# QA 36 (design) - a custom DNS server for the Web Filter

Reviewer: design QA, 2026-10-18. Branch `design-36` at `ddbb2396` (on main `95d8f73c`). I read it against:
- `docs/design/36-custom-dns.md`; the root `CLAUDE.md` (contract files, both-directories rule); PLAN.md's backlog item
  "Our own DNS server in the Web Filter" (2026-10-15 and the 2026-10-18 clarification);
- `server/CLAUDE.md` ("Fail closed", "Connectivity and exposure", "Data model", the backups/snapshot paragraph, the
  Spotify/Storytel sealed-token notes), `server/DEPLOY.md` ("Tailscale Funnel");
- `launcher/CLAUDE.md` ("Architecture change in progress", "Fail closed", the always-on VPN notes).

I also read the code it touches:
- launcher: `DnsFilterEngine.kt` (`resolveUpstream`), `KidVpnService.kt` (`handlePacket` reads
  `LauncherPreferences.mdm().dnsUpstreamProvider()` per query), `MdmSyncWorker.kt` (where that pref is written: only
  after `judgeFresh` accepts), `PolicyGate.kt` (`decodeFresh`/`decodeCached`, `judgeFresh`, `LastEnforcedPlan`),
  `ServerJson.kt` (`ignoreUnknownKeys = true`, no `coerceInputValues`), `dto/PolicyResponse.kt`,
  `StatusCapabilities.kt`, `AppDownloads.kt` (the existing default-network callback), the manifest (no
  `networkSecurityConfig`; the VPN service in the app process; minSdk 34);
- server: `handlers/dns_filter.rs` (`set_upstream`), `handlers/device_api.rs` (`build_policy`, the
  `dns_upstream_provider` read), `handlers/backups.rs` (`strip_secrets_in`), the migration list (0077 is the highest).
- OkHttp 4.12 from memory of its source (`RealConnection.connectSocket`, `RouteSelector`, `callTimeout`).

No code was built or changed.

**Verdict: good shape, approve after a revision of the note.** One field, a pure parser, no server-side network
access, the old field kept for old launchers, the fallback only ever to an encrypted resolver behind the phone's own
lists - all right. What must change before step 1 is the fallback trigger (H1: as written, a healthy server's own
"no" for a name makes the phone ask Cloudflare and so *unblocks* it), the network-change signal (M1), the OkHttp
client's lifetime and proxy (M2), the concurrent-failure count (M3), and two classification decisions (M4, M5).

Counts: 1 High, 7 Medium, 9 Low, 5 Nits.

## High

### H1. The fallback rule unblocks what the parent's server blocks, and a child can open it at will

**What.** §5.4 makes `REFUSED` and a timeout *server* failures: the query goes to Cloudflare at once and the custom
server is skipped for 60 s (doubling). But `REFUSED` and silence are also how healthy servers *block*:
- AdGuard Home's blocking mode is selectable and "REFUSED" is one of the choices (AdGuard DNS private too); dnsdist
  setups commonly block with `RCodeAction(DNSRCode.REFUSED)` or `DropAction()` (= a timeout for us).
  With either, every blocked name is answered by Cloudflare - unblocked - and then everything else for 60 s+ too.
- A timeout is also what a healthy AdGuard Home produces for a name whose authoritative servers are slow or dead
  (its upstream timeout is 10 s, our `callTimeout` 3 s). A child who knows one such name can trip the 60 s window,
  again and again, and browse past AdGuard's filtering in it. `SERVFAIL` passing through is right for blocking, but
  then "never broken" isn't met for a name only AdGuard's upstream failed on - that is fine, see the rule below.
- §10 Q1 even tells the parent they may set every phone list to Off "if AdGuard should decide alone" - then each of
  these paths is fully unfiltered browsing.

AdGuard Home's `REFUSED` for a client not in "Allowed clients" (its `preBlockedResponse`: dropped on UDP, `REFUSED`
on TCP/DoT/DoH - from my memory of its source; verify when implementing) is the case the note is after, but one
`REFUSED` can't tell "you aren't allowed" from "this name is blocked".

**Why it matters.** The fallback is meant for "the server is down", never "the server said no". As written it turns
the parent's filter into a best-effort hint that a REFUSED-mode server, a drop-style dnsdist, or a curious child
defeats. That is the "filtering-weakening path" the project's fail-closed rule forbids.

**Fix to the note.** Replace §5.4's failure list with one principle and two classes:
> Cloudflare answers only while the custom server *as a whole* is unhealthy, never a name a healthy server didn't
> resolve.
- **Transport failures** (no bootstrap address, connect, TLS/verifier, HTTP non-200/3xx/wrong type, a DoT stream
  closed before an answer, a timeout *before the request was written*) → server failure: this query through
  Cloudflare, the period starts.
- **Answer-level outcomes** (`REFUSED`, `SERVFAIL`, a timeout *after* the request was written on a working
  connection) → the chooser runs a **canary**: `. IN NS` (every recursive resolver answers the root from its
  priming cache, no list blocks it), at most one in flight, its verdict cached 60 s. Canary fails or is itself
  `REFUSED` → server failure as above (this is AdGuard's unknown-id case). Canary answers → the server is healthy
  and the name's outcome stands: `REFUSED` → the phone's own NODATA (`buildBlockedResponse`, no retry storm),
  `SERVFAIL`/timeout → relayed `SERVFAIL`. Never Cloudflare for that name.
- Tests: `UpstreamChooserTest` gets "REFUSED with a healthy canary → blocked answer, no period", "REFUSED with a
  refused canary → Cloudflare + period", "timeout after write with a healthy canary → SERVFAIL, no period".
This costs one small query per odd answer and removes both the REFUSED-mode and the child-triggered paths. It also
makes §5.4's captive-portal paragraph moot (transport failures there, as today).

## Medium

### M1. `registerDefaultNetworkCallback` from the VPN app likely never sees the change it is meant for

**What.** §5.4 resets the back-off from a default-network callback. The launcher's own UID is inside its own VPN (it
isn't a disallowed app), so its *default network* is the VPN network, which stays the same across Wi-Fi ↔ mobile
(the underlying network is null = "follow the system default", and design 13 forbids setting it). Wi-Fi → LTE then
shows at most as `onCapabilitiesChanged`/`onLinkPropertiesChanged` on the VPN network, not `onAvailable`. And "leaving
the portal is a network change" is false: logging into a captive portal doesn't change the network, it gains
`NET_CAPABILITY_VALIDATED`.

**Why.** Without a working reset a captive portal or an hour in a basement can leave the phone on Cloudflare for up
to 15 minutes after the network is fine - the parent's filter off for no reason.

**Fix.** Register `registerBestMatchingNetworkCallback` (API 31; minSdk is 34) - or `registerNetworkCallback` - with
a request for `INTERNET` + `NOT_VPN`; reset when the best network's `Network` changes **or** it gains `VALIDATED`.
Name the events in the note; a unit-testable pure `networkChanged(old, new)` if anything. Unregister in
`stopSelfCleanly` (as written).

### M2. The OkHttp client: lifetime, proxy, pool and the bootstrap inside `callTimeout`

**What.** I checked the mechanism against OkHttp 4.12 and it works: `RealConnection.connectSocket` takes
`address.socketFactory.createSocket()` (the no-arg overload) for `DIRECT` and `HTTP` proxies, then connects it via
`Platform.connectSocket`, and TLS is layered on that socket (`sslSocketFactory.createSocket(raw, host, port, true)`),
so a `protect()` in `createSocket()` covers the whole HTTP/2 connection - the same unconnected-socket `protect` that
today's DoT does live. The pitfalls the note doesn't name:
1. **Proxy.** OkHttp's default `ProxySelector` follows a Wi-Fi's configured or PAC proxy. A SOCKS proxy is
   connected with `Socket(proxy)` - **not** through the factory, so unprotected, into our own VPN; an HTTP proxy
   would carry the DoH (and resolve the proxy's name through our `Dns`). Set `proxy(Proxy.NO_PROXY)`.
2. **Never derive it from another client.** The SSE/API clients carry tsnet's SOCKS proxy (`CommandListenerService`);
   a `newBuilder()` of one inherits it. Build this one from `OkHttpClient.Builder()`.
3. **Lifetime.** "One client per config, owned by the engine" (an `object`) holds a factory bound to one
   `KidVpnService` instance; always-on VPN restarts the service. Own the client by the service instance (or let the
   factory call a swappable `protect` function), `connectionPool.evictAll()` in `stopSelfCleanly`, rebuild on start.
4. **Pool on a network change.** Evict the pool on M1's reset; a pooled HTTP/2 connection from the old network
   otherwise costs one failed call (→ a needless period, before H1's canary) or a 3 s wait.
5. **Bootstrap inside the call.** `callTimeout` can't interrupt a custom `Dns.lookup` (there is no socket to close
   yet), and `resolveDoT(cloudflare)` waits 5 s + 5 s. A cold bootstrap blocks the first query well past the
   Android stub resolver's ~5 s retry. Bound the bootstrap lookup (2 s connect, 2 s read) and say that a cache miss
   answers that one query through Cloudflare while the lookup completes (not a period failure).

**Fix.** Add the five lines to §5.2/§5.3.

### M3. Concurrent failures inflate the back-off to 15 minutes on the first blip

**What.** `onFailure` does `failures + 1` per failed query. A page load has 10-20 queries in flight; one dead moment
fails all of them, `failures` becomes ~15 and `skipUntilMs` jumps to the 15-minute cap at once. The same burst repeats
at every expiry, so "at most one failed attempt per back-off" (§5.4 Battery) isn't true either.

**Why.** A single Wi-Fi hiccup puts the phone on Cloudflare for 15 minutes; the schedule the tests pin is not the one
the phone runs.

**Fix.** The state gets a `generation` (bumped on every change of `inUse`/`skipUntilMs`); an attempt carries the
generation it started under, and a failure counts only if that generation is still current (one CAS on the
`AtomicReference`). After a skip expires, the first query is the probe and the others go to Cloudflare until it
returns (`probing` flag). Test: "ten concurrent failures → one back-off step".

### M4. Private IP literals can never pass TLS; private host names are the case that works - say what happens away

**What.** §2 allows RFC 1918/ULA addresses because "they work with a certificate whose IP SAN the system trusts".
No public CA may issue a certificate for a private IP (CA/B Baseline Requirements), the launcher trusts only system
CAs (no network security config; a DO-installed CA lands in the user store, which apps targeting 24+ don't trust). So
`tls://192.168.1.10` is a value that is always on Cloudflare. What does work is a public *name* with a DNS-01
certificate that resolves (in public DNS - Cloudflare doesn't strip private answers) to a home address: on home Wi-Fi
it is used; on mobile data or a friend's Wi-Fi the connect fails (or another device owns that IP and TLS fails), and
the phone falls back per period and retries on each network change (M1).

**Fix.** Refuse RFC 1918, ULA and CGNAT literals - "A public certificate can't name a home-network address; use the
server's host name" - and add one sentence to §2/the ⓘ: a name that points at the home network works only at home;
elsewhere the phone uses Cloudflare and the PWA line says "can't reach your server" (word `connect` so it reads
naturally for "away from home").

### M5. Refusing `*.ts.net` blocks the setup DEPLOY.md recommends for families behind CGNAT

**What.** DEPLOY.md's "Tailscale Funnel (behind carrier-grade NAT)" gives the phone listener a public
`https://<node>.<tailnet>.ts.net:8443` URL. The same family's AdGuard Home on that Pi can be funneled on 443 or 10000
(`https://pi.tailXXXX.ts.net/dns-query`): a real public address with a Let's Encrypt certificate, resolvable through
Cloudflare while Funnel is on, reached from the protected socket over the internet. §2 refuses every `*.ts.net`.

**Why.** It turns away the one hosting path the project documents for families without a port forward.

**Fix.** Refuse only the tailnet *addresses* (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`) - those really are unreachable.
Allow `*.ts.net` names; without Funnel the bootstrap finds no public address and the phone reports `bootstrap`. The
ⓘ: "a tailnet name works only through Tailscale Funnel". DEPLOY.md gets a line (`tailscale funnel --bg --https=443
http://127.0.0.1:<AdGuard's DoH port>` and AdGuard Home's "allow unencrypted DoH" behind it, or `--tls-terminated-tcp`
for DoT) - verify the exact Funnel flags when writing it.

### M6. Where the phone keeps the custom config: say "a pref written with the provider", not "from the policy"

**What.** §5.1 says the engine "reads the cached config (`CustomUpstream?`, from the policy)". Today the provider is
its own pref (`dnsUpstreamProvider`), written in `MdmSyncWorker` only after `judgeFresh` accepts, read per query from
SharedPreferences' in-memory map. Decoding the cached policy blob per query would be far too slow on the packet path,
and a `Corrupt` cache would silently drop the custom server.

**Fix.** Specify: the accepted policy's `dns_upstream` is written next to `dnsUpstreamProvider` (same place, same
`commit()`), cleared when an accepted policy has `null`, never touched on a decode failure or a rejected policy;
`DnsFilterEngine` holds it parsed in a `@Volatile` (set by the sync, loaded in `loadFromDisk`, like `spotifyOn`). Not
in `LastEnforcedPlan` (the provider isn't either; the pref survives a corrupt cache on its own). Offline after a
reboot = the pref = the last accepted server - say it.

### M7. With Custom chosen, the fallback is unfiltered if the phone's lists are off

**What.** §10 Q1 invites the parent to set every phone list to Off; §1.7 falls back to plain Cloudflare (`1.1.1.1`).
Even after H1, a genuine outage - or a child on a network that blocks the server's IP or port (an own hotspot, a
school firewall) - is then unfiltered browsing. The note doesn't say so anywhere the parent reads.

**Fix.** Either (recommended, a two-line change) fall back to Cloudflare for Families (`1.1.1.3`,
`family.cloudflare-dns.com`, malware + adult) whenever Custom is chosen, or keep `1.1.1.1` and have the ⓘ and the
footer say "while a phone uses Cloudflare, only its own lists filter". Ask the user (question 1 below); drop the
"set every list to Off" advice from Q1 either way, or qualify it.

## Low

- **L1. DoH can carry the id in the host too.** AdGuard Home accepts a ClientID as the first label of the DoH server
  name (`https://abc123.dns.example/dns-query`), AdGuard DNS private likewise. §2's DoH hint shows the full host,
  and §5.3's "DoH keeps the id inside TLS" is true only for a path id. Fix: one hint rule for every name - `…` plus
  the last two labels when there are more than two; the ⓘ says "an id in the path stays inside TLS".
- **L2. Numeric and disguised addresses.** Say: a host whose last label is all digits is refused unless it parses as
  a full dotted-quad IPv4 (`1.1` is a valid LDH host to §2 but `InetAddress.getByName` on the phone reads it as
  `1.0.0.1`); no octal/hex/shorthand forms; IPv4-mapped/-compatible IPv6 (`::ffff:127.0.0.1`) is mapped before the
  range checks; a trailing dot is stripped; `%` (zone ids) refused in a host. Rows in `refuses`.
- **L3. Bare `host:port` and bare `host/`.** The table has no row for `dns.example:8853` (DoT with that port, fine)
  or `dns.example:443` (almost certainly a DoH server: refuse with "add https://") or `dns.example/` (DoH with
  `/dns-query`, or DoT?). Add the three rows.
- **L4. The DTO must never fail the whole policy.** `ServerJson` has no `coerceInputValues`, so a type mismatch in
  `dns_upstream` fails `decodeFresh` and the phone stops taking *any* policy. Say: every `DnsUpstream` field
  nullable with a default, `kind` a `String` (no enum), so a future kind is an "invalid → provider word" in
  `customUpstreamFrom`, never a decode failure.
- **L5. Redundant `host` + `url` for DoH.** The phone needs only `url` for DoH (OkHttp parses it) and `host`/`port`
  for DoT. Sending both for DoH adds the "host matches" check and a mismatch case for nothing. Wire: DoH
  `{kind, url, rev}`, DoT `{kind, host, port, rev}`. (Less code on both sides.)
- **L6. The bootstrap disk cache and 7-day staleness are more than needed.** A memory cache with the TTL clamp and
  "serve stale while one refresh runs" covers it; a reboot costs one Cloudflare look-up. Keep the disk file only if
  the user takes question 3 (networks that block port 853), where it is what makes DoH work across a reboot.
- **L7. Time budget vs the stub resolver.** Android's resolver re-sends after ~5 s. Custom DoT tries "the first
  address (2 s), then the next" plus a 3 s read; with A + AAAA that can pass 5 s before Cloudflare even starts. Cap
  the custom attempt at ~2.5 s in total (at most two addresses, IPv4 first) so a fallback answer lands inside the
  first try.
- **L8. Logs.** `MdmSyncWorker` logs `FreshDecode.Failed.error`, and a kotlinx `JsonDecodingException` message embeds
  an excerpt of the JSON input - with the URL if the failure is near `dns_upstream`. Logcat only (adb is off), but
  §7's "never logged" should either cover it (log the exception class only for policy decode errors) or say it is
  accepted. The same for any crash report: the engine catches everything, keep it so.
- **L9. Bootstrap answers.** Cloudflare's answer for `abc123.dns.example` may be a CNAME chain; take the A/AAAA
  records of the final name in the answer section, not only those owned by the queried name. And the DoH response's
  `Content-Type` compare is on the media type, ignoring parameters.

## Nits

- **N1.** ⓘ: "a bare name or IP means DNS-over-TLS on 853; for DNS-over-HTTPS type the https:// address" - parents
  will paste their AdGuard Home web UI's host name and expect DoH.
- **N2.** An unbracketed IPv6 is always the whole address (`2620:fe::fe:853` is a valid address, not a port); say a
  port needs brackets.
- **N3.** The root `CLAUDE.md`'s shared-files paragraph and the three workflows' path lists need
  `dns_upstream_status.json` (the note says the workflows; add the CLAUDE.md line to step 1).
- **N4.** §3's heading "0079 ..., or 0078": write it once as "the next free number on `main` when this merges
  (0078 today; 0079 if design 35 lands first)", the way server/CLAUDE.md's migration rule says.
- **N5.** §1.2 mentions "the hourly refresh" - there is no server-side refresh of this value; drop the words or name
  the blocklist refresh.

## Confirmed

- **Old launchers.** `ServerJson` has `ignoreUnknownKeys = true`; 0.26.0 decodes the policy, ignores `dns_upstream`
  and uses `dns_upstream_provider` = `"cloudflare"`. `judgeFresh` doesn't look at either field, so switching back
  (object → `null`) is accepted. A new launcher with an old server never sees the object. Either release order safe.
- **`rev` is worth its column**: it is what lets the footer say "Waiting for the phone" instead of showing the old
  server's state as the new one's, and it gives the phone an equality trigger for rebuilding the client. Compare by
  equality only (a restore can move it backwards) - add that word.
- **The status field** follows `time_status_report.json`'s pattern (testdata file byte-identical in the launcher's test
  resources, an encode test on the phone, a post-and-compare on the server, both path filters); capability
  `dns_custom_v1` added to `STATUS_CAPABILITIES`; the chooser lives in the app process with the sync (same process,
  checked in the manifest).
- **Bootstrap through Cloudflare DoT** is correct and battery-sane: pinned `1.1.1.1`, protected, no loop, bypasses
  `classifyDomain`, once per TTL. On the DoT first-label leak: resolving the id-less parent name instead is *not*
  valid in general (the server doesn't know which label is an id; the parent may not exist, may point elsewhere or
  be GeoDNS'd differently; AdGuard Home's id names rely on a wildcard record that the apex needn't share). The full
  name once per TTL to Cloudflare is far less exposure than the SNI on every connection to every on-path network.
  Keep it, with the note's DoH recommendation and L1's wording.
- **TLS checks.** System CAs only (no `networkSecurityConfig`); a DoT name keeps SNI + `endpointIdentificationAlgorithm
  = "HTTPS"`; an IP sends no SNI and is checked by Android's default hostname verifier, which matches IP SANs
  (Cloudflare's, Quad9's and Google's certificates carry theirs). OkHttp's verifier does the same for an IP URL.
- **The grammar against real servers**: AdGuard Home (`/dns-query`, `/dns-query/<id>`, `<id>.host` for DoT), AdGuard
  DNS, NextDNS (`https://dns.nextdns.io/<id>[/<device%20name>]`, `<id>.dns.nextdns.io`), Mullvad, Quad9, Cloudflare
  (`https://1.1.1.1/dns-query`), ControlD (`/p2`), dnsdist/Unbound (`/dns-query`), the RFC 8484 `{?dns}` template -
  all classify as intended (with L1-L3 added). Port 53/80 and `http://` refusals, user info and query refusals, the
  512/256 caps: right.
- **No SSRF**: the server only parses; the phone is the only client. The write-only field, the 400-in-place refusal
  without the value in any URL, `#server` on every redirect and form action (no jump to the top), the empty-keeps /
  Forget pattern: as the Tailscale key, right.
- **Secrecy, Q3: keep it in backups.** The precedent supports it: the TOTP 2FA secret, far more sensitive, stays
  unsealed in backups by decision (design 22 §3.2.8); the Spotify/Storytel tokens are sealed only because a key
  exists for them, and sealing this would make a restore elsewhere lose it exactly as stripping does. A DNS id
  unlocks nothing of ours. The backlog's "left out where possible" is answered by "not possible without losing the
  restore" - write that sentence in §7 so the next reader sees the decision, and pin it with the `strip_secrets_in`
  test as planned. The event records the kind only; the server never logs the value; the launcher logs kind and
  exception class only (plus L8).
- **Downgrade analysis** (§7): a TLS-breaking MITM can only push the phone to Cloudflare DoT, never to plaintext or
  unfiltered; the FCM/Spotify exemptions are block decisions a forged answer can't touch.
- **Wire in one commit** (§9 step 1: `server/` + the launcher's DTO/status fields + `PolicyResponseCompatTest` + the
  contract file + path filters) and the tests listed on both sides match the project's rules; the PWA's "update
  Vibb One" line keys on the capability, as other features do.
- **Over-building**: little. Global only, no per-phone override, no DoQ/DNSCrypt, no server probing - good. The two
  things to trim are L5 (duplicate host) and L6 (the disk cache).

## Questions for the user (the note should add or change these)

1. **Fallback when Custom is chosen** (M7): plain Cloudflare `1.1.1.1` (today's requirement), or Cloudflare for
   Families `1.1.1.3` (malware + adult blocked) so a phone whose own lists are off is never unfiltered during an
   outage? Recommended: Families for Custom; plain Cloudflare stays for the Cloudflare choice.
2. **A REFUSED answer from a healthy server** (H1): treat it as "blocked" (the phone's NODATA), never asked of
   Cloudflare? Recommended: yes, with the canary deciding "healthy".
3. **Networks that block port 853** (school/work Wi-Fi - today the whole filter breaks there, before this design):
   now that the phone has a DoH client, should the fallback and the bootstrap use Cloudflare DoH
   (`https://1.1.1.1/dns-query`, 443) when 853 fails? Recommended: yes, as a separate small step after this one, or
   park it in the backlog - but decide, because it changes L6.
4. **`*.ts.net` through Funnel** (M5): allow it and document it in DEPLOY.md? Recommended: yes.

## Revision 2026-10-18 (owner's decisions)

The note was revised on branch `design-36b` after the owner answered the questions above. Versus the QA'd version:
- **Q1/M7 changed**: no hard-coded Cloudflare for Families. Under Custom the parent gives a second field, "Backup
  server", with §2's grammar, refusals and write-only handling; required, prefilled `1.1.1.3`, not equal to the
  primary. It is a column in the same migration (0079), a nested `backup` target in `dns_upstream`, and §5.4's
  fallback target; a failing backup drops the query (no third tier). The status word is `backup`.
- **Bootstrap**: through the backup when it is an IP, else the primary when it is an IP, else Cloudflare `1.1.1.1`
  DoT; both names looked up when a config loads, a failed refresh keeps the cached entry.
- **Q4/M4/M5 changed**: any address is allowed (private, tailnet, `*.ts.net`, single-label names); only loopback,
  unspecified, multicast (and the forms that can't be dialled: `255.255.255.255`, unzoned IPv6 link-local) are
  refused. The phone reaches the server directly over its own network, never through tsnet; a home server works on
  the home Wi-Fi, the backup elsewhere.
- Q2, Q3 (step 3, each provider's DoH on 443), Q5-Q7: as recommended.
- **H1 still holds**: the canary judges only the primary, and the backup is used only on a primary transport failure
  or a failed canary, never for a name a healthy primary refused or timed out on. Who chose the backup doesn't
  change that rule; a parent-chosen backup only decides how much the backup itself filters.
