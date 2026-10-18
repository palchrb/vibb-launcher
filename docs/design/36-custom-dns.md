# 36 - A custom DNS server for the Web Filter (DoH/DoT, AdGuard Home's client id included)

Architect, 2026-10-18. Branch `design-36` from `main` (`95d8f73c`). A buildable spec: three steps (§9). Revised the
same day after the design QA (`docs/design/qa-36-design.md`), and again after the owner's answers to §10 (branch
`design-36b`): the parent gives the backup server, and any address is allowed.

Sources: PLAN.md "Our own DNS server in the Web Filter" (2026-10-15 and the 2026-10-18 clarification: **any** DNS
server, the server works out the kind); server `handlers/dns_filter.rs` `set_upstream`, `templates/dns_filter.html`,
`device_api.rs` `build_policy`, `settings.rs` `key_hint`, `backups.rs` `strip_secrets_in`, DEPLOY.md "Tailscale
Funnel"; launcher `DnsFilterEngine.kt` (`resolveUpstream`: a fresh `protect()`ed TLS socket per query to a pinned IP
on 853, 5 s timeouts, `null` = dropped), `KidVpnService.kt` (only `192.0.2.2/32` routed; every app's resolver, ours
included, points at it), `MdmSyncWorker.kt`, `ServerJson` (`ignoreUnknownKeys`, no `coerceInputValues`, defaults not
encoded), OkHttp 4.12.0, dnsjava 3.6.5.

## 0. Goal and non-goals

**Today** Settings › Web Filter › DNS Server is Cloudflare or Quad9 (`dns_filter_settings.upstream`, global). The
policy's `dns_upstream_provider` names one; the phone forwards every query its blocklists let through to
`1.1.1.1`/`cloudflare-dns.com` or `9.9.9.9`/`dns.quad9.net` over DoT.

**Goal**: a third choice, **Custom server**, with two fields that take whatever address the parent has - a DoH URL
(an AdGuard Home client id in the path or the first label, or none), a DoT host name, or an IP for DoT: **Your
server** and **Backup server** (prefilled `1.1.1.3`, Cloudflare for Families). The server classifies and validates
both without any network access. The phone forwards to the first, checks its certificate by the system CAs, and
while that server as a whole is unhealthy answers through the backup - never unfiltered plaintext. A name the healthy
server refuses stays refused. The PWA shows, per phone, which is in use.

**Non-goals**:
- **Plain port-53 DNS.** Unencrypted DNS over a mobile network or someone else's Wi-Fi is what this project avoids.
- DoQ, DoH3, DNSCrypt, DNS stamps (`sdns://`). Refused with a reason (§2).
- **Fail-closed under Custom** ("no look-ups while my server is down"): the backup is required instead.
- A third tier: a failing backup drops the query, as a failing Cloudflare does today.
- A per-phone custom server (later, if wanted). Private DNS: stays locked to Opportunistic; the custom server applies
  only while the Web filter (the VPN) is on. Replacing the phone's blocklists: two layers, the phone's lists block
  first.

## 1. Decisions

1. Two fields, one grammar, stored normalized, re-parsed by one pure function wherever read (`src/dns_server.rs`).
2. The server never resolves or contacts either address. No SSRF surface.
3. Both values are secrets (either may hold a client id): write-only, never logged, events carry their kinds only.
   They **stay in backups** (§7).
4. Any address is allowed - public, home network, tailnet, `*.ts.net` - except what can never be a server from the
   phone: loopback, unspecified, multicast (§2). The phone reaches it directly over its current network, **never
   through the launcher's tsnet** (which carries only the API); where it can't, the backup answers.
5. With Custom chosen, `dns_upstream_provider` is `"cloudflare"` (old launchers); a new `dns_upstream` object
   carries both servers.
6. **The backup answers only while your server as a whole is unhealthy, never a name a healthy server didn't
   resolve** (§5.4): transport failures start a period; an odd answer is judged by a canary query.
7. The backup is the parent's: required under Custom, prefilled `1.1.1.3`, not equal to the primary. Cloudflare and
   Quad9 are unchanged (no backup).
8. Bootstrap through the backup if it is an IP, else through the primary if it is an IP, else Cloudflare `1.1.1.1`
   DoT; cached in memory by TTL; no server-resolved IPs (§5.3).
9. DoH over a dedicated OkHttp client, protected sockets, our own `Dns`, no proxy (§5.2); DoT generalized. Both
   serve either target.

## 2. The fields: grammar and classification

Both fields, the same rules. Trim; strip one trailing dot of the host. Refuse an empty value (§6 says when that is
"keep"), more than 512 characters, whitespace or control characters, non-ASCII ("Type the name in its xn-- form").
Then by scheme (case-insensitive):

| Typed | Kind | Host | Port | Path |
|---|---|---|---|---|
| `https://dns.adguard-dns.com/dns-query` | DoH | `dns.adguard-dns.com` | 443 | `/dns-query` |
| `https://dns.example/dns-query/abc123` | DoH | `dns.example` | 443 | `/dns-query/abc123` |
| `https://abc123.dns.example/dns-query` | DoH | `abc123.dns.example` | 443 | `/dns-query` |
| `https://dns.example` or `https://dns.example/` | DoH | `dns.example` | 443 | `/dns-query` |
| `https://dns.example:8443/dns-query/abc` | DoH | `dns.example` | 8443 | `/dns-query/abc` |
| `https://dns.example/dns-query{?dns}` | DoH | (RFC 8484 template stripped) | 443 | `/dns-query` |
| `https://[2606:4700:4700::1111]/dns-query`, `https://192.168.1.10/dns-query` | DoH | the IP | 443 | `/dns-query` |
| `dns.example/dns-query/abc`, `dns.example/` (no scheme, a path) | DoH | `dns.example` | 443 | as typed, `/` → `/dns-query` |
| `dns.quad9.net`, `abc123.dns.example`, `pi.tail1234.ts.net`, `pi.home.arpa` | DoT | as typed | 853 | - |
| `dns.example:8853`, `tls://dns.example:8853` | DoT | `dns.example` | 8853 | - |
| `1.1.1.3`, `192.168.1.10`, `100.101.102.103`, `2620:fe::fe`, `[2620:fe::fe]:853` | DoT | the IP | 853 | - |

A client id is never parsed out: in a DoH path or a first label it is just part of the address. An unbracketed IPv6
is always the whole address (`2620:fe::fe:853` is an address); a port needs brackets.

**Refused**, nothing written, the reason under that field:
- `http://` - "Not encrypted: use the https:// address"; any other scheme but `https://`/`tls://` (`quic://`,
  `h3://`, `sdns://`, ...) - "Use the https:// (DNS-over-HTTPS) or tls:// (DNS-over-TLS) address"; `host:443` without
  a scheme - "That's a DNS-over-HTTPS port: add https://".
- user info - "No user name or password in the address"; a query or fragment (but a trailing `{?dns}`) - "Remove
  what follows ? or #"; a path on `tls://` - "A DNS-over-TLS address has no path".
- port 0, above 65535, 53 or 80 - "Port 53 and 80 aren't encrypted". Any other port is allowed for both kinds.
- a path character outside `A-Z a-z 0-9 - . _ ~ /` and `%XX`, or a path over 256 characters.
- host not LDH labels (1-63, total 253) - "Not a server's address".
- a last label of only digits unless the whole host is a dotted-quad IPv4 (`1.1` would read as `1.0.0.1` on the
  phone); octal, hex and shorthand IPv4; `%` (zone ids) - "Not a server's address".
- what can never be a server from the phone, checked after mapping IPv4-mapped/-compatible IPv6
  (`::ffff:127.0.0.1`) to IPv4 - "That address can't be a DNS server": `localhost` and `*.localhost`; loopback
  (`127/8`, `::1`); unspecified (`0.0.0.0/8`, `::`); multicast (`224/4`, `ff00::/8`) and `255.255.255.255`; IPv6
  link-local (`fe80::/10`, unusable without the zone id refused above).

Everything else is allowed: private (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`), tailnet (`100.64/10`,
`fd7a:115c:a1e0::/48`), single-label and `*.local`/`*.home.arpa`/`*.internal`/`*.ts.net` names. Whether the phone can
use it is decided where it is (§5): it must reach the address over its current network and accept the certificate
(system CAs; an IP only if the certificate names that IP, which public resolvers' do and a home server's can't).
Where it can't, the backup answers and the PWA says why (§6).

`parse_dns_server(&str) -> Result<DnsServer, Refusal>`; `DnsServer { kind, host, port, path }`,
`DnsServer::normalized()` (`https://host[:port]/path`, `tls://host[:port]`; default ports left out, IPv6 bracketed,
host lowercased) - stored as is; equality of two servers is equality of `normalized()`. `DnsServer::hint()`, one
rule for every name: `…` plus the last two labels when there are more than two (the first label may be an id), never
the path; an IP as is; then the kind (`…dns.example · DNS-over-HTTPS`). `Refusal` is an enum, worded by the template.

## 3. Data model

Migration `0079_custom_dns.sql` (design 35 takes 0078 as one file for both of its steps, decided 2026-10-18 after
its design QA; if 36 ships first, 0078 stays reserved for 35 and this is still 0079); added to the
migrations-in-order test.

```sql
ALTER TABLE dns_filter_settings ADD COLUMN custom_server TEXT NOT NULL DEFAULT '';
ALTER TABLE dns_filter_settings ADD COLUMN custom_backup TEXT NOT NULL DEFAULT '';
ALTER TABLE dns_filter_settings ADD COLUMN custom_rev INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_status ADD COLUMN dns_upstream_json TEXT;
```

- `upstream` may now be `'custom'`, set only with a parsable `custom_server` and a parsable, different
  `custom_backup`; a reader that finds `'custom'` without both (empty, no longer parsable, or equal) treats it as
  `'cloudflare'` (the PWA says so, §6).
- `custom_rev` goes up by one on every save that changes either value and on Forget; the phone echoes it (§4.2).
  Compared by equality only (a restore can move it backwards).
- Choosing Cloudflare or Quad9 keeps both values (switching back needs no retyping); "Forget" clears both.

## 4. Wire

### 4.1 Policy (`GET /api/devices/policy`)

```json
"dns_upstream_provider": "cloudflare",
"dns_upstream": {"kind": "doh", "url": "https://dns.example/dns-query/abc123", "rev": 4,
                 "backup": {"kind": "dot", "host": "1.1.1.3", "port": 853}}
```
A target is DoH `{kind, url}` or DoT `{kind, host, port}`; the primary adds `rev` and `backup` (a target).
`dns_upstream` is `null` unless `upstream = 'custom'` with both values valid (§3); then `dns_upstream_provider` is
`"cloudflare"`. Always present (null or object) in the snapshot. ≤ ~1200 bytes.

The old launcher ignores the unknown key and uses Cloudflare. The new DTO never fails the policy: every field of
`DnsUpstream` and of its `backup` (`DnsTarget`) nullable with a default, `kind` a `String` (no enum).
`customUpstreamFrom(dto)` checks both targets again (kind `doh` with a `url` starting `https://` that OkHttp parses,
or kind `dot` with a non-empty host and port 1-65535; a `rev`; a backup present); anything invalid in either → the
provider word.

### 4.2 Status report (`POST /api/devices/status`)

Capability `dns_custom_v1` (in `STATUS_CAPABILITIES`). New field, only while the VPN runs and Custom is configured:
```json
"dns_upstream": {"rev": 4, "in_use": "custom", "since_ms": 1792312345000, "reason": null}
```
`in_use`: `custom` | `backup`; `reason` (with `backup` only): `bootstrap` | `connect` | `tls` | `http` | `refused` |
`timeout`. Never an address. The server's `sanitize_dns_upstream` keeps known words, an integer `rev`, a plausible
`since_ms`, and stores `device_status.dns_upstream_json`. Pinned by `server/testdata/dns_upstream_status.json`,
byte-identical in `launcher/app/src/test/resources/` (like `time_status_report.json`).

## 5. The phone

Every connection to either server is a `protect()`ed socket on the phone's current network (mobile data or Wi-Fi),
never through the launcher's tsnet, which carries only the API. A home-network server is therefore used on the home
Wi-Fi, and the backup answers everywhere else through §5.4's period and probes - the intended behaviour, not an
error. A tailnet address or name is reachable only where the network itself routes it (e.g. through Funnel).

### 5.1 Config and shape

`MdmSyncWorker` writes the accepted policy's `dns_upstream` (both targets) next to `dnsUpstreamProvider` (same
`commit()`), clears it when an accepted policy has `null`, never touches it on a decode failure or a rejected policy;
not in `LastEnforcedPlan`. `DnsFilterEngine` holds it parsed in a `@Volatile` `CustomUpstream?` (`primary`, `backup`,
`rev`), set by the sync and loaded in `loadFromDisk` (like `spotifyOn`). Offline after a reboot = the last accepted
servers.

`resolveUpstream(query, protect)`: no custom → today's DoT to the provider; custom → the chooser's route (§5.4) to the
primary or the backup. One `resolveTarget(target, query)` serves both: DoH (§5.2) or DoT (§5.3) by the target's kind.
The backup's answer is relayed as is; a transport failure to the backup drops the query (`null`, as today). Blocking,
`BlockedEventLog` (the phone's own blocks only), the FCM/Spotify exemptions and the NODATA answer don't change. Logs
carry the kind and exception class only, never host, URL or `e.message` (OkHttp's carry the URL); `MdmSyncWorker`
logs a policy decode failure by class only (a `JsonDecodingException` quotes the input).

### 5.2 DoH

One `OkHttpClient` for both targets, owned by the `KidVpnService` instance (built in `startVpn` and on a new `rev`;
`connectionPool.evictAll()` in `stopSelfCleanly`; always-on VPN restarts the service):
- Built from `OkHttpClient.Builder()`, **never** `newBuilder()` of another client (the SSE/API clients carry tsnet's
  SOCKS proxy).
- `proxy(Proxy.NO_PROXY)`: a Wi-Fi's SOCKS proxy would connect a socket outside the factory (unprotected, into our
  VPN); an HTTP proxy would carry the DoH.
- `socketFactory(ProtectedSocketFactory(service::protect))`: `createSocket()` returns `Socket().also { protect(it) }`
  (OkHttp 4.12 uses the no-arg overload for a direct route and layers TLS on it, so the whole HTTP/2 connection is
  protected); the other overloads create, protect, then connect.
- `dns(BootstrapDns)` (§5.3), never the system resolver (the launcher's look-ups go to `192.0.2.2`, back into us).
- `connectTimeout(1.5 s)`, `callTimeout(2.5 s)`, HTTP/2 via ALPN, the default pool; evicted on every network reset
  (§5.4). No cache, `followRedirects(false)` (a 3xx is `http`), no cookies.
- An `EventListener` per call marks "request written" (`requestBodyEnd`), which splits a timeout into transport
  (before) and answer-level (after).
- Request: POST `url`, `Content-Type` and `Accept: application/dns-message`, the query with ID 0 (RFC 8484 §4.1).
  Response: 200, media type `application/dns-message` (parameters ignored), ≤ 65535 bytes, parsable by dnsjava; the
  original ID put back before relay. Anything else → `http`.
- TLS: the system CAs only (no network security config) and OkHttp's verifier (IP SANs for an IP URL).

### 5.3 Bootstrap and DoT

`BootstrapDns.lookup(host)`: an IP literal returns itself. A name (of the primary or the backup): A and AAAA queries
(dnsjava), never through `classifyDomain` or the chooser, with their own bound (1 s connect, 1 s read), sent to the
**bootstrap resolver**, fixed per config:
1. the backup, when it is an IP literal (DoT to that IP, or DoH to its IP-literal URL); else
2. the primary, when it is an IP literal; else
3. Cloudflare `1.1.1.1` DoT (`cloudflare-dns.com`), the only case a look-up leaves for anyone else.

When the bootstrap resolver fails at the transport level (silence after the request included), the look-up (only the
look-up, never a query) falls through to the next in this order, with the same budget per step; a step that failed
for A is skipped for AAAA, and an NXDOMAIN or an empty answer is an answer, not a failure.

The A/AAAA records of the final name of a CNAME chain in the answer are taken. A memory cache, TTL clamped to 5 min-
24 h; an expired entry is still served while one refresh runs, and kept when the refresh fails. Both names are looked
up when a config loads, so the backup's address is in hand before it is needed. A cache miss for the primary answers
that query through the backup while the look-up completes in the background - not a server failure; nothing at all
after the look-up → `bootstrap`. A cache miss for the backup drops that query (no third tier). (A disk cache comes
only with step 3, where it keeps DoH working across a reboot on a network that blocks 853.)

A DoT id in the first label reaches the bootstrap resolver in this look-up, once per TTL (it is in the clear in SNI
on every connection anyway); an id in a DoH path stays inside TLS. The ⓘ recommends a path id.

DoT to either target is today's `resolveUpstream` generalized to `resolveDoT(addresses, host, port)`: at most two
addresses, IPv4 first, within one 2.5 s budget for connect + handshake + answer, so a backup answer lands inside the
stub resolver's first ~5 s try. A name: SNI and endpoint identification on it, as today. An IP: no SNI (RFC 6066),
and after the handshake `HttpsURLConnection.getDefaultHostnameVerifier().verify(ip, session)` or `tls`. "Request
written" = the length-prefixed query flushed. A fresh connection per query, as today.

### 5.4 The backup rule (`UpstreamChooser`, pure)

Outcomes of one attempt at the primary:
- `Answer` - a DNS answer other than REFUSED/SERVFAIL (NXDOMAIN and AdGuard's `0.0.0.0` included) → relayed.
- `Transport(reason)` - no bootstrap address, connect, TLS/verifier, HTTP non-200/3xx/wrong type, a DoT stream
  closed before an answer, a timeout before the request was written.
- `Odd(kind)` - REFUSED, SERVFAIL, a timeout after the request was written.

State: `{gen, mode: Healthy | Backup, failures, skipUntilMs, probing, sinceMs, reason, canary: (atMs, ok)?}` in one
`AtomicReference`, changed by CAS. Pure functions:
- `route(s, now)`: `Healthy` → `Primary`. `Backup` with `now < skipUntilMs` or `probing` → `Backup`. Else
  `Probe` (this query tries the primary; `probing = true`, the rest go to the backup until it returns).
- `afterTransport(s, attemptGen, now, reason)`: if `attemptGen != s.gen` → `s` unchanged (a burst counts once). Else
  `mode = Backup`, `failures + 1`, `skipUntilMs = now + min(60 s · 2^(failures-1), 15 min)`, `probing = false`,
  `gen + 1`. The query itself goes to the backup.
- `afterAnswer(s, attemptGen, now)`: from `Backup` (the probe) → `Healthy`, `failures = 0`, `gen + 1`.
- `afterOdd(s, attemptGen, now, kind, canaryOk)`: the canary decides. `canaryOk == false` → as `afterTransport`
  (reason `refused` when the canary was REFUSED - AdGuard Home's answer to an id not in "Allowed clients" - else its
  transport reason); the query goes to the backup. `canaryOk == true` → as `afterAnswer`, and the name's own
  outcome stands: REFUSED → the phone's NODATA (`buildBlockedResponse`), SERVFAIL or the timeout → a relayed
  `SERVFAIL`. **Never the backup for that name.**
- `needsCanary(s, now)`: no verdict younger than 60 s. The canary is `. IN NS` to the primary (every resolver
  answers the root from its priming cache; no list blocks it), 2 s budget; ok = a NOERROR answer. At most one in
  flight (a shared `CompletableDeferred` in the glue); its verdict cached 60 s in the state. A query waiting for
  another's canary that gives up leaves the state alone and answers as from a healthy server.
- One deadline per query, ~4.8 s (inside the stub's first ~5 s try): the primary at most 2.5 s, the canary what is
  left above the backup's floor (1-2 s), the backup the rest (1.3-2.5 s).
- `reset(s)` - a network reset or a new `rev`: `Healthy`, `failures = 0`, `skipUntilMs = 0`, `probing = false`,
  `canary = null`, `gen + 1`; the glue evicts the OkHttp pool (a new `rev` rebuilds the client).

The chooser judges only the primary; the backup's outcomes never change its state.

Network reset: `KidVpnService` registers `registerBestMatchingNetworkCallback` for `INTERNET` + `NOT_VPN` (its own
default network is the VPN, which never changes). Pure `networkChanged(old, new)`: the best `Network` changed, or it
gained `VALIDATED` (a captive portal logged in). Unregistered in `stopSelfCleanly`. Coming home onto the Wi-Fi of a
home-network server is such a reset, so the primary is tried at once.

Battery: one probe per back-off at most, one canary per 60 s at most. Captive portal: transport failures to both
servers, the query dropped as today; the login gains `VALIDATED` → reset. The status (§4.2) is
`mode`/`sinceMs`/`reason`, reported with the next regular status report.

## 6. The PWA (Settings › Web Filter, `#server`)

Design 31's shapes. The group:
1. The inline picker "Look-ups go to": Cloudflare / Quad9 / Custom server (`POST /dns/upstream`, autosave, back to
   `/dns#server`). Custom with no valid stored pair (§3): 400, the page rendered in place with the row below open and
   "Type your server's address first" under the first field; nothing written.
2. A row "Custom Server" that opens (`<details>`, open when Custom is chosen and nothing is stored, or on a refusal).
   One form, `POST /dns/upstream/custom` (full auth, `dns_filter::set_custom`), two fields, Save, and with values
   stored a "Forget Custom Server" checkbox (the Tailscale key's pattern):
   - **Your server**: the hint (`Set (…dns.example · DNS-over-HTTPS).` / `Not set.`), an empty input, placeholder
     `https://… or a host name`.
   - **Backup server**: the hint, and an input that is empty when a backup is stored and prefilled `1.1.1.3` when
     none is (our public default, the only value ever rendered into either input); below it "Answers while your
     server can't be reached. 1.1.1.3 is Cloudflare for Families."; while both stored values are names, also "Give
     the backup as an IP address and no look-up ever leaves for anyone else."
   - Both inputs `type="text"`, `autocomplete="off"`, `autocapitalize="none"`, `spellcheck="false"`, never filled from
     storage (write-only; text, not password, so the prefill shows and no password manager offers to keep an id).

   On Save, each field: typed → `parse_dns_server`; empty → the stored value. Then, all or nothing:
   - a refusal → under that field; no primary at all → "Type your server's address first"; no backup at all (the
     prefill cleared, nothing stored) → "Custom needs a backup server: 1.1.1.3 is Cloudflare for Families"; equal
     `normalized()` → under the backup, "The backup must be another server than yours". **400, rendered in place**
     (form action `…#server`), the typed values back in their inputs (body only, never a URL; `Cache-Control:
     no-store`), `aria-invalid` and the reason under the field (`aria-describedby`); nothing written.
   - ok and something typed → store both `normalized()`, `upstream = 'custom'`, `custom_rev + 1` if either changed,
     nudge all phones, 303 `/dns#server`. Typing it means using it.
   - empty and Forget → clear both, `custom_rev + 1`; `upstream = 'cloudflare'` if it was custom; nudge; 303.
   - both empty without Forget, a backup stored → nothing changes, 303.
3. The footer, while Custom is chosen, one line per phone from its last report:
   - "Anna · Your server in use"
   - "Ola · Backup server since 10:42 - your server's certificate wasn't accepted" (one wording per `reason`;
     `connect`: "can't reach your server from where the phone is", which reads right for a home server while the
     child is out; `refused`: "your server refused this phone - check its allowed clients")
   - "Per · Cloudflare - update Vibb One on this phone to use your server" (no `dns_custom_v1`)
   - "Kari · Waiting for the phone" (no report with the current `rev`), "Web filter off" (VPN off).

   A stored pair that is no longer valid: "The saved addresses aren't accepted any more: type them again". The ⓘ: a
   bare name or IP is DNS-over-TLS on 853 (for DoH type the https:// address); the phone checks the certificate (an
   IP works only if the certificate names it); the phone reaches your server directly over its mobile data or Wi-Fi,
   not through Tailscale, so a home-network server works on the home Wi-Fi and the backup answers elsewhere; a path
   id stays inside TLS; the phone's lists still block first.

Security event `dns_server_changed` on every change of the three, detail
`{"server":"custom","kind":"doh","backup_kind":"dot"}` - never a host or path. The settings hub's Web Filter row is
unchanged.

## 7. Security

- **SSRF**: none - the server only parses (§1.2). The phone is the only client of either address.
- **Any address**: the phone, not the server, connects, from its own network position; allowing private and tailnet
  addresses exposes nothing new (the child's phone can already open any address on its Wi-Fi). Only addresses that
  can never be a server are refused.
- **The ids as secrets**: both values write-only, hinted (no first label, no path), out of logs, events, URLs and
  redirects; in the database, the policy to enrolled phones (bearer-authenticated, TLS) and backups. The backlog's
  "left out of backups where possible" is answered by "not possible without losing the restore": stripping or sealing
  them makes a restore lose them, as the TOTP secret's precedent (design 22 §3.2.8) decided; they unlock nothing of
  ours. Pinned by a `strip_secrets_in` test.
- **Filtering never weakens**: the backup serves only while the primary is unhealthy (transport, or a failed canary);
  a healthy server's REFUSED/SERVFAIL/silence stays a block or a SERVFAIL. A child can't open a window with a slow
  name: that is an `Odd` with a healthy canary. How much the backup filters is the parent's choice; the prefill is
  Cloudflare for Families, and the phone's lists block first either way.
- **Downgrade**: a TLS-breaking MITM, or a network that blocks the primary, can only push the phone to the backup
  over TLS (encrypted, the parent's choice).
- **Servers the parent chose** see every allowed query sent to them and can answer anything (as Cloudflare can
  today); the bootstrap resolver sees the servers' names only - Cloudflare also when the parent's IP servers are
  unreachable for a look-up (§5.3's fall-through). The FCM/Spotify exemptions are block decisions a forged
  answer can't touch.
- **Shape and size**: responses ≤ 65535 bytes, parsed, ID restored, no redirects; each value ≤ 512.

## 8. Tests

Server:
- `dns_server::tests::classifies` (every row of §2's table, incl. private, tailnet and `*.ts.net`/`*.home.arpa`) and
  `refuses` (every refusal incl. `host:443`, `1.1`, octal/hex, `%`, `localhost`, `127.0.0.1`, `::ffff:127.0.0.1`,
  `0.0.0.0`, `::`, `224.0.0.1`, `ff02::1`, `fe80::1`); `normalized_parses_to_itself`;
  `hint_hides_the_path_and_the_first_label` (DoH and DoT).
- `tests::device_api`: `policy_json_keys_snapshot` with `dns_upstream` and its `backup`; `custom_server_in_the_policy`
  (provider `cloudflare`, DoH `{kind,url,rev,backup}`, DoT `{kind,host,port,rev,backup}`, backup DoH and DoT);
  `custom_without_a_valid_pair_is_cloudflare` (empty, unparsable or equal backup).
- `tests::settings_pages`: save → 303 `/dns#server`, both hints shown, never a path or a first label; the backup
  input prefilled `1.1.1.3` with none stored and empty with one stored, the primary input always empty; each refusal
  in either field → 400, the reason under that field, nothing written; the prefill cleared with none stored → 400
  (backup required); equal values (`9.9.9.9` and `tls://9.9.9.9:853`) → 400; a backup-only change with a primary
  stored; Custom picked with nothing stored → 400; Forget clears both; both empty keeps; the "give the backup as an
  IP" line only while both are names; the form in `check_forms`; the event detail holds kinds only.
- `dns_upstream_status_contract` (the testdata file posted, both copies compared), `sanitize_dns_upstream`, the
  footer's lines (in use, backup with each reason, old launcher, stale `rev`, filter off).
- `backups`: `strip_secrets_in` keeps `custom_server` and `custom_backup`. The migrations-in-order test.

Launcher:
- `PolicyResponseCompatTest`: old JSON without `dns_upstream`; DoH and DoT objects with DoH and DoT backups; an
  unknown kind, an `http://` url, a wrong type (`"port": "x"`), a missing or invalid `backup` → the policy still
  decodes, `customUpstreamFrom` → null → the provider.
- `UpstreamChooserTest`: the back-off schedule; ten concurrent transport failures → one step; the probe (one query,
  the rest to the backup); success resets; `reset`; REFUSED + healthy canary → blocked answer, no period; REFUSED +
  refused canary → backup + period (`refused`); timeout after write + healthy canary → SERVFAIL, no period; timeout
  before write → period; canary verdict reused within 60 s; a route to the backup goes to the configured backup
  target (DoH or DoT), and its failure leaves the state alone. `NetworkChangedTest`: new network, gained `VALIDATED`,
  same network → no reset.
- `BootstrapResolverTest` (pure): backup IP → the backup (DoT IP and DoH IP-URL); backup name + primary IP → the
  primary; both names → Cloudflare `1.1.1.1`; both IPs → no look-up at all. `BootstrapCacheTest`: TTL clamp, stale
  served while refreshing and kept on a failed refresh, CNAME chain.
- `DohMessageTest`: ID zeroed and restored, media type with parameters accepted, wrong type / too big / 3xx refused.
  `DnsUpstreamStatusTest`: encodes exactly `dns_upstream_status.json`.
- Device check (`docs/testing/emulator.md`, a new §): primary DoH `https://dns.quad9.net/dns-query`, DoT
  `dns.adguard-dns.com` and `9.9.9.9` resolve with the filter on (proves protected sockets, NO_PROXY, bootstrap);
  `https://wrong.host.badssl.com/dns-query` → backup, `tls`; a backup given as a DoH URL answers; Wi-Fi ↔ mobile
  resets. On the user's AdGuard Home: the id in its query log; a REFUSED-mode block stays blocked; an id removed from
  "Allowed clients" → `refused`; a LAN address used on the home Wi-Fi, the backup on mobile data, back on the
  primary on returning home.

## 9. Steps

1. **Server + wire** (one commit for the wire: `server/` and the launcher's `PolicyResponse`/`StatusReportRequest`
   fields, `PolicyResponseCompatTest`, the contract file, the workflows' path filters and the root CLAUDE.md's
   shared-files paragraph). Migration (both values), `dns_server.rs`, the handlers with the backup's rules, the
   template, the status column and lines, the event. Every new launcher field optional with a `null` default
   (`DnsUpstream`, `DnsTarget`, the status `dnsUpstream`): `ServerJson` doesn't encode defaults, so
   `time_status_report.json` (byte-identical on both sides, encoded exactly by `TimeStateZoneTest`) and
   `PolicyResponseCompatTest`'s old JSON stay valid unchanged.
2. **Launcher**: the pref, `CustomUpstream`, `UpstreamChooser`, the canary, `BootstrapDns` with the resolver order,
   the DoH client, `resolveDoT` generalized, `resolveTarget` for both targets, the network callback, the status field
   and `dns_custom_v1`.
3. **Networks that block 853** (small, launcher only): DoH on 443 when 853 fails, for today's choices (Cloudflare
   `https://1.1.1.1/dns-query`, Quad9 `https://9.9.9.9/dns-query`) and the Cloudflare bootstrap resolver; the
   bootstrap's disk cache. Under Custom the parent's servers are what they are (a DoH backup already uses 443).

Either release order is safe: a new server with an old launcher keeps it on Cloudflare (and says so); a new
launcher with an old server never gets `dns_upstream`. Release together. DEPLOY.md: both addresses are in backups;
the server never contacts them; the phone reaches them directly over its mobile data or Wi-Fi, never through the
launcher's Tailscale, so a home server (AdGuard Home with encryption on, "Allowed clients" set to the ids, a
certificate a phone trusts for the name typed) works on the home Wi-Fi and the backup answers elsewhere; to reach it
from outside too, one option is a public name with a port forward, or behind CGNAT Tailscale Funnel to AdGuard Home's
DoH (`tailscale funnel --bg --https=443 …` with AdGuard's unencrypted DoH behind it, or a TLS-terminated TCP funnel
for DoT - verify the flags when writing it); give the backup as an IP so no look-up leaves for anyone else; phones
need step 2's launcher (the PWA names those that don't).

## 10. Decisions 2026-10-18 (the owner)

1. **Backup under Custom**: the parent's own, a second field with §2's grammar; required, prefilled `1.1.1.3`
   (Cloudflare for Families), not equal to the primary. No hard-coded fallback; no fail-closed mode.
2. **REFUSED from a healthy server = blocked** (the phone's NODATA, the canary deciding "healthy"): yes.
3. **Networks that block 853**: yes, as step 3.
4. **Addresses**: any, refusing only loopback, unspecified and multicast; no tailnet-specific rules. The phone never
   uses the launcher's tsnet for DNS.
5. **Two layers** (the phone's lists first): keep.
6. **Per-phone server**: later, if wanted.
7. **Backups**: keep both addresses.

## Design QA (2026-10-18, `docs/design/qa-36-design.md`)

Every finding taken: H1 → §1.6, §5.4, §7; M1, M3 → §5.4; M2 → §5.2/§5.3; M4, M5 → §2 (M5 also §9); M6 → §5.1;
M7 → §1.7; L1-L3, N2 → §2; L4, L5 → §4.1; L6, L7, L9 → §5.2/§5.3; L8 → §5.1; N1 → §6; N3 → §9; N4 → §3;
N5 → §1.2. Not taken: none. The owner's decisions (§10) since replaced M4's and M5's refusals (any address allowed,
§2) and M7's Families fallback (the parent's backup, prefilled Families); see the QA's "Revision 2026-10-18".

## Step 1 implementation status

Branch `dns-s1` (from `c570886e`), 2026-10-18. Server + wire, in one commit with the launcher's side.

**Shipped as specified**: migration `0079_custom_dns.sql` (both columns, `custom_rev`, `device_status.dns_upstream_json`;
in `migrations_73_to_79_in_order`); `src/dns_server.rs` (§2's grammar, every row and refusal, `normalized()`, `hint()`,
equality, the §3 pair rule, the policy object, the event detail, `sanitize_dns_upstream`); the policy's `dns_upstream`
next to an unchanged `dns_upstream_provider` (`"cloudflare"` under Custom); the status field stored through known words
only; the PWA group of §6 (picker, the Custom Server row, the write-only fields, `1.1.1.3` only while no backup is
stored, all-or-nothing Save, refusals in place with 400 + `no-store`, Forget, the per-phone lines, the ⓘ); the event
`dns_server_changed` with kinds only; the contract `dns_upstream_status.json` in both directories, the path filters of
`launcher.yml` and `server-ci.yml` (music doesn't read it), the root `CLAUDE.md`. Tests per §8's server list,
`PolicyResponseCompatTest`, `DnsUpstreamStatusTest`.

**Deviations, with the reason**:
- **The launcher's `dnsUpstream` is a raw `JsonElement?`**, not a typed `DnsUpstream`/`DnsTarget` with nullable
  fields: `ServerJson` has no `coerceInputValues`, so a wrong type (§8's `"port": "x"`) would still fail the whole
  decode of a typed field. Raw JSON read field by field is the project's existing pattern (`music`, `lockGlance`,
  `app_display`) and meets §4.1's "never fails the policy" literally.
- **`customUpstreamFrom` is in step 1** (pure, `server/CustomUpstream.kt`, unused until step 2): §8's compat test needs
  it. **`DnsUpstreamStatusTest` too** (listed under step 2 in §8): the contract file is step 1's, and the launcher side
  compares its copy like `TimeStateZoneTest`.
- **"Web filter off"** comes from the phone's server-side filter switch (`device_policy.vpn_filter_enabled`). A capable
  phone whose report has no `dns_upstream` (its VPN not running, or the new servers not yet taken) reads "Waiting for
  the phone".
- **Forget with something typed**: the typed values win (Forget acts only with both fields empty), so a parent who
  ticks Forget and types a new server gets the new server, never a silent loss of what they typed.
- **The event** is written on every change of the choice too (Cloudflare ↔ Quad9 included: "every change of the
  three"), and on Forget only when something was stored. The hint never shows the port.
- `tls://host/` (a bare trailing slash) is taken as DoT with no path; any other path on `tls://` is refused.

**Code QA fix round** (`docs/review/qa-36-s1-code.md`): L1 - each hint and the `1.1.1.3` prefill follow their own
stored value, not the pair's validity (a stale or equal stored pair shows its hints and no prefill over a stored
backup; the policy still reads it as Cloudflare). L2 - a Custom Server save returns to
`/dns?open=custom-server#server` (§6 said `/dns#server`): the row comes back open, the house rule for a form in a
closed `<details>`, still landing on its group. L3 - the picker's 400 names what is missing or equal (one check,
`check_custom`, shared with Save). L4 - phones never set up get no footer line. M1/M2 tests, and the nits
(`{?dns}` stripped only for DoH, `240/4` and NAT64 `64:ff9b::/96` judged as IPv4, one HTTP refusal per field).

**Not in step 1**: everything of §5 (step 2) and step 3. DEPLOY.md's paragraph (§9's last sentence) waits for the
release, with step 2's launcher.

**Open points for the code QA**: (1) 0079 lands with 0078 unused on `main`: if design 35's 0078 reaches a server after
0079, sqlx applies it out of order and the pre-migration backup (`MAX(version)`) takes none - 35 must ship before or
with 36. (2) The "Waiting for the phone" vs "Web filter off" split above. (3) The picker's 400 keeps "Custom server"
selected in the answer (the choice the parent made), though nothing was stored.

## Step 2 implementation status

Branch `dns-s2` (from `b0d59eb1`), 2026-10-18. Launcher only; the wire, the contract files and `server/` are untouched.

**Shipped as specified**: §5.1 - `dns_upstream_custom` written with `dns_upstream_provider` in `storeAcceptedPolicy`'s
one `commit()` (removed by an accepted policy without a valid pair; a decode failure or a rejected policy never reach
it), `DnsFilterEngine.setCustomUpstream` after the commit, `loadFromDisk` from the pref; the policy decode failure and a
corrupt cache logged by exception class only (`PolicyGate`). §5.2 - `buildDohClient` (fresh builder, `NO_PROXY`,
`ProtectedSocketFactory`, `BootstrapDns` as `Dns`, 1.5 s / 2.5 s, no redirects/cache/cookies, the `CallMarks` event
listener), POST `application/dns-message` with ID 0, the response checks and the ID restored. §5.3 - `resolveDoT`
generalized (two addresses, IPv4 first, one budget, SNI/endpoint identification for a name, no SNI + the default
hostname verifier for an IP), `bootstrapResolver`'s order, `BootstrapCache` (CNAME chain, 5 min-24 h clamp, stale while
one refresh runs, kept on failure), both names looked up at load, the primary's miss to the backup and a failed
look-up = `bootstrap`, the backup's miss dropped. §5.4 - `UpstreamChooser` (`route`, `afterTransport`, `afterAnswer`,
`afterOdd`, `needsCanary`, `reset`) in one `AtomicReference` by CAS, the canary `. IN NS` (2 s, one in flight, 60 s),
REFUSED from a healthy server = the phone's NODATA, SERVFAIL/silence = SERVFAIL, the chooser judging only the primary,
`registerBestMatchingNetworkCallback` (INTERNET + NOT_VPN) with the pure `networkChanged`. §4.2 - the status field
while the VPN runs with a custom server, `dns_custom_v1`. The lists block first; the FCM/Spotify exemptions, the NODATA
answer and `BlockedEventLog` are unchanged; no underlying networks, lockdown off, never tsnet. Tests per §8's launcher
list (`UpstreamChooserTest` with `networkChanged` and the status field, `CustomDnsTest`, `BootstrapResolverTest` with
the cache, `DohMessageTest`, `CustomUpstreamPrefTest` with the log scan).

**Deviations, with the reason**:
- **Reasons the contract has no word for**: a canary SERVFAIL or any non-NOERROR answer is `timeout`; a DoT stream
  closed or garbled after the request is `connect`; a connect that times out is `connect` (`timeout` only once the TCP
  connection is up) - the PWA's "can't reach your server from where the phone is" reads right for a home server away
  from home. The contract file stays as it is.
- **`releaseProbe`** (new in the chooser): the back-off's probe whose primary has no address yet (its look-up runs)
  gives the slot back, so the next query probes once the address is there; otherwise `probing` would stick.
- **A failed look-up with nothing cached is retried after 30 s** (or at a network reset), one look-up per name at a
  time: without it a name backup on a captive portal or offline would start a look-up per query. The primary's
  look-ups are paced by the back-off anyway.
- **The DoH client is owned by `CustomDns`** (one per config while the VPN runs), not built in `startVpn` directly:
  `KidVpnService.syncCustom` builds a `CustomDns` when the VPN comes up with a config and whenever the config changes
  (any change, not only `rev`), and closes (evicts) the old one; `stopSelfCleanly` closes it.
- **Gen checks**: `afterAnswer` also ignores an answer from an older `gen` (a slow answer that began before a period
  doesn't end it); the canary's verdict is kept whatever the `gen` (so one canary per 60 s holds); `reset` keeps
  `sinceMs` while healthy (the report's "since" stays the time the server came into use).
- **Cloudflare/Quad9 keep today's timeouts** (`PROVIDER_BUDGET`: 5 s connect, 10 s in all) through the generalized
  `resolveDoT`; the 2.5 s budget is for the parent's servers, where a backup answer must land inside the stub's first
  try. A garbled provider answer is now dropped rather than relayed.
- **`cloudflare-dns.com` is always `1.1.1.1`** in `BootstrapDns` (the bootstrap fallback's fixed address), also when
  a parent types that name.
- **The addresses kept are one IPv4 and one IPv6** (`ipv4First`), not two of one family.
- `CustomDns` takes its clock, log and transport as parameters (Android's by default) so `CustomDnsTest` runs the glue
  on the JVM.

**Device checks owed** (`docs/testing/emulator.md` §6m covers the emulator part): a real DoH and a real DoT server
(by name and by IP) through the protected sockets, `NO_PROXY` and the bootstrap; a LAN server on the home Wi-Fi, then
away on mobile data (backup, `connect`), then back home (the network reset tries it at once); a network that blocks
853 (a DoT primary goes to the backup; step 3 is not built); the canary against AdGuard Home's REFUSED - a
REFUSED-mode block stays blocked, an id removed from "Allowed clients" is `refused`; the id in AdGuard Home's query
log; Wi-Fi/mobile switches on the Jelly Star.

**Open points for the code QA**: (1) the reason words above (`connect` for a connect timeout and a closed DoT stream,
`timeout` for a canary SERVFAIL). (2) A config change closes the old client while its queries may be in flight; those
are dropped once (the stub retries). (3) The bootstrap hole (a home-IP primary, a backup by name, a reboot away from home) - closed
by the fix round's fall-through (H1). (4) A canary waiter that gives up - since the fix round it leaves the state alone and answers
its own query as from a healthy server (L2).

**Code QA fix round** (`docs/review/qa-36-s2-code.md`, branch `qa-dns-s2`): H1 - the bootstrap look-up falls through
backup-IP → primary-IP → Cloudflare on a transport failure or silence (`bootstrapResolvers`, `CustomDns.lookUp`), for
the look-up only; a step that failed for A is skipped for AAAA; §5.3 and §7 say so. M1 - a stale entry whose refresh
failed waits the same 30 s before the next look-up. L1 - the canary's winner re-reads the cached verdict after its
claim. L2 - a waiter that gives up returns no verdict: the state is untouched and its own query gets the NODATA or
SERVFAIL of a healthy server. L3 - `CustomDnsGuardsTest` pins the lists before any resolver, the DoH client's
`ProtectedSocketFactory`/`NO_PROXY`/no redirects or cache (and no `.newBuilder(`), and `live = null` under the monitor
in `stopSelfCleanly`. L4 - `CustomDnsTest` drives the bootstrap glue: `start()`, the fall-through, a working or
NXDOMAIN resolver not passed by, the primary's miss to the backup with the probe slot given back, the `bootstrap`
period, the backup's miss dropping the query. N1 - `ipLiteral` parses IPv4/IPv6 into bytes itself (never
`InetAddress.getByName`). N2 - every DoH call's connect timeout comes from its `Budget` (an interceptor), so a DoH
bootstrap resolver gets 1 s. N3 - a failed look-up inside a period doesn't step the back-off. N4 - one ~4.8 s deadline
per query (`QUERY_DEADLINE_MS`, `canaryBudget`, `backupBudget`; §5.4).
