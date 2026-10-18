# 36 - A custom DNS server for the Web Filter (DoH/DoT, AdGuard Home's client id included)

Architect, 2026-10-18. Branch `design-36` from `main` (`95d8f73c`). A buildable spec: two steps, a third if the user
takes Q3 (§9). Revised the same day after the design QA (`docs/design/qa-36-design.md`); see "Design QA" at the end.

Sources: PLAN.md "Our own DNS server in the Web Filter" (2026-10-15 and the 2026-10-18 clarification: **any** DNS
server, the server works out the kind); server `handlers/dns_filter.rs` `set_upstream`, `templates/dns_filter.html`,
`device_api.rs` `build_policy`, `settings.rs` `key_hint`, `backups.rs` `strip_secrets_in`, DEPLOY.md "Tailscale
Funnel"; launcher `DnsFilterEngine.kt` (`resolveUpstream`: a fresh `protect()`ed TLS socket per query to a pinned IP
on 853, 5 s timeouts, `null` = dropped), `KidVpnService.kt` (only `192.0.2.2/32` routed; every app's resolver, ours
included, points at it), `MdmSyncWorker.kt`, `ServerJson` (`ignoreUnknownKeys`, no `coerceInputValues`), OkHttp 4.12.0,
dnsjava 3.6.5.

## 0. Goal and non-goals

**Today** Settings › Web Filter › DNS Server is Cloudflare or Quad9 (`dns_filter_settings.upstream`, global). The
policy's `dns_upstream_provider` names one; the phone forwards every query its blocklists let through to
`1.1.1.1`/`cloudflare-dns.com` or `9.9.9.9`/`dns.quad9.net` over DoT.

**Goal**: a third choice, **Custom server**, one text field that takes whatever address the parent has - a DoH URL
(an AdGuard Home client id in the path or the first label, or none), a DoT host name, or a public IP for DoT. The
server classifies and validates it without any network access. The phone forwards to it, checks its certificate by
the system CAs, and while the server as a whole is unhealthy answers through a fallback resolver - never unfiltered
plaintext, never broken. A name the healthy server refuses stays refused. The PWA shows, per phone, which is in use.

**Non-goals**:
- **Plain port-53 DNS** (to a LAN or tailnet server). The VPN's own sockets can't reach the tailnet (tsnet lives in
  the launcher's userspace), and unencrypted DNS on the way to a home server is what this project avoids.
- DoQ, DoH3, DNSCrypt, DNS stamps (`sdns://`). Refused with a reason (§2).
- A per-phone custom server (Q6). Private DNS: stays locked to Opportunistic; the custom server applies only while
  the Web filter (the VPN) is on. Replacing the phone's blocklists: two layers (Q5).

## 1. Decisions

1. One field, stored normalized, re-parsed by one pure function wherever read (`src/dns_server.rs`).
2. The server never resolves or contacts the address. No SSRF surface.
3. The value is a secret (it may hold a client id): write-only, never logged, events carry its kind only. It
   **stays in backups** (§7, Q7).
4. With Custom chosen, `dns_upstream_provider` is `"cloudflare"` (old launchers); a new `dns_upstream` object
   carries the custom server.
5. **The fallback answers only while the custom server as a whole is unhealthy, never a name a healthy server
   didn't resolve** (§5.4): transport failures start a period; an odd answer is judged by a canary query.
6. The fallback under Custom is **Cloudflare for Families** (`1.1.1.3`, `family.cloudflare-dns.com`, DoT), so a
   phone whose own lists are off is never unfiltered during an outage (Q1). Cloudflare and Quad9 are unchanged.
7. Bootstrap through the fallback's DoT path, cached in memory by TTL; no server-resolved IPs (§5.3).
8. DoH over a dedicated OkHttp client, protected sockets, our own `Dns`, no proxy (§5.2); DoT generalized.

## 2. The field: grammar and classification

Trim; strip one trailing dot of the host. Refuse an empty value (that's "Forget", §6), more than 512 characters,
whitespace or control characters, non-ASCII ("Type the name in its xn-- form"). Then by scheme (case-insensitive):

| Typed | Kind | Host | Port | Path |
|---|---|---|---|---|
| `https://dns.adguard-dns.com/dns-query` | DoH | `dns.adguard-dns.com` | 443 | `/dns-query` |
| `https://dns.example/dns-query/abc123` | DoH | `dns.example` | 443 | `/dns-query/abc123` |
| `https://abc123.dns.example/dns-query` | DoH | `abc123.dns.example` | 443 | `/dns-query` |
| `https://dns.example` or `https://dns.example/` | DoH | `dns.example` | 443 | `/dns-query` |
| `https://dns.example:8443/dns-query/abc` | DoH | `dns.example` | 8443 | `/dns-query/abc` |
| `https://dns.example/dns-query{?dns}` | DoH | (RFC 8484 template stripped) | 443 | `/dns-query` |
| `https://[2606:4700:4700::1111]/dns-query` | DoH | the IPv6 address | 443 | `/dns-query` |
| `dns.example/dns-query/abc`, `dns.example/` (no scheme, a path) | DoH | `dns.example` | 443 | as typed, `/` → `/dns-query` |
| `dns.quad9.net`, `abc123.dns.example`, `pi.tail1234.ts.net` | DoT | as typed | 853 | - |
| `dns.example:8853`, `tls://dns.example:8853` | DoT | `dns.example` | 8853 | - |
| `9.9.9.9`, `2620:fe::fe`, `[2620:fe::fe]:853` | DoT | the IP | 853 | - |

A client id is never parsed out: in a DoH path or a first label it is just part of the address. An unbracketed IPv6
is always the whole address (`2620:fe::fe:853` is an address); a port needs brackets.

**Refused**, nothing written, the reason under the field:
- `http://` - "Not encrypted: use the https:// address"; any other scheme but `https://`/`tls://` (`quic://`,
  `h3://`, `sdns://`, ...) - "Use the https:// (DNS-over-HTTPS) or tls:// (DNS-over-TLS) address"; `host:443` without
  a scheme - "That's a DNS-over-HTTPS port: add https://".
- user info - "No user name or password in the address"; a query or fragment (but a trailing `{?dns}`) - "Remove
  what follows ? or #"; a path on `tls://` - "A DNS-over-TLS address has no path".
- port 0, above 65535, 53 or 80 - "Port 53 and 80 aren't encrypted". Any other port is allowed for both kinds.
- a path character outside `A-Z a-z 0-9 - . _ ~ /` and `%XX`, or a path over 256 characters.
- host: not LDH labels (1-63, total 253), a single label, `localhost`, `*.local`, `*.home.arpa`, `*.internal` -
  "Use a name in public DNS" (the phone bootstraps through Cloudflare, §5.3).
- a last label of only digits unless the whole host is a dotted-quad IPv4 (`1.1` would read as `1.0.0.1` on the
  phone); octal, hex and shorthand IPv4; `%` (zone ids) - "Not a server's address".
- IP literals, checked after mapping IPv4-mapped/-compatible IPv6 (`::ffff:127.0.0.1`) to IPv4:
  - tailnet addresses `100.64.0.0/10`, `fd7a:115c:a1e0::/48` - "The filter can't reach the tailnet; use the server's
    public name (a tailnet name works through Tailscale Funnel)";
  - private `10/8`, `172.16/12`, `192.168/16`, ULA `fc00::/7` - "A public certificate can't name a home-network
    address; use the server's host name";
  - loopback, unspecified, link-local, multicast, broadcast, `0.0.0.0/8`, `192.0.2.0/24` - "Not a server's address".

`*.ts.net` names are allowed: through Funnel they are public, with a Let's Encrypt certificate; without it the
bootstrap finds nothing and the phone reports `bootstrap`. A public name that points at a home address works at home
only; elsewhere the connect fails and the PWA says "can't reach your server" (§6).

`parse_dns_server(&str) -> Result<DnsServer, Refusal>`; `DnsServer { kind, host, port, path }`,
`DnsServer::normalized()` (`https://host[:port]/path`, `tls://host[:port]`; default ports left out, IPv6 bracketed,
host lowercased) - stored as is. `DnsServer::hint()`, one rule for every name: `…` plus the last two labels when
there are more than two (the first label may be an id), never the path; an IP as is; then the kind
(`…dns.example · DNS-over-HTTPS`). `Refusal` is an enum, worded by the template.

## 3. Data model

Migration `0079_custom_dns.sql` (design 35 takes 0078 as one file for both of its steps, decided 2026-10-18 after
its design QA; if 36 ships first, 0078 stays reserved for 35 and this is still 0079); added to the
migrations-in-order test.

```sql
ALTER TABLE dns_filter_settings ADD COLUMN custom_server TEXT NOT NULL DEFAULT '';
ALTER TABLE dns_filter_settings ADD COLUMN custom_rev INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_status ADD COLUMN dns_upstream_json TEXT;
```

- `upstream` may now be `'custom'`, set only with a parsable `custom_server`; a reader that finds `'custom'` with an
  empty or no-longer-parsable value treats it as `'cloudflare'` (the PWA says so, §6).
- `custom_rev` goes up by one on every save or forget of the custom server; the phone echoes it (§4.2). Compared by
  equality only (a restore can move it backwards).
- Choosing Cloudflare or Quad9 keeps `custom_server` (switching back needs no retyping); "Forget" clears it.

## 4. Wire

### 4.1 Policy (`GET /api/devices/policy`)

```json
"dns_upstream_provider": "cloudflare",
"dns_upstream": {"kind": "doh", "url": "https://dns.example/dns-query/abc123", "rev": 4}
```
DoT: `{"kind": "dot", "host": "abc123.dns.example", "port": 853, "rev": 4}`. `dns_upstream` is `null` unless
`upstream = 'custom'` with a parsable value; then `dns_upstream_provider` is `"cloudflare"`. Always present (null or
object) in the snapshot. ≤ ~600 bytes.

The old launcher ignores the unknown key and uses Cloudflare. The new DTO never fails the policy: every
`DnsUpstream` field nullable with a default, `kind` a `String` (no enum). `customUpstreamFrom(dto)` checks it again
(kind `doh` with a `url` starting `https://` that OkHttp parses, or kind `dot` with a non-empty host and port 1-65535,
a `rev`); anything else → the provider word.

### 4.2 Status report (`POST /api/devices/status`)

Capability `dns_custom_v1` (in `STATUS_CAPABILITIES`). New field, only while the VPN runs and Custom is configured:
```json
"dns_upstream": {"rev": 4, "in_use": "custom", "since_ms": 1792312345000, "reason": null}
```
`in_use`: `custom` | `fallback`; `reason` (with `fallback` only): `bootstrap` | `connect` | `tls` | `http` |
`refused` | `timeout`. Never the address. The server's `sanitize_dns_upstream` keeps known words, an integer `rev`, a
plausible `since_ms`, and stores `device_status.dns_upstream_json`. Pinned by `server/testdata/dns_upstream_status.json`,
byte-identical in `launcher/app/src/test/resources/` (like `time_status_report.json`).

## 5. The phone

### 5.1 Config and shape

`MdmSyncWorker` writes the accepted policy's `dns_upstream` next to `dnsUpstreamProvider` (same `commit()`), clears it
when an accepted policy has `null`, never touches it on a decode failure or a rejected policy; not in
`LastEnforcedPlan`. `DnsFilterEngine` holds it parsed in a `@Volatile` `CustomUpstream?`, set by the sync and loaded
in `loadFromDisk` (like `spotifyOn`). Offline after a reboot = the last accepted server.

`resolveUpstream(query, protect)`: no custom → today's DoT to the provider; custom → the chooser's route (§5.4).
Blocking, `BlockedEventLog` (the phone's own blocks only), the FCM/Spotify exemptions and the NODATA answer don't
change. Logs carry the kind and exception class only, never host, URL or `e.message` (OkHttp's carry the URL);
`MdmSyncWorker` logs a policy decode failure by class only (a `JsonDecodingException` quotes the input).

### 5.2 DoH

One `OkHttpClient`, owned by the `KidVpnService` instance (built in `startVpn` and on a new `rev`;
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

`BootstrapDns.lookup(host)`: an IP literal returns itself. A name: A and AAAA queries (dnsjava) through
`resolveDoT(families)` with its own bound (1 s connect, 1 s read), never through `classifyDomain`. The A/AAAA
records of the final name of a CNAME chain in the answer are taken. A memory cache, TTL clamped to 5 min-24 h; an
expired entry is still served while one refresh runs. A cache miss answers that query through the fallback while the
look-up completes in the background - not a server failure; nothing at all after the look-up → `bootstrap`. (A disk
cache comes only with Q3, where it keeps DoH working across a reboot on a network that blocks 853.)

A DoT id in the first label reaches Cloudflare in this look-up, once per TTL (it is in the clear in SNI on every
connection anyway); an id in a DoH path stays inside TLS. The ⓘ recommends a path id.

DoT to a custom server is today's `resolveUpstream` generalized to `resolveDoT(addresses, host, port)`: at most two
addresses, IPv4 first, within one 2.5 s budget for connect + handshake + answer, so a fallback answer lands inside
the stub resolver's first ~5 s try. A name: SNI and endpoint identification on it, as today. An IP: no SNI (RFC 6066),
and after the handshake `HttpsURLConnection.getDefaultHostnameVerifier().verify(ip, session)` or `tls`. "Request
written" = the length-prefixed query flushed. A fresh connection per query, as today.

### 5.4 The fallback rule (`UpstreamChooser`, pure)

Outcomes of one custom attempt:
- `Answer` - a DNS answer other than REFUSED/SERVFAIL (NXDOMAIN and AdGuard's `0.0.0.0` included) → relayed.
- `Transport(reason)` - no bootstrap address, connect, TLS/verifier, HTTP non-200/3xx/wrong type, a DoT stream
  closed before an answer, a timeout before the request was written.
- `Odd(kind)` - REFUSED, SERVFAIL, a timeout after the request was written.

State: `{gen, mode: Healthy | Fallback, failures, skipUntilMs, probing, sinceMs, reason, canary: (atMs, ok)?}` in one
`AtomicReference`, changed by CAS. Pure functions:
- `route(s, now)`: `Healthy` → `Custom`. `Fallback` with `now < skipUntilMs` or `probing` → `Fallback`. Else
  `Probe` (this query tries custom; `probing = true`, the rest go to the fallback until it returns).
- `afterTransport(s, attemptGen, now, reason)`: if `attemptGen != s.gen` → `s` unchanged (a burst counts once). Else
  `mode = Fallback`, `failures + 1`, `skipUntilMs = now + min(60 s · 2^(failures-1), 15 min)`, `probing = false`,
  `gen + 1`. The query itself goes to the fallback.
- `afterAnswer(s, attemptGen, now)`: from `Fallback` (the probe) → `Healthy`, `failures = 0`, `gen + 1`.
- `afterOdd(s, attemptGen, now, kind, canaryOk)`: the canary decides. `canaryOk == false` → as `afterTransport`
  (reason `refused` when the canary was REFUSED - AdGuard Home's answer to an id not in "Allowed clients" - else its
  transport reason); the query goes to the fallback. `canaryOk == true` → as `afterAnswer`, and the name's own
  outcome stands: REFUSED → the phone's NODATA (`buildBlockedResponse`), SERVFAIL or the timeout → a relayed
  `SERVFAIL`. **Never the fallback for that name.**
- `needsCanary(s, now)`: no verdict younger than 60 s. The canary is `. IN NS` to the custom server (every resolver
  answers the root from its priming cache; no list blocks it), 2 s budget; ok = a NOERROR answer. At most one in
  flight (a shared `CompletableDeferred` in the glue); its verdict cached 60 s in the state.
- `reset(s)` - a network reset or a new `rev`: `Healthy`, `failures = 0`, `skipUntilMs = 0`, `probing = false`,
  `canary = null`, `gen + 1`; the glue evicts the OkHttp pool (a new `rev` rebuilds the client).

Network reset: `KidVpnService` registers `registerBestMatchingNetworkCallback` for `INTERNET` + `NOT_VPN` (its own
default network is the VPN, which never changes). Pure `networkChanged(old, new)`: the best `Network` changed, or it
gained `VALIDATED` (a captive portal logged in). Unregistered in `stopSelfCleanly`.

Battery: one probe per back-off at most, one canary per 60 s at most. Captive portal: transport failures to both
custom and fallback, the query dropped as today; the login gains `VALIDATED` → reset. The status (§4.2) is
`mode`/`sinceMs`/`reason`, reported with the next regular status report.

## 6. The PWA (Settings › Web Filter, `#server`)

Design 31's shapes. The group:
1. The inline picker "Look-ups go to": Cloudflare / Quad9 / Custom server (`POST /dns/upstream`, autosave, back to
   `/dns#server`). Custom with no parsable stored value: 400, the page rendered in place with the row below open and
   "Type your server's address first" under the field; nothing written.
2. A row "Custom Server" that opens (`<details>`, open when Custom is chosen and nothing is stored, or on a refusal):
   the hint (`Set (…dns.example · DNS-over-HTTPS).` / `Not set.`), a password-type input, empty, `autocomplete="off"`,
   placeholder `https://… or a host name`, Save, and with a value stored a "Forget Custom Server" checkbox in the same
   form (the Tailscale key's pattern). `POST /dns/upstream/custom` (full auth, `dns_filter::set_custom`):
   - a value → `parse_dns_server`; ok → store `normalized()`, `upstream = 'custom'`, `custom_rev + 1`, nudge all
     phones, 303 `/dns#server`. Typing it means using it.
   - refused → **400, rendered in place** (form action `…#server`), the typed value back in the input (body only,
     never a URL; `Cache-Control: no-store`), `aria-invalid` and the reason under the field (`aria-describedby`).
   - empty and Forget → clear, `custom_rev + 1`; `upstream = 'cloudflare'` if it was custom; nudge; 303.
   - empty without Forget → nothing changes, 303.
3. The footer, while Custom is chosen, one line per phone from its last report:
   - "Anna · Custom server in use"
   - "Ola · Cloudflare for Families since 10:42 - the server's certificate wasn't accepted" (one wording per
     `reason`; `connect`: "can't reach your server", which reads right away from home; `refused`: "your server
     refused this phone - check its allowed clients")
   - "Per · Cloudflare - update Vibb One on this phone to use your server" (no `dns_custom_v1`)
   - "Kari · Waiting for the phone" (no report with the current `rev`), "Web filter off" (VPN off).
   A stored value that no longer parses: "The saved address isn't accepted any more: type it again". The ⓘ: a bare
   name or IP is DNS-over-TLS on 853 (for DoH type the https:// address); a public certificate is needed; a
   home-network name works at home only, a tailnet name only through Funnel; a path id stays inside TLS; the phone's
   lists still block first; while the server is unreachable the phone uses Cloudflare for Families.

Security event `dns_server_changed` on every change of the three, detail `{"server":"custom","kind":"doh"}` - never
host or path. The settings hub's Web Filter row is unchanged.

## 7. Security

- **SSRF**: none - the server only parses (§1.2). The phone is the only client of the address.
- **The id as a secret**: write-only, hinted (no first label, no path), out of logs, events, URLs and redirects; in
  the database, the policy to enrolled phones (bearer-authenticated, TLS) and backups. The backlog's "left out of
  backups where possible" is answered by "not possible without losing the restore": stripping or sealing it makes a
  restore lose it, as the TOTP secret's precedent (design 22 §3.2.8) decided; it unlocks nothing of ours. Pinned by a
  `strip_secrets_in` test.
- **Filtering never weakens**: the fallback serves only while the server is unhealthy (transport, or a failed
  canary); a healthy server's REFUSED/SERVFAIL/silence stays a block or a SERVFAIL. A child can't open a window with
  a slow name: that is an `Odd` with a healthy canary. The fallback under Custom is Cloudflare for Families.
- **Downgrade**: a TLS-breaking MITM can only push the phone to the fallback over DoT (encrypted, filtered).
- **A custom server the parent chose** sees every allowed query and can answer anything (as Cloudflare can today).
  The FCM/Spotify exemptions are block decisions a forged answer can't touch.
- **Shape and size**: responses ≤ 65535 bytes, parsed, ID restored, no redirects; the value ≤ 512.

## 8. Tests

Server:
- `dns_server::tests::classifies` (every row of §2's table) and `refuses` (every refusal incl. `host:443`, `1.1`,
  octal/hex, `%`, `::ffff:127.0.0.1`, `::ffff:192.168.1.1`, private, tailnet IPs); `ts_net_names_are_allowed`;
  `normalized_parses_to_itself`; `hint_hides_the_path_and_the_first_label` (DoH and DoT).
- `tests::device_api`: `policy_json_keys_snapshot` with `dns_upstream`; `custom_server_in_the_policy` (provider
  `cloudflare`, DoH `{kind,url,rev}`, DoT `{kind,host,port,rev}`); `custom_without_a_parsable_value_is_cloudflare`.
- `tests::settings_pages`: save → 303 `/dns#server`, the page shows the hint but never the path or the first label;
  each refusal → 400, the reason, nothing written; Custom picked with nothing stored → 400; Forget; empty keeps; the
  forms in `check_forms`; the event detail holds no host or path.
- `dns_upstream_status_contract` (the testdata file posted, both copies compared), `sanitize_dns_upstream`, the
  footer's lines (in use, fallback with each reason, old launcher, stale `rev`, filter off).
- `backups`: `strip_secrets_in` keeps `custom_server`. The migrations-in-order test.

Launcher:
- `PolicyResponseCompatTest`: old JSON without `dns_upstream`; a DoH and a DoT object; an unknown kind, an `http://`
  url, a wrong type (`"port": "x"`) → the policy still decodes, `customUpstreamFrom` → null → the provider.
- `UpstreamChooserTest`: the back-off schedule; ten concurrent transport failures → one step; the probe (one query,
  the rest fallback); success resets; `reset`; REFUSED + healthy canary → blocked answer, no period; REFUSED +
  refused canary → fallback + period (`refused`); timeout after write + healthy canary → SERVFAIL, no period;
  timeout before write → period; canary verdict reused within 60 s. `NetworkChangedTest`: new network, gained
  `VALIDATED`, same network → no reset.
- `DohMessageTest`: ID zeroed and restored, media type with parameters accepted, wrong type / too big / 3xx refused.
  `BootstrapCacheTest`: TTL clamp, stale served while refreshing, CNAME chain. `DnsUpstreamStatusTest`: encodes
  exactly `dns_upstream_status.json`.
- Device check (`docs/testing/emulator.md`, a new §): custom DoH `https://dns.quad9.net/dns-query`, DoT
  `dns.adguard-dns.com` and `9.9.9.9` resolve with the filter on (proves protected sockets, NO_PROXY, bootstrap);
  `https://wrong.host.badssl.com/dns-query` → fallback, `tls`; Wi-Fi ↔ mobile resets. On the user's AdGuard Home:
  the id in its query log; a REFUSED-mode block stays blocked; an id removed from "Allowed clients" → `refused`.

## 9. Steps

1. **Server + wire** (one commit for the wire: `server/` and the launcher's `PolicyResponse`/`StatusReportRequest`
   fields, `PolicyResponseCompatTest`, the contract file, the workflows' path filters and the root CLAUDE.md's
   shared-files paragraph). Migration, `dns_server.rs`, the handlers, the template, the status column and lines, the
   event.
2. **Launcher**: the pref, `CustomUpstream`, `UpstreamChooser`, the canary, `BootstrapDns`, the DoH client,
   `resolveDoT` generalized, Families as the fallback, the network callback, the status field and `dns_custom_v1`.
3. **If Q3 is taken** (small, launcher only): Cloudflare DoH on 443 (`https://1.1.1.1/dns-query`, or Families'
   `https://1.1.1.3/dns-query` under Custom) when 853 fails, for the fallback, the bootstrap and today's
   Cloudflare/Quad9 choices; the bootstrap's disk cache.

Either release order is safe: a new server with an old launcher keeps it on Cloudflare (and says so); a new
launcher with an old server never gets `dns_upstream`. Release together. DEPLOY.md: the custom server is in backups;
the server never contacts it; it needs a public certificate (AdGuard Home: encryption on, "Allowed clients" set to
the ids); behind CGNAT, Tailscale Funnel to AdGuard Home's DoH (`tailscale funnel --bg --https=443 …` with AdGuard's
unencrypted DoH behind it, or a TLS-terminated TCP funnel for DoT - verify the flags when writing it); phones need
step 2's launcher (the PWA names those that don't).

## 10. Open questions (recommendation first)

1. **Fallback under Custom**: Cloudflare for Families (`1.1.1.3`) rather than plain `1.1.1.1` (the original
   requirement)? Recommended: Families (written so); plain Cloudflare stays for the Cloudflare choice.
2. **REFUSED from a healthy server = blocked** (the phone's NODATA, the canary deciding "healthy")? Recommended: yes.
3. **Networks that block 853** (school Wi-Fi breaks the filter today): Cloudflare DoH on 443 when 853 fails?
   Recommended: yes, as step 3 - the DoH client exists then, and it helps every phone, not only Custom.
4. **`*.ts.net` names through Funnel** allowed (only tailnet IPs refused), in DEPLOY.md? Recommended: yes.
5. **Two layers**: the phone's lists block first, the custom server filters the rest. Recommended: keep.
6. **Per-phone server** (an id per child): a per-phone override later, server-only. Recommended: later, if wanted.
7. **Backups**: keep the address (confirmed by the design QA, §7). Recommended: keep.

## Design QA (2026-10-18, `docs/design/qa-36-design.md`)

Every finding taken: H1 → §1.5, §5.4, §7; M1, M3 → §5.4; M2 → §5.2/§5.3; M4, M5 → §2 (M5 also §9); M6 → §5.1;
M7 → §1.6, Q1; L1-L3, N2 → §2; L4, L5 → §4.1; L6, L7, L9 → §5.2/§5.3; L8 → §5.1; N1 → §6; N3 → §9; N4 → §3;
N5 → §1.2. Not taken: none.
