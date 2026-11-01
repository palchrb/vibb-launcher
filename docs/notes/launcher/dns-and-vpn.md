# Launcher notes: DNS filtering, the custom DNS server, tsnet/VPN history, the browsers' secure DNS

Moved from launcher/CLAUDE.md on 2026-11-01.

## Architecture change in progress (2026-08-07): on-device DNS filtering + embedded tsnet

**The DoT-to-Pi Private DNS approach described in the section below is being replaced.** Confirmed live it hit a hard Android limitation: `setGlobalPrivateDnsModeSpecifiedHost`'s strict-mode bootstrap resolution structurally cannot resolve a Tailscale MagicDNS (`*.ts.net`) hostname (Android's private-DNS validation uses the network's own DHCP/underlying DNS for that one lookup, which can never know about `*.ts.net`) - this caused intermittent but real DNS breakage, confirmed via `NetworkMonitor: Strict mode hostname resolution ... UnknownHostException` and reproduced live via `curl: Could not resolve host` for the app's own server calls. Fixing that properly would need a real public domain + ACME DNS-01 automation on the Pi - real ongoing infrastructure for what should be a simple feature.

**New direction**: ad/content blocking moves fully on-device via the launcher's own `VpnService` (DNS66/RethinkDNS-style local packet filtering - not yet built, see `kid-phone-server`'s CLAUDE.md Phase C/D), and the standalone Tailscale app is being replaced by embedding Tailscale's `tsnet` library directly into the launcher via `gomobile bind`, so the launcher's own `VpnService` can be the device's only VPN (Android only allows one) while still reaching the server over the tailnet.

**Embedding tsnet - two real gobind limitations hit and worked around, confirmed via CI + `javap` on the actual built `.aar`, not guessed:**
- Go 1.24+ requires build tools (`gomobile`/`gobind`) to be declared via a `tool` directive in the *target* module's own `go.mod`, not just `go install`'d globally on the build machine - see the `tool` directive in `mobile/go.mod` (committed, with every other module version pinned in `go.sum`).
- `gobind` cannot bind `tsnet.Server` directly - several of its methods (`Loopback` in particular) return more values than gobind's Java bridge supports (zero-or-one value plus an optional error, no more). Fixed with `mobile/tsembed`, a thin wrapper exposing only a gobind-compatible surface (`Up`/`StartProxy`/`ProxyCredential`/`Close`), calling tsnet's richer API internally in plain Go. This turned out to be the *right* design anyway, not just a workaround: `Loopback()`'s local SOCKS5 proxy is tsnet's own documented mechanism for embedding into a non-Go program, so `TsnetClient.kt` talks to it via `java.net.Proxy`/`java.net.Authenticator` (confirmed via tsnet's own docs: the proxy always expects username `"tsnet"`, with the per-session `proxyCred` as password) rather than needing any Go network type bridged into Java at all.

**Current state**: CI builds `tsnet.aar` successfully (arm64-only) and it links into the app; `TsnetClient.kt` and `MdmApi.createMdmApi` are wired so sync traffic routes through the embedded tailnet whenever a Tailscale auth key is configured (Settings entry, `mdm.tailscale_auth_key` - a parent-generated Tailscale reusable pre-auth key, entered once). **Confirmed live and working on both test phones (2026-08-07)** - policy sync succeeds over the embedded tailnet with the standalone Tailscale app not required for this path. Getting there required three real bugs found via live logcat, not guessed:
- **`panic: no safe place found to store log state`** - `tsnet.Server.Start` calls `logpolicy.LogsDir()` internally (separate from `Server.Dir`), which searches `TS_LOGS_DIR`, platform defaults, `os.UserCacheDir()`, cwd, then a temp dir, and panics if none work. All fail on Android (no platform default, no `$HOME` for `UserCacheDir`, unwritable cwd) - this crashed the whole app process in a loop (`SIGABRT`, `CommandListenerService`'s restart-on-crash kept re-triggering it every ~30s). Fixed in `mobile/tsembed/tsembed.go`'s `New()` by setting `TS_LOGS_DIR` to a subdir of the already-writable `stateDir` before constructing the server.
- **`TsnetClient.connect()` cached its `Client` on the first call per process and never rebuilt it** - so changing the auth key in Settings had no effect until a full app restart, since the Go-side `tsnet.Server.AuthKey` field is only set at construction. Fixed by tracking the `(hostname, authKey, stateDir)` the current client was built with and rebuilding when they change (see `TsnetClient.kt`).
- **`netlinkrib: permission denied` during the actual tailnet-join/route-setup step reappeared intermittently even after the earlier interface-enumeration fix** (`netmon.RegisterInterfaceGetter` + `github.com/wlynxg/anet`) - turned out to only manifest transiently on some cold starts; a retry (new sync cycle / process restart) got past it cleanly both times it was hit live. Not fully root-caused past "some other net.Interfaces()-calling code path than the one already patched" - watch for a repeat before treating this as fully resolved.
- Also confirmed: an invalid/stale auth key produces a clean `tsnet.Up: backend: invalid key: API key does not exist` from Tailscale's control server - don't misdiagnose that as a code bug. Hand-typing a ~57-character `tskey-auth-...` key on-device is genuinely error-prone; copying it from the Tailscale admin console opened in the phone's own browser (rather than retyping from another device) avoids transcription mistakes entirely. A QR-code/interactive-login flow (surfacing `tsnet`'s browser login URL instead of requiring a static pre-auth key) was raised as a real future improvement, not yet built.

The `VpnService`-based local DNS filter itself (the actual replacement for DoT-to-Pi blocking, Phase C) is built but not yet live-tested. **Phase D shipped 2026-08-08, live-verified on the Pixel**: `KidVpnService.start()`/`TsnetClient.connectFromPreferences()` are both now called unconditionally from `Application.onCreate()` (each fails soft if not ready yet - no VPN consent granted, no auth key configured - and self-heals: `KidVpnService` via Android's own always-on-VPN service management once consent is granted, `TsnetClient` via `MdmSyncWorker`'s retry-until-connected call every sync cycle). `applyPrivateDnsLock` is simplified to unconditionally lock Opportunistic mode (never a specific host - see "Current status (2026-08-06)" below for the retired hostname-locking behavior this replaced) + `DISALLOW_CONFIG_PRIVATE_DNS`, on every `apply()`, not gated on any policy field. All Tailscale-app-specific code is deleted: the `TAILSCALE_PACKAGE` constant/suspend-exemption, the `ForceEnabled`/`ExitNodeID` managed-config push, and the client-side `PolicyResponse` fields (`requireTailscale`, `tailscaleExitNodeId`, `forcePrivateDnsToPi`) are all gone.

**`AppEnforcer.applyVpnRestrictions` calls `setAlwaysOnVpnPackage` pointed at the launcher's own package with lockdown OFF - a real live bug caught on the very first test, not a hypothetical.** The initial version enabled lockdown, reasoning that `KidVpnService` never capturing general traffic (only its one fake-DNS-server route, by design - see that class's doc comment) meant there was no split-tunnel gap for lockdown to strand anything in. That reasoning was wrong: `dumpsys connectivity` showed that once this VPN becomes the system default network, lockdown forces *every app's* traffic onto it regardless of what routes it declares - and since `KidVpnService` deliberately has no general/default route, general internet connectivity broke device-wide immediately (confirmed via the VPN network's captured routes: only the DNS-server address plus an explicit `::/0 unreachable`). This is the identical failure mode to the old Tailscale-without-an-exit-node bug (`github.com/tailscale/tailscale#12925`) that already motivated gating lockdown on an exit node elsewhere in this file - except here there's no equivalent "configure something broader" fix to gate on, since narrow routing is `KidVpnService`'s permanent design, not a transient unconfigured state. Lockdown must stay off for this VPN, full stop. Without it, Android's always-on designation still auto-restarts the service and still blocks a kid from disabling it via Settings; the only cost is DNS briefly going unfiltered through the OS's normal path if the service is ever down - an acceptable gap next to bricking the device.

Also found and fixed on the same test pass: **`CommandListenerService`'s SSE `OkHttpClient` was never wired to `TsnetClient`'s SOCKS5 proxy at all**, and was cached via `by lazy` (the same staleness class of bug already fixed once in `TsnetClient.connect()` itself). It only ever worked before because the standalone Tailscale app provided OS-level MagicDNS/routing for the server's tailnet-only hostname - once `KidVpnService` became the device's sole VPN, that stopped being available, and the command stream failed to connect indefinitely ("Command stream connection failed, reconnecting" looping). Fixed by building a fresh, proxy-aware client on every `connect()`/reconnect attempt, mirroring `createMdmApi`.

**Phase E (server-side, retiring `dns_engine.rs`'s live DNS listener) and the `require_tailscale`/`tailscale_exit_node_id`/`dns_filter_settings.enabled` cleanup are both done** - see `kid-phone-server`'s own CLAUDE.md for the Phase E writeup. In their place: `device_policy.vpn_filter_enabled` (default on), a real per-device admin toggle for `KidVpnService` itself, surfaced as the "Web filter" switch on the phone's Web Filter page in the PWA (`/devices/{id}/filter`, design 31 step 5) and threaded through `PolicyResponse.vpnFilterEnabled` to `AppEnforcer.applyVpnRestrictions`, which starts/stops the service and sets/clears the always-on-VPN designation to match. The on-device "pause all restrictions" kill-switch and the offline-override PIN both force this off too while active (not just the admin toggle) - confirmed live this needed fixing once already: an earlier version deliberately excluded `vpnFilterEnabled` from that "fully open" treatment, reasoning an access-restriction escape hatch shouldn't override a parent's separate content-filtering choice - wrong in practice, since the whole point of those overrides is a guaranteed working, unblocked device when something's wrong (which can include the VPN/filter itself).

**A real ANR was caught and fixed getting the toggle working**: `SettingsFragmentLauncher`'s "pause all restrictions" listener has always called `AppEnforcer.apply()` directly inside a main-thread `view.post{}` block - previously cheap enough (DevicePolicyManager Binder calls) not to matter, but once `applyVpnRestrictions` started (re)starting `KidVpnService`, whose `onCreate()` does a synchronous blocklist-file read (`DnsFilterEngine.loadFromDisk`), this froze the UI thread for several seconds (confirmed live: `Davey! duration=3876ms`, 230 skipped frames) and got the process killed. Fixed by having the posted block hand off to a background coroutine instead of running `apply()`/`reevaluateLockReasonFromCache()` itself - matches every other `AppEnforcer.apply()` caller in the codebase, which were already off the main thread.

**Confirmed live and fully working on both test phones (Pixel 4a 5G, Moto G Play) as of 2026-08-08**: ad-blocking, policy sync, the new per-device filter toggle (both the server checkbox and the local pause-restrictions switch), and the blocked-domain log at `/dns/log` all verified end to end. Not yet built (deliberately deferred, tracked as a backlog task): the QR-code/interactive-login flow for the tsnet auth key mentioned above. Also not yet cleaned up: the server's `com.tailscale.ipn` tracked-app row still silently auto-updates the now-unused standalone Tailscale app - harmless, minor, low priority.

## A custom DNS server with a parent-chosen backup (design 36 steps 1-3, `docs/design/36-custom-dns.md`, at the monorepo root)

- **What it does**: with Settings › Web Filter › "Custom server" the VPN forwards every query **the phone's lists let
  through** (FCM/Spotify exemptions and `BlockedEventLog` unchanged - the lists always block first) to the parent's
  server - DoH or DoT, by name or IP - and, only while that server as a whole is unhealthy, to the parent's backup.
  A failing backup drops the query as a failing Cloudflare does (no third tier); never plain DNS, never unfiltered.
  Every connection is a `protect()`ed socket on the phone's current network, **never through tsnet** (a home-network
  server works on the home Wi-Fi, the backup answers elsewhere). Without a custom server
  `DnsFilterEngine.resolveProvider` is DoT to Cloudflare/Quad9 (through `resolveDoT`; `PROVIDER_BUDGET` 2.5 s connect
  since step 3's fix round, 10 s in all), with
  step 3's DoH on 443 where 853 is blocked (below).
- **Policy and pref** (step 1's wire): `PolicyResponse.dnsUpstream` is raw `JsonElement?` read field by field by the
  pure `server/CustomUpstream.kt` `customUpstreamFrom` (kind `doh` with an `https://` URL OkHttp parses, or `dot` with
  a host and a port 1-65535, a numeric `rev`, a backup passing the same check - anything else is `null` = the provider;
  `PolicyResponseCompatTest`). `storeAcceptedPolicy` writes `dns_upstream_provider` and `dns_upstream_custom`
  (`customUpstreamPref`: the raw JSON when valid, else removed) in the policy's one `commit()`; a decode failure or a
  rejected policy never touches either; not in `LastEnforcedPlan`. Then `DnsFilterEngine.setCustomUpstream` (memory,
  `@Volatile`; `loadFromDisk` reads the pref at VPN start, so a reboot offline keeps the last accepted servers) tells
  the running VPN (`onCustomChanged`). `CustomUpstreamPrefTest` pins the one writer.
- **`server/CustomDns.kt`** (glue, one per config while the VPN is up; `KidVpnService.syncCustom` builds a new one for
  new servers - fresh state, fresh client - and `close()`s the old): routes a query, runs the canary and the
  bootstrap look-ups, relays. The backup's answer is relayed as is; the backup's outcomes never touch the state.
- **The rule** (pure `server/UpstreamChooser.kt`, state `ChooserState` in one `AtomicReference` changed by CAS): an
  attempt at the primary is an `Answer` (relayed - NXDOMAIN and `0.0.0.0` included), a `Transport` failure (no
  address, connect, TLS, HTTP, a stream closed or silent before the request was written - a period starts: backup for
  60 s doubling to 15 min, `gen` up so a burst counts once) or `Odd` (REFUSED, SERVFAIL, silence after the request was
  written). `Odd` asks the canary (`. IN NS` to the primary, 2 s, one in flight, verdict kept 60 s): a healthy canary
  makes the name's own outcome stand - **REFUSED is the phone's NODATA (`blockedResponse`), SERVFAIL/silence a
  SERVFAIL, never the backup for that name** -; a failed one is a transport failure (`refused` for a REFUSED canary,
  AdGuard Home's answer to a client id it doesn't allow). A query that gives up waiting for another's canary leaves
  the state alone and answers as from a healthy server. One ~4.8 s deadline per query (`QUERY_DEADLINE_MS`: primary
  2.5 s, canary 1-2 s, backup the rest, 1.3-2.5 s) keeps a failed canary's query inside the stub's first try. In a period one query per back-off probes the primary
  (`probing`), the rest go to the backup. `reset` (a network reset or new servers) tries the primary at once.
  Battery: one probe per back-off, one canary per 60 s at most.
- **Transport** (`server/UpstreamTransport.kt`): `resolveDoT(query, addresses, host, port, budget, protect)` - at most
  two addresses, IPv4 first, one budget (1.5 s connect, 2.5 s in all for the parent's servers), the next address only
  while nothing was written; a name gets SNI + endpoint identification, an IP no SNI and the default hostname
  verifier on the IP after the handshake. DoH: `buildDohClient` - **one OkHttpClient per `CustomDns`, from a fresh
  `OkHttpClient.Builder()` (never `newBuilder()` of the API/SSE clients, which carry tsnet's SOCKS proxy)**,
  `Proxy.NO_PROXY`, `ProtectedSocketFactory` (sockets protected before they connect), `BootstrapDns` as its `Dns`,
  1.5 s connect / 2.5 s call, no redirects, no cache, no cookies, system CAs; a per-call `EventListener` (`CallMarks`)
  splits a timeout at the written request. POST `application/dns-message`, ID 0 out, the query's ID back in; a
  response that isn't 200 + `application/dns-message` (parameters ignored) + <= 65535 bytes + parsable is `http`.
  Pool evicted on a network reset and on close.
- **Bootstrap** (`server/BootstrapDns.kt`): an IP literal is itself (`ipLiteral` parses it into bytes - never
  `InetAddress.getByName`, which would resolve a malformed one through our own VPN), `cloudflare-dns.com` is
  `1.1.1.1`; a name is A then AAAA through the bootstrap resolvers (`bootstrapResolvers`: the backup if it is an IP,
  the primary if it is, then Cloudflare `1.1.1.1` DoT; the look-up - never a query - falls through to the next on a
  transport failure or silence, and a step that failed for A is skipped for AAAA, so a home-IP primary away from home
  still lets a backup by name be found), never the system resolver and never through the lists or the chooser, 1 s
  connect (DoH too, per call from its `Budget`), 2 s in all. `BootstrapCache` (memory): the CNAME chain's final name,
  TTL clamped 5 min-24 h, stale served while one refresh runs and kept when it fails, one look-up per name at a time,
  a failed look-up (stale or with nothing cached) retried after 30 s or a network reset. Both
  names are looked up when a config loads. A primary with no address yet sends that query to the backup (no failure);
  nothing at all after its look-up is a `bootstrap` period (not stepped again inside a period). A backup with no address drops the query.
- **Networks that block 853** (step 3, pure `DotBlockedRule` + glue `PublicResolver` in `server/PublicResolver.kt`):
  Cloudflare and Quad9 (`PublicResolvers`, one state each per process) fall back to **the same provider's** DoH on 443 by
  its IP-literal URL - `https://1.1.1.1/dns-query`, `https://9.9.9.9/dns-query` (no SNI, OkHttp's verifier on the IP;
  their certificates name it), never the other provider's. A DoT `Transport` failure (connect refused/timed out, reset,
  TLS) counts; 3 within 60 s start a DoH period of 5 min, doubling with each failed probe to 1 h; in a period one query
  per period probes DoT (within at most `UPSTREAM_BUDGET`, so its query still gets DoH in time), the rest go straight
  to DoH; DoT that reaches the server (an answer, or anything above TLS) or a network reset (`PublicResolvers.reset`
  from the VPN's `networkChanged`) ends it. A query whose DoT fails goes on to DoH itself while its ~4.8 s deadline
  (`QUERY_DEADLINE_MS`) leaves at least `BACKUP_FLOOR_MS` - a refused connect and a dropped one alike, since the
  providers' DoT connect is 2.5 s (`PROVIDER_BUDGET.connectMs + BACKUP_FLOOR_MS <= QUERY_DEADLINE_MS`, pinned). In a
  period a DoH transport failure goes on to DoT the same way, and a DoT that works ends the period (a network that
  blocks the provider's 443 but not 853). A transport that throws counts as a connect failure (`probing` can't stick).
  The providers' DoH client is the VPN's own `buildDohClient(IP_LITERAL_DNS, ::protect)` (built at its first DoH use,
  evicted on a reset and at stop; `IP_LITERAL_DNS` never looks a name up), never tsnet. The bootstrap's last resolver is `PublicResolvers.cloudflare` too (its DoT, or its DoH on
  443), with the bootstrap's budgets. **Under Custom the parent's servers are what they are** - a parent's DoT server
  never gets a guessed DoH URL (`PublicResolverTest` pins that a DoH target is built only from the parent's URL or the
  two public ones); a DoT backup on a network that blocks 853 fails like any backup. The status field is unchanged.
- **The bootstrap's disk cache** (step 3, `BootstrapDiskCache` in `BootstrapDns.kt`): `noBackupFilesDir/
  dns_bootstrap_cache.txt` (CE storage, 0600 by default, **never device-protected storage, never logged** - it holds a
  host name the parent typed): `v1`, then a line per name: host, expiry as wall-clock ms, at most two addresses. Read
  when a `CustomDns` is built, before any look-up (only the config's names; the expiry back in elapsed time, at most
  24 h ahead; an expired entry is stale - served while one refresh runs; anything malformed = no cache; addresses parsed
  by `ipLiteral`, never looked up), written after each look-up that found addresses through a temp file + rename
  (`writeAtomically`), never by a closed `CustomDns`. Kept only for a custom server with a name
  (`BootstrapDiskCache.keptFor`): `syncCustom` deletes it otherwise **before its early return**, so a Forget while the
  VPN was off is forgotten at the next start; reading it drops another config's names (rewritten, or deleted when
  nothing usable is left). The parent's prefilled backup is `https://1.1.1.3/dns-query` (DoH, so it answers where 853
  is blocked; a stored `tls://1.1.1.3` stays as the parent saved it).
- **protect needs a descriptor** (the 0.29.0 bug, fixed 2026-10-19 on branch `vpn-restart-fix`): Android's `Socket()`
  creates no file descriptor until a bind or connect (`Socket.getFileDescriptor$()` returns the impl's fresh
  `FileDescriptor`, -1; libcore creates the socket only in `getImpl()`), so `VpnService.protect(Socket())` returned
  false every time and design 36's `resolveDoT`/`ProtectedSocketFactory` threw "not protected" for **every** upstream
  socket - DoT and the step-3 DoH alike, `DnsUpstream: DoT/DoH attempt failed: IOException` in pairs, then a bogus
  "DoT blocked" period. The VPN answered no query, so every name failed device-wide, and the launcher's own sync and
  SSE stream died with it (the server's name resolves through the VPN too). Before design 36 the old DoT code ignored
  protect()'s result and worked unprotected. It shows at every VPN start (enrolment, the end of a pause, the Web filter
  switched on, a reboot), not only after a pause; a Wi-Fi toggle doesn't help. **Rule**: every socket the engine protects
  comes from `protectedSocket(protect, local)` (`UpstreamTransport.kt`: `Socket()`, `bind(local)` - an ephemeral port
  on any address -, then protect, closed when refused; a refusal is `NotProtectedException`, so the log names it). A
  refusal is `Attempt.Unprotected` (`unprotected(e)` over the cause chain): the query is dropped and **nothing counts**
  - no chooser period, no canary verdict, no "DoT blocked" (`DotBlockedRule.afterUnprotected` only gives a probe back);
  the factory's connecting overloads close their socket when the connect throws (qa-vpn-protect-code L1-L3).
  `ProtectedSocketTest` pins the bind before protect, the one plain `Socket()` in the file and that no other engine
  file makes its own; a JVM test can't see the descriptor, so the emulator check (`docs/testing/emulator.md` §6m step 8)
  is the proof.
- **Network reset**: `KidVpnService` registers `registerBestMatchingNetworkCallback` (INTERNET + NOT_VPN; the VPN is
  its own default network) and resets on the pure `networkChanged` (another network, or one that gained VALIDATED -
  a captive portal logged in); unregistered in `stopSelfCleanly`. Never set underlying networks
  (`VpnMeteredInvariantTest`); lockdown stays off.
- **Status**: `StatusReportRequest.dnsUpstream` = `KidVpnService.dnsUpstreamReport()` - only while the tunnel is up
  (design 67: `state.tunnel` is volatile and read there; a dead read loop sends none): with a custom server `{rev,
  in_use: custom|backup, since_ms (wall clock, from elapsed time), reason (backup only):
  bootstrap|connect|tls|http|refused|timeout, unreachable_since_ms, unreachable_until_ms}`, with a provider `{rev: 0,
  in_use: provider, since_ms: null, reason: null, ...}` (`providerReport`), never an address (`upstreamReport`;
  contract `dns_upstream_status.json`, byte-identical with `../server/testdata/`, `DnsUpstreamStatusTest`,
  `UpstreamChooserTest`). Capability `dns_custom_v1` in `STATUS_CAPABILITIES`.
- **DNS can't be reached** (design 67 §3, pure `server/UpstreamReach.kt`; glue `ReachTracker`, one `AtomicReference`
  per `KidVpnService` instance, CAS): `CustomDns.resolve` and `DnsFilterEngine.resolveProvider` return `Resolved(bytes,
  answered)` and `reachKind` reads it - FAILED: dropped with no server bytes, whatever the cause (every attempt a
  transport failure, silence after the request, `Attempt.Unprotected`, an exception in our code); ANSWERED: server
  bytes relayed (NXDOMAIN, a relayed REFUSED/SERVFAIL too), or our NODATA/SERVFAIL after a healthy canary; NEITHER: our
  SERVFAIL/NODATA after a canary the query gave up waiting for. The chooser and `DotBlockedRule` still ignore
  `Unprotected`; only this count reads it. A failure counts only while `lastNetwork` (`@Volatile`) is validated
  (offline - airplane mode, a Wi-Fi-only phone away - is no outage), and `validatedLost` in `onNetwork` ends an
  ongoing outage. The key is `reachKey` (`custom:<rev>` or the provider's label). At least 3 failures (`MIN_FAILURES`)
  over at least 30 s (`MIN_SPAN_MS`) with no answer between make an outage dated from the first failure (`Upstream
  unreachable, failures=N` once); a failure more than 60 s (`GAP_MS`) after the last restarts the count; an answer, a
  new key (a new `rev` - `syncCustom` rekeys at once - or another provider) or the loss of the validated network end it
  into `lastOutage` (`Upstream outage ended`), which a rekey keeps; a VPN restart starts afresh, a network reset changes
  nothing. In custom mode nothing counts while the backup answers. `outageTimes` gives the report's two wall-clock
  times: ongoing `(since, null)`, the last ended `(since, until)`, none `(null, null)`. Tests: `UpstreamReachTest`
  (the window, the 60 s rule, offline, the rekey, CAS, the kinds, the report), `CustomDnsTest` (both failed, the probe
  and the backup failed, an exception, a healthy and a given-up canary).
- **Secrets**: both targets may hold an AdGuard Home client id. `CustomUpstream`/`UpstreamTarget` `toString`s are
  redacted; log lines name modes, kinds, reasons, counts and exception classes only (never `e.message`, which OkHttp
  fills with the URL) - `CustomUpstreamPrefTest` scans the log calls; the policy decode failure and a corrupt cache are
  logged by exception class only (`PolicyGate`: a `JsonDecodingException` quotes the input).
- Tests: `UpstreamChooserTest` (every transition, the burst, the back-off cap, the canary verdicts, reset, the outcome
  mapping, `networkChanged`, the status field against the contract), `CustomDnsTest` (the glue with a fake transport:
  backup DoT/DoH, its failure leaving the state, REFUSED/silence from a healthy server, the canary reused and its
  waiter, reset, the bootstrap glue and its fall-through), `CustomDnsGuardsTest` (the lists first, the DoH client's
  protected sockets and no proxy, no status field once the VPN is off),
  `BootstrapResolverTest` (with the disk cache's codec, expiry, corrupt files and the rename), `DohMessageTest`,
  `CustomUpstreamPrefTest` (its log scan covers `PublicResolver.kt`), `PolicyResponseCompatTest`,
  `DnsUpstreamStatusTest`, `PublicResolverTest` (step 3: the rule's threshold, window, back-off and cap, the probe,
  reset; each provider's own DoH; the probe's budget; no guessed DoH URL; the providers' client). `CustomDnsTest` also
  covers the bootstrap's Cloudflare step over DoH and the disk cache across two instances.
- **Not device-tested**: a real DoH/DoT server through the protected sockets (emulator check `docs/testing/emulator.md`
  §6m), a LAN server on the home Wi-Fi then away, a network that blocks 853, the canary against AdGuard Home's REFUSED
  for a removed client id, Wi-Fi/mobile resets on the Jelly Star; step 3's DoH on 443 on a network that blocks 853
  (both providers' certificates on their IPs, the period and its probe, a reboot there with a parent's server by name -
  `docs/testing/emulator.md` §6m step 7).

## Current status (2026-08-08, continued): the browsers' secure DNS (design 67)

(This block sat inside the "Current status" section, now in gotchas-and-history.md; moved here verbatim on 2026-11-01.)

**The browsers' secure DNS** (design 67 §2, `docs/design/67-dns-bypass.md`; security review S-M3 - before it, one
"Use secure DNS" switch in an allowed browser bypassed the whole filter). A VPN/DNS-level filter can't tell a DoH request
from any other HTTPS, so three parts, all following `vpnFilterEnabled` (lifted by the override, the pause and the
parent's switch) and claimed together as capability `dns_bypass_v1` (`DNS_BYPASS_CAPABILITY`):
- **The managed config** (`server/BrowserDoh.kt`, the only part that really closes it, in browsers that read it):
  `AppEnforcer.apply` calls `BrowserDoh.apply` right after `applyVpnRestrictions`; pure `browserDohPlan(filterOn,
  installed)` gives every installed (hidden ones too: `MATCH_UNINSTALLED_PACKAGES`) package of `BROWSER_DOH_PACKAGES`
  exactly `{"DnsOverHttpsMode": "off"}` (a String) while on and an empty bundle while off, written with
  `setApplicationRestrictions` only when it differs (`sameBundle`, shared with MusicSync). A fixed list, not the
  restrictions schema; not tied to the allowlist (a browser allowed later starts with DoH off). Stock Chrome on a
  device-owner phone likely has DoH off already (Chromium's `ShouldDisableDohForManaged`); the policy closes the startup
  window and covers forks. Which browsers read it (E3, `docs/testing/emulator.md` §6m checks 9 and 16; fill in):

  | Package | Browser | Reads `DnsOverHttpsMode`? |
  |---|---|---|
  | `com.android.chrome`, `com.chrome.beta`, `.dev`, `.canary` | Chrome | yes (documented, Android 85+); E3 2026-10-26: the launcher sets it (`BrowserDoh` log), `chrome://policy` still a device check (Chrome draws nothing on the swiftshader emulator) |
  | `com.microsoft.emmx` | Edge | not checked yet (`edge://policy`) |
  | `com.brave.browser`, `_beta`, `_nightly` | Brave | not checked yet (`brave://policy`) |
  | `com.vivaldi.browser` | Vivaldi | not checked yet (`vivaldi://policy`) |
  | `com.sec.android.app.sbrowser` | Samsung Internet | a device check (no emulator image) |
  | `com.kiwibrowser.browser` | Kiwi (discontinued 2025) | not checked yet |
  | `com.opera.browser` | Opera | not checked yet (`opera://policy`); the server's Each Phone line warns until then |

  Not listed: Cromite (its patch drops the provider; the server's Each Phone line tells the parent to set its Secure
  DNS to Off), the Firefox family (`org.mozilla.firefox`, `_beta`, `org.mozilla.fenix` - Nightly,
  `org.mozilla.fennec_fdroid`, IronFox `org.ironfoxoss.ironfox`: Gecko reads no Chromium policy; the canary below, and
  an Each Phone line each - `dns_filter::DOH_IGNORING_BROWSERS`), Vanadium (not on these phones). A browser that
  doesn't read it stays listed.
- **The built-in secure-DNS hosts** (a best-effort backstop): `assets/dns/doh_hosts.txt`, one name per line, each a
  suffix, listed one by one (no `doh.`/`dns.` first-label rule: `doh.wa.gov` is a health department), loaded by
  `DnsFilterEngine.loadFromDisk` **before** the list's early return (in force before the first sync). `classifyDomain`
  takes it as its fourth input: FCM and Spotify first, then the set (category `"Secure DNS server"`, the phone's NODATA,
  recorded by `BlockedEventLog` like any block while the parent has that phone's log on), then the server's lists. The
  parent can't lift it (design 67 §6), but turning the phone's filter off does. Only ever asked for queries through the
  tunnel: our own upstreams are IP literals or looked up by `BootstrapDns` on protected sockets, so a parent's backup
  `https://family.cloudflare-dns.com/dns-query` still works. Side effect: an app's own DoH fallback (Telegram's
  `dns.google`) falls back to the filtered system resolver.
- **Firefox's canary**: `use-application-dns.net` (the exact name, any QTYPE) is answered NXDOMAIN with the SOA
  (`nxdomainResponse`) in `handlePacket` before `classify` - never forwarded, logged or counted. It turns off the DoH
  Firefox enabled by default; a user's own "Increased" falls back to us, "Max" with an unlisted host still bypasses.
  Next to it DDR's `_dns.resolver.arpa` (RFC 9462) gets the phone's NODATA, never relayed (`isDdrQuery`).
  The server keeps a byte-identical copy of the host list (`server/testdata/doh_hosts.txt`, its Allowed Domains notice;
  `DnsFilterEngineTest` compares).
- **What stays open** (the page says so): a browser that ignores the managed config (Firefox, Cromite) set by hand to
  an unlisted DoH host or an IP-literal template (`https://1.1.1.1/dns-query`). Routing the resolvers' IPs into the
  tunnel is design 67's non-goal.
- Tests: `DnsFilterEngineTest` (the file's lines, hits and misses, the order, the load before the early return),
  `NxdomainTest`, `BrowserDohTest`, `SyncScheduleTest` (the capability), `CustomDnsGuardsTest` (the canary before the lists).
