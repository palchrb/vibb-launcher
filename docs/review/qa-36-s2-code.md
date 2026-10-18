# QA 36 step 2 (code): the launcher side of a custom DNS server with a parent-chosen backup

Code QA, 2026-10-18. Commit under review: `a54a8fa6` (branch `dns-s2`, base `main` `b0d59eb1`), launcher only. I read it
against `docs/design/36-custom-dns.md` (§4.2, §5.1-5.4, §7, §8, §9, "Step 2 implementation status"),
`docs/design/qa-36-design.md`, `docs/review/qa-36-s1-code.md`, the root and launcher `CLAUDE.md`. Line numbers are
`a54a8fa6`'s, under `launcher/app/src/main/java/com/kidslauncher/mdm/server/` unless named. No product code was changed;
every mutation was reverted (`git checkout -- launcher/app/src`, tree clean before this file was written).

**Verdict: sound core, merge after a fix round.** The pure rule matches §5.4 exactly: the burst rule by `gen`, a
back-off of 60 s doubling to the 15 min cap, one probe (CAS on `probing`), the canary deciding `Odd`, REFUSED from a
healthy primary turned into the phone's NODATA and never sent to the backup, SERVFAIL or silence relayed as SERVFAIL,
and a reset on a network change or new servers. The phone's lists still decide before any resolver
(`KidVpnService.kt:212-222`). A failing backup drops the query, with no third tier. The pref goes into
`storeAcceptedPolicy`'s one `commit()`. The status field exists only while the VPN runs with Custom. Logs name kinds,
reasons and exception classes only. The DoH client is built fresh with `NO_PROXY`, protected sockets and
`BootstrapDns`. What has to change:
- **H1**: the bootstrap hole (the design's open point (3), the orchestrator's (a)). It is a full DNS outage in a
  setup the PWA doesn't warn about.
- **M1**: a failed refresh of a stale cache entry starts a new look-up on every query.
- Two small canary races (L1, L2).
- Three guards that no test pins (L3: the lists first, the protected socket factory, the status while the VPN is
  off; the mutations show them), and no test of the bootstrap glue (L4).

## Runs

| Check | Result |
|---|---|
| launcher `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest -PwarningsAsErrors=true` | BUILD SUCCESSFUL; 1325 tests, 0 failed, 0 skipped |
| new test classes | `UpstreamChooserTest` 11, `CustomDnsTest` 6, `BootstrapResolverTest` 5, `DohMessageTest` 4, `CustomUpstreamPrefTest` 3 (29) |
| compiler warnings | only the two known kapt `RELEASE_11` lines; none in the diff |
| contract copies | `dns_upstream_status.json`, `time_status_report.json` untouched (the diff has no `server/` or test-resource change) |

## Findings

### High

#### H1. A home-network primary by IP and a backup by name: after a reboot away from home, no DNS at all until home

`CustomDns.kt:45, 152-173` (`lookUp` asks the one `resolver`), `BootstrapDns.kt:50-59` (`bootstrapResolver`).

**What breaks.** Take primary = `192.168.1.2` (AdGuard Home on the LAN, DoT or a DoH IP URL) and backup = a name
(`https://family.cloudflare-dns.com/dns-query`, or `tls://dns.adguard-dns.com`). `bootstrapResolver` then picks the
primary, so the backup's name can only be looked up *through the primary*. If the phone reboots away from home, or the
VPN service is restarted there, the memory cache is empty and the look-up goes to an unreachable LAN address:
- every query is a `Transport` at the primary (a period starts), then `backup()` finds no address and drops it
  (`CustomDns.kt:108-109`);
- the look-up is retried every 30 s and at each network reset, always through the same unreachable resolver.

The result is no DNS for any app until the phone is on the home Wi-Fi again. It fails closed, but it is a full
outage, and it looks like "the phone has no internet". The PWA's "give the backup as an IP" line shows only while
*both* values are names (step 1), so it never warns about this setup, which is a natural one: a LAN server plus a
public filtered backup. The design note lists this as open point (3) with "the owner's call". It is a correctness hole
in the bootstrap order, not a preference.

**Fix (the orchestrator's decision).** The bootstrap *look-up* falls through the order when the chosen resolver fails
at the transport level: backup-IP, then primary-IP, then Cloudflare `1.1.1.1` DoT. This is for the name look-up only;
the queries themselves never fall through.
- Replace `bootstrapResolver(config): UpstreamTarget?` with `bootstrapResolvers(config): List<UpstreamTarget>`: the
  backup if it is an IP literal, the primary if it is, then `CLOUDFLARE_BOOTSTRAP` (empty when both are IPs). Leave out
  the target whose own name is being looked up; it can't be an IP anyway.
- In `lookUp`, try them in order with `BOOTSTRAP_BUDGET` per step. Move to the next on a `Transport` attempt, and on
  silence after the request (`Odd(TIMEOUT)`), which is what an unreachable-after-connect resolver looks like. Stop at
  the first usable DNS answer. An NXDOMAIN or an empty NOERROR is an answer and does *not* fall through.
- Once a step failed at the transport level for A, use the next step for AAAA too. Don't pay for the dead resolver
  twice.
- Keep everything else as it is: the cache, the 30 s retry, the `bootstrap` period for the primary's name.
- Add the sentence to §5.3: "When the bootstrap resolver fails at the transport level, the look-up (only the
  look-up, never a query) falls through to the next in this order, with the same budget per step." Update §7's
  "the bootstrap resolver sees the servers' names only": Cloudflare sees a server's name also when the parent's
  IP servers are unreachable. Update the launcher `CLAUDE.md` Bootstrap bullet and close open point (3) in the step 2
  status.
- Test in `CustomDnsTest`: primary `192.0.2.10` (transport failure), backup by name, fake transport. The look-up goes
  primary, then `CLOUDFLARE_BOOTSTRAP`, and the backup then answers. With a working primary, Cloudflare is never asked.
  An NXDOMAIN from the primary doesn't fall through. This is also the first glue test of `lookUp` (L4).

### Medium

#### M1. A failed refresh of an expired entry starts a new look-up on every query

`BootstrapDns.kt:120-126` (`get`'s `addresses.isNotEmpty() -> now >= entry.expiresAtMs`) with `failed`
(`:141-145`), which keeps the old `expiresAtMs`.

**What breaks.** After a failed refresh the entry still has addresses, its expiry is in the past and `lookingUp` is
false, so the very next `get` is due again: one look-up per query, back to back. The 30 s wait applies only when
nothing is cached. This happens wherever a refresh can't reach the bootstrap resolver:
- the H1 setup after the backup's TTL (5 min floor) runs out away from home;
- a network that blocks 853, so Cloudflare DoT fails (step 3);
- a captive portal.

For the primary's name this runs on every healthy-route query (`CustomDns.kt:70`), and for the backup's on every
query in a period. Each look-up is A and AAAA, up to 2 s each, on the IO pool. That is per-query background work and
a battery drain, which the design excludes ("no per-query background work"). The test (`BootstrapResolverTest.kt:67-69`)
checks only that the addresses stay, not the `lookUp` flag.

**Fix.** Apply the same `RETRY_AFTER_FAILURE_MS` to a stale entry:
`addresses.isNotEmpty() -> now >= entry.expiresAtMs && (entry.failedAtMs == null || now - entry.failedAtMs >= RETRY_AFTER_FAILURE_MS)`.
`forgetFailures` (network reset) already clears it. Extend the test: after `failed(…, 300_500)`, `get(…, 300_600).lookUp`
is false, and `get(…, 330_500).lookUp` is true.

### Low

#### L1. Two canaries can run back to back

`CustomDns.kt:125-146`. Thread B reads `needsCanary == true` (`:126`) before thread A's verdict lands. A finishes and
sets `canaryInFlight` to `null` (`:143`). B's `compareAndExchange(null, mine)` then succeeds and B sends a second
`. IN NS` at once. The window is small (B must be between `:126` and `:128` across A's whole canary), but the rule
says one canary per 60 s. **Fix:** after winning the CAS, read the state again; if `needsCanary` is now false, complete
`mine` with the cached verdict, clear `canaryInFlight` and return it.

#### L2. A canary waiter that gives up starts a period, contrary to open point (4)

`CustomDns.kt:129-135` returns `CanaryVerdict(ok = false, TIMEOUT)` to a waiter after 3 s. `resolveChecked`
(`:91-94`) then applies `afterOdd` → `afterTransport`, which starts a period (or doubles the back-off) and sends the
query to the backup, while the real canary may still come back healthy. The note's "counts as a failed canary for its
own query only" is not what the code does. A waiter times out only when the canary overruns its 2 s by more than 1 s.
That can happen with DoT, whose `soTimeout` is set once before the handshake, so several handshake reads can each take
nearly the remaining time. It is rare, but it is a backup window opened without a failed canary (§7, "the backup serves
only while the primary is unhealthy"). **Fix:** a timed-out waiter leaves the state alone and answers its query as an
odd answer from a healthy server would (`servfailResponse`, or the NODATA for REFUSED). It is never the backup. Mark
the synthetic verdict, for example with a `null` return, so `afterOdd` isn't called. Correct open point (4) in the note.

#### L3. Three guards that no test pins

The mutations below survive:
- **the lists before the custom server** (M5: `custom.resolve` moved ahead of `classify` in `handlePacket`);
- **the DoH client's `ProtectedSocketFactory`** (M8: removed; OkHttp then uses the system factory, the DoH socket
  goes into our own VPN and loops). `NO_PROXY` is pinned no better;
- **the status field while the VPN is off** (M9: `live = null` dropped from `stopSelfCleanly`, so a stopped filter
  would keep reporting `dns_upstream`).

All three are the design's headline rules (§5, §5.2, §4.2), and each is a source-scan or small JVM test like the
project's existing ones (`VpnMeteredInvariantTest`, `CustomUpstreamPrefTest`). **Fix:**
- In `handlePacket`, `classify(` comes before the first `custom.resolve(`/`resolveProvider(`, and `blockedResponse`
  is on the `category != null` branch.
- `buildDohClient(dns, protect)`: build it in a JVM test and assert `proxy == Proxy.NO_PROXY`, `socketFactory is
  ProtectedSocketFactory`, `dns === the given`, `!followRedirects`, `cache == null`. Also assert that
  `createSocket()` calls `protect` and closes the socket + throws when `protect` returns false. Add a scan that no
  `newBuilder()` appears in `UpstreamTransport.kt`/`CustomDns.kt`.
- `stopSelfCleanly` clears `live` inside the synchronized block, and `dnsUpstreamReport()` reads only `live` (scan),
  or move `live` handling into a tiny testable holder.

#### L4. The bootstrap glue has no test

`CustomDnsTest` uses IP literals only (its doc says so), so these are untested end to end:
- `lookUp` (`CustomDns.kt:152-185`);
- `start()` looking both names up;
- the primary's cache miss going to the backup without a failure;
- a failed look-up of the primary's name starting a `bootstrap` period (and only when nothing is cached);
- the backup's miss dropping the query;
- `releaseProbe` from the glue.

`BootstrapResolverTest` covers the order and the cache in isolation. **Fix:** two or three `CustomDnsTest` cases with
a name for one target and `Dispatchers.Unconfined` (already the test's scope, so `lookUp` runs inline). H1's test
covers one of them.

### Nits

- **N1** `BootstrapDns.kt:29-38` `ipLiteral`: anything with a `:` goes to `InetAddress.getByName`. On Android, a
  string that isn't numeric (a malformed IPv6 from a bad policy) is then a *system* DNS look-up, into our own VPN, and
  on the main thread when it comes through `syncCustom` from `startVpn` (`NetworkOnMainThreadException`, swallowed by
  `runCatching`). The server's grammar makes this unreachable today, but the doc says "never a DNS look-up". Parse
  numerically only (`android.net.InetAddresses.parseNumericAddress` behind a seam, or check that the string has only
  hex digits, `:` and `.` before calling).
- **N2** `UpstreamTransport.kt:51, 211`: `BOOTSTRAP_BUDGET.connectMs` (1 s) is used only by DoT. A DoH bootstrap
  resolver (a backup given as `https://<ip>/…`) gets the client's 1.5 s connect. Either say so in §5.3 or set it per
  call.
- **N3** `CustomDns.kt:182-184`: in a period, each 30 s retry of a failing primary look-up applies
  `afterTransport(BOOTSTRAP)` with the current `gen`. This doubles the back-off and overwrites the reason
  independently of the probe schedule. It is harmless (capped, reset on a network change). A gen check against the
  probe, or skipping it while `backup` is already set, would keep "one step per back-off".
- **N4** Worst case of a failed-canary query: 2.5 s (primary) + 2 s (canary) + 2.5 s (backup) = 7 s, past the stub's
  first ~5 s try. It happens only on the first query of a period, and the stub's retry gets the backup. A sentence in
  §5.4 would be enough.

## The implementer's deviations and open points

| # | Deviation | Judgement |
|---|---|---|
| 1 | `connect` for a connect timeout and a closed/garbled DoT stream; `timeout` for a canary SERVFAIL / non-NOERROR | Agree. Within the contract's words, and "can't reach your server from here" is the right text for a home server away from home. |
| 2 | `releaseProbe` | Agree; without it `probing` sticks while the primary's name isn't looked up. Pinned purely in `UpstreamChooserTest`; the glue's use of it is untested (L4). |
| 3 | 30 s retry after a failed look-up with nothing cached | Agree, but it must also cover a stale entry (M1). |
| 4 | DoH client owned by `CustomDns`, rebuilt on any config change | Agree; simpler and stricter than "on a new `rev`". |
| 5 | Gen checks (`afterAnswer` ignores an old gen; the canary verdict kept across gens; `reset` keeps `sinceMs` while healthy) | Agree, all three. |
| 6 | Cloudflare/Quad9 keep today's timeouts; a garbled provider answer is now dropped | Agree. The stub would reject a garbled answer anyway; dropping it lets its retry run. REFUSED/SERVFAIL from the provider are still relayed (`DnsFilterEngine.resolveProvider`). |
| 7 | `cloudflare-dns.com` is always `1.1.1.1` | Agree (Cloudflare's own anycast address; the name is normalized lower-case by the server and OkHttp). |
| 8 | One IPv4 and one IPv6 kept | Agree. A better fallback on a dual-stack network than two of one family, still "at most two, IPv4 first". |
| 9 | `CustomDns` takes clock, log and transport | Agree. |

Open points:
- **(1) the reason words**: agree, as deviation 1.
- **(2) a config change while queries are in flight**: fine, and better than the note says. `evictAll()` closes only
  idle connections, and an in-use HTTP/2 connection finishes its exchanges, so in-flight queries normally complete.
- **(3) the bootstrap hole**: not acceptable as is. H1, with the fall-through as the fix.
- **(4) a canary waiter that gives up**: the code changes the state, not just that query. L2.

## Checked and fine

- **The lists first** (`KidVpnService.kt:211-222`): `classify` decides, a blocked name gets `blockedResponse` and the
  `BlockedEventLog` entry, and only an allowed name reaches `custom.resolve` or `resolveProvider`. The FCM/Spotify
  exemptions live in `classifyDomain` and are unchanged.
- **§5.4 transitions** (`UpstreamChooser.kt`):
  - `route`: Healthy → primary; in a period → backup until `skipUntilMs`, then one CAS-won `PROBE`.
  - `afterTransport`: the gen check, `60 s shl min(failures-1, 5)` capped at 15 min, `probing = false`, `sinceMs` kept
    within a period.
  - `afterAnswer` from the probe resets everything.
  - `afterOdd`: the canary decides, and a REFUSED canary gives `refused`.
  - `needsCanary`: 60 s.
  - `reset`: drops the canary and bumps `gen`.
  - The backup's outcomes never reach the state (`backup()` doesn't call `update`).
- **REFUSED from a healthy primary** → `blockedResponse(request)` (the same NODATA, now a top-level function shared
  with the block path). SERVFAIL and silence → `servfailResponse`. Neither goes to the backup.
- **DoH** (`UpstreamTransport.kt:207-249`):
  - a fresh `OkHttpClient.Builder()`, `Proxy.NO_PROXY`, `ProtectedSocketFactory`, `BootstrapDns` as `Dns`;
  - 1.5 s connect, 2.5 s call (per call `call.timeout()` for the canary and the bootstrap);
  - no redirects or cache, default cookie jar `NO_COOKIES`;
  - POST with both media types and ID 0, the response checked (200, type without parameters, ≤ 65535 bytes via
    `readNBytes(65536)`, parsable) and the ID restored;
  - the `CallMarks` listener splits the timeout at `requestBodyEnd`, and an `UnknownHostException` is `bootstrap`.
  - `readNBytes` is API 33, and minSdk is 34.
- **DoT** (`resolveDoT`):
  - at most two addresses, one deadline;
  - `protect` before connect, and a socket that isn't protected is closed;
  - a name gets SNI + `endpointIdentificationAlgorithm = "HTTPS"`; an IP gets no SNI + the default hostname verifier
    after the handshake;
  - the next address is tried only while nothing was written.
  - `resolveProvider` keeps Cloudflare/Quad9's addresses, names and (in total) timeouts.
- **Bootstrap**: the order, the CNAME chain's final name with the smallest TTL, the 5 min-24 h clamp, stale served
  while one refresh runs, kept on failure, one look-up per name (`lookingUp` under `@Synchronized`), both names at
  `start()`, the look-ups on the service's IO scope (never the main thread).
- **The pref**: written in `storeAcceptedPolicy`'s one `commit()` with the provider (the old generated `apply()`
  setter for the provider is gone too), removed when the accepted policy has none, untouched on a decode failure or a
  rejected policy (`storeAcceptedPolicy` runs only for `freshPolicy`). `setCustomUpstream` runs after the commit, and
  `loadFromDisk` reads it. The VPN service is in the app process, so `live` and the sync share memory.
- **Status**: `KidVpnService.dnsUpstreamReport()` = `live?.report()`, where `live` is set only with an established VPN
  and a config, and cleared in `stopSelfCleanly`. The words and the shape are the contract's (`UpstreamChooserTest`
  encodes `dns_upstream_status.json`). `dns_custom_v1` is in `STATUS_CAPABILITIES`.
- **Cleanup**: `stopSelfCleanly` clears `onCustomChanged`, unregisters the callback, sets `stopped` and closes the
  client under the same monitor as `syncCustom` (a late `syncCustom` from the sync thread is a no-op), then cancels
  the scope. Calling it twice (`onRevoke` + `onDestroy`) is harmless.
- **Network callback**: `registerBestMatchingNetworkCallback` (INTERNET + NOT_VPN) on the main looper, with
  `ACCESS_NETWORK_STATE` in the manifest. `lastNetwork` is touched only on that looper. Eviction runs on its own
  thread (closing TLS sockets may write).
- **Secrets**: the redacted `toString`s and the log scan (`CustomUpstreamPrefTest`, which catches M6).
  `PolicyGate` keeps only the exception class, which the fresh/cached decode logs then print. No underlying networks
  or lockdown (`VpnMeteredInvariantTest` unchanged and green).
- **Thread-safety**: one `AtomicReference<ChooserState>` with CAS loops (`route`, `update`), a `@Volatile`
  `custom`/`live`/`customUpstream`, and `BootstrapCache` synchronized. The only gaps are L1 and L2.
- `docs/testing/emulator.md` §6m: a usable emulator check (public DoH/DoT by name and IP, badssl for `tls`, Wi-Fi
  toggle, a DoH backup, Forget), and honest about what needs the device.

## Mutations

Each was applied alone, then `testDebugUnitTest --tests 'com.kidslauncher.mdm.server.*'` was run and the sources
reverted.

| # | Mutation | Result |
|---|---|---|
| M1 | `CustomDns.resolveChecked`: an `Odd` always goes to the backup (REFUSED/silence from a healthy primary routed to the backup) | killed: `CustomDnsTest` REFUSED-is-NODATA, silence-is-SERVFAIL |
| M2 | `afterTransport` without the `attemptGen` check (burst rule dropped) | killed: `UpstreamChooserTest` burst counts once |
| M3 | `BACKOFF_CAP_MS` 15 → 30 min | killed: `UpstreamChooserTest` back-off schedule |
| M4 | `needsCanary` always true (a canary per odd query) | killed: `UpstreamChooserTest` reuse 60 s, `CustomDnsTest` canary reused |
| M5 | lists skipped when a custom server is set (`custom.resolve` before `classify`, branch form) | killed only by `-PwarningsAsErrors` (an unreachable-branch warning), not by a test, so re-run as M5b |
| M5b | `classify(domain).takeIf { this.custom == null }` (the lists skipped under Custom, warning-free) | **survived** (L3) |
| M6 | `log("Look-up of $host gave nothing")` in `lookUp` | killed: `CustomUpstreamPrefTest` log scan |
| M7 | the pref written by the generated setter outside the commit | killed: `CustomUpstreamPrefTest` one writer |
| M8 | `.socketFactory(ProtectedSocketFactory(protect))` removed from `buildDohClient` (the system socket for DoH) | **survived** (L3) |
| M9 | `live = null` removed from `stopSelfCleanly` (the status reported while the VPN is off) | **survived** (L3) |
| M10 | a failing backup falls through to Cloudflare (a third tier) | killed: `CustomDnsTest` backup failure drops the query |
| M11 | TTL floor 5 min → 1 s | killed: `BootstrapResolverTest` clamp |
| M12 | `route` ignores `probing` (every query probes after the back-off) | killed: `UpstreamChooserTest` one probe per back-off |

Of 13, 9 were killed by a test, M5 only by the compiler flag (its warning-free form M5b survived), and M5b, M8 and M9 survived (L3).
