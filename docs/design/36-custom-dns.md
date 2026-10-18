# 36 - A custom DNS server for the Web Filter (DoH/DoT, AdGuard Home's client id included)

Architect, 2026-10-18. Branch `design-36` from `main` (`95d8f73c`). A buildable spec: two steps (§9).

Sources:
- PLAN.md, "Fix backlog" → "Approved": "Our own DNS server in the Web Filter" (2026-10-15, and the 2026-10-18
  clarification: **any** DNS server - a DoH URL with or without a client id in the path, a DoT host name, a plain IP
  over DoT; the field takes what the parent has, the server works out the kind and validates it).
- server: `handlers/dns_filter.rs` `set_upstream`, `templates/dns_filter.html` (`#server`), `models.rs`
  `DnsFilterSettings` / `PolicyResponse.dns_upstream_provider`, `handlers/device_api.rs` `build_policy`,
  `handlers/settings.rs` `key_hint` (the Tailscale key's write-only pattern), `handlers/backups.rs`
  `strip_secrets_in`, `src/tests/device_api.rs` `policy_json_keys_snapshot`, migration `0009_dns_filter.sql`.
- launcher: `server/DnsFilterEngine.kt` (`resolveUpstream`: a fresh raw TLS socket per query, `protect()`ed, to a
  pinned IP on 853, SNI + `endpointIdentificationAlgorithm = "HTTPS"` on the provider's name, 5 s connect and read,
  `null` on any failure = the query is dropped), `server/KidVpnService.kt` (only `192.0.2.2/32` is routed; the system
  resolver of every app, the launcher included, points at it), `dto/PolicyResponse.kt`, `ServerJson`
  (`ignoreUnknownKeys = true`), `PolicyResponseCompatTest`, OkHttp 4.12.0 (already a dependency), dnsjava 3.6.5.

## 0. Goal and non-goals

**Today** Settings › Web Filter › DNS Server is Cloudflare or Quad9 (`dns_filter_settings.upstream`, global). The
policy's `dns_upstream_provider` names one; the phone forwards every query its blocklists let through to
`1.1.1.1`/`cloudflare-dns.com` or `9.9.9.9`/`dns.quad9.net` over DoT.

**Goal**: a third choice, **Custom server**, one text field that takes whatever address the parent has - a DoH URL
(with an AdGuard Home client id in the path or not), a DoT host name (with the id as its first label or not), or an
IP for DoT. The server classifies and validates it without any network access. The phone forwards to it, checks its
certificate by the system CAs, and falls back to Cloudflare DoT when it fails - never unfiltered, never broken.
The PWA shows, per phone, whether the custom server is in use.

**Non-goals**:
- **Plain port-53 DNS** (to a LAN or tailnet server). The VPN's own sockets can't reach the tailnet (tsnet lives in
  the launcher's userspace), and unencrypted DNS on the way to a home server is what this project avoids. A LAN-only
  DoT/DoH server works if its certificate is one the system trusts (a public CA's, for a name or the IP).
- DoQ, DoH3, DNSCrypt, DNS stamps (`sdns://`). Refused with a reason (§2).
- A per-phone custom server. Global like today; the wire is per device already, so a per-phone override is a later
  server-only change (Q2).
- Private DNS. It stays locked to Opportunistic; the custom server applies only while the Web filter (the VPN) is on.
- Replacing the phone's blocklists. Two layers: the phone blocks first, the custom server filters what's left (Q1).

## 1. Decisions

1. One field. Its value is stored normalized and re-parsed by the same pure function wherever it is read
   (`src/dns_server.rs`, `parse_dns_server`). One source of truth for the grammar.
2. The server never resolves or contacts the address: not at save, not at the hourly refresh. No SSRF surface.
3. The value is a secret (it may hold a client id): write-only in the PWA, never logged, never in an event beyond
   its kind. It **stays in backups** (a restore must keep the phones on the parent's server; it is a DNS id, not a
   credential that unlocks anything of ours). DEPLOY.md says so (Q3).
4. The old field stays: with Custom chosen, `dns_upstream_provider` is `"cloudflare"` - what an old launcher
   (0.26.0) uses, and what a new one falls back to. A new object `dns_upstream` carries the custom server.
5. The phone falls back to Cloudflare DoT on any failure of the custom server, **per period**: the failed query is
   answered through Cloudflare at once, then the custom server is skipped for a back-off (60 s, doubling to 15 min),
   reset on success, on a network change and on a new config (§5.4).
6. Bootstrap: the phone resolves the custom server's host name **through the built-in Cloudflare DoT path**
   (encrypted, protected socket, no loop through its own VPN), cached by TTL. No server-resolved IPs.
7. Fallback is always Cloudflare, also for a parent who had Quad9 before.
8. DoH: RFC 8484 POST over OkHttp (HTTP/2, one pooled connection), its sockets `protect()`ed through a
   `SocketFactory`, its look-ups through our own `Dns` (§5.2). DoT: today's code, with a host or an IP.

## 2. The field: grammar and classification

Trim. Refuse an empty value (that's "Forget", §6), more than 512 characters, whitespace or control characters inside,
non-ASCII (reason: "Type the name in its xn-- form"). Then by scheme (case-insensitive):

| Typed | Kind | Host | Port | Path |
|---|---|---|---|---|
| `https://dns.adguard-dns.com/dns-query` | DoH | `dns.adguard-dns.com` | 443 | `/dns-query` |
| `https://dns.example/dns-query/abc123` | DoH | `dns.example` | 443 | `/dns-query/abc123` |
| `https://dns.example` or `https://dns.example/` | DoH | `dns.example` | 443 | `/dns-query` |
| `https://dns.example:8443/dns-query/abc` | DoH | `dns.example` | 8443 | `/dns-query/abc` |
| `https://dns.example/dns-query{?dns}` | DoH | (RFC 8484 template stripped) | 443 | `/dns-query` |
| `https://[2606:4700:4700::1111]/dns-query` | DoH | the IPv6 address | 443 | `/dns-query` |
| `dns.example/dns-query/abc` (no scheme, a path) | DoH | `dns.example` | 443 | `/dns-query/abc` |
| `dns.quad9.net`, `abc123.dns.example` | DoT | as typed | 853 | - |
| `tls://abc123.dns.example`, `tls://dns.example:8853` | DoT | as typed | 853 / 8853 | - |
| `9.9.9.9`, `2620:fe::fe`, `[2620:fe::fe]:853` | DoT | the IP | 853 | - |

A client id is never parsed out: in a DoH path or a DoT first label it is just part of the address.

**Refused**, nothing written, the reason under the field:
- `http://` - "Not encrypted: use the https:// address".
- `quic://`, `h3://`, `sdns://`, `udp://`, `tcp://`, any other scheme - "Use the https:// (DNS-over-HTTPS) or tls://
  (DNS-over-TLS) address".
- user info (`user@`, `user:pw@`) - "No user name or password in the address".
- a query or a fragment (other than the trailing `{?dns}`) - "Remove what follows ? or #".
- a path on `tls://` - "A DNS-over-TLS address has no path".
- port 0, above 65535, 53 or 80 - "Port 53 and 80 aren't encrypted". Any other port is allowed for both kinds (home
  servers run DoH on 8443).
- a path character outside `A-Z a-z 0-9 - . _ ~ /` and `%XX`, or a path over 256 characters.
- host: not LDH labels (1-63, total 253), or a single label, `localhost`, `*.local`, `*.home.arpa`,
  `*.internal` - "Use a name in public DNS, or the server's IP" (the phone bootstraps through Cloudflare, §5.3).
- `*.ts.net`, `100.64.0.0/10`, `fd7a:115c:a1e0::/48` - "The filter can't reach the tailnet; use the server's
  public or home-network address".
- loopback, unspecified, link-local, multicast, broadcast, `0.0.0.0/8`, `192.0.2.0/24` (the VPN's own) -
  "Not a server's address".

Private (RFC 1918, ULA) addresses are allowed: they work with a certificate whose IP SAN the system trusts. The
ⓘ says so.

`parse_dns_server(&str) -> Result<DnsServer, Refusal>`; `DnsServer { kind, host, port, path }`,
`DnsServer::normalized()` (`https://host[:port]/path`, `tls://host[:port]`; default ports left out, IPv6 in
brackets, host lowercased) - stored as is. `DnsServer::hint()`: DoH shows the host only (`dns.example · DNS-over-HTTPS`);
DoT shows the last two labels, `…` before them when there are more (`…dns.example · DNS-over-TLS`), because the
first label may be the id; an IP shows the IP. `Refusal` is an enum, worded by the template.

## 3. Data model (migration `0079_custom_dns.sql`, or `0078`)

Number: `0078` is the next free one on `main`, but design 35 is designed in parallel and may take it. **Take 0079**
if design 35 has landed `0078` when this lands, else `0078`; add it to the migrations-in-order test either way.

```sql
ALTER TABLE dns_filter_settings ADD COLUMN custom_server TEXT NOT NULL DEFAULT '';
ALTER TABLE dns_filter_settings ADD COLUMN custom_rev INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_status ADD COLUMN dns_upstream_json TEXT;
```

- `upstream` may now be `'custom'`. It is set only with a parsable `custom_server`; a reader that finds `'custom'`
  with an empty or no-longer-parsable value treats it as `'cloudflare'` (and the PWA says so, §6).
- `custom_rev` goes up by one on every save or forget of the custom server; the phone echoes it (§4.2).
- Choosing Cloudflare or Quad9 keeps `custom_server` (switching back needs no retyping); "Forget" clears it.

## 4. Wire

### 4.1 Policy (`GET /api/devices/policy`)

```json
"dns_upstream_provider": "cloudflare",
"dns_upstream": {"kind": "doh", "host": "dns.example", "port": 443, "url": "https://dns.example/dns-query/abc123", "rev": 4}
```
DoT: `{"kind": "dot", "host": "abc123.dns.example", "port": 853, "rev": 4}` (no `url`). `dns_upstream` is `null`
unless `upstream = 'custom'` with a parsable value; then `dns_upstream_provider` is `"cloudflare"`. Always present
(null or object) in the snapshot. Size: ≤ ~700 bytes.

The old launcher ignores the unknown key (`ignoreUnknownKeys`) and uses Cloudflare. A new launcher prefers a valid
`dns_upstream` and checks it again (`customUpstreamFrom`: kind `doh`/`dot`, a non-empty host, port 1-65535, a DoH `url`
starting `https://` whose host matches); an invalid one counts as absent → the provider word.

### 4.2 Status report (`POST /api/devices/status`)

Capability `dns_custom_v1`. New field, only while the VPN runs and a custom server is configured:
```json
"dns_upstream": {"rev": 4, "in_use": "custom", "since_ms": 1792312345000, "reason": null}
```
`in_use`: `custom` | `cloudflare`; `reason` (with `cloudflare` only): `bootstrap` | `connect` | `tls` | `http` |
`refused` | `timeout`. Never the address. The server's `sanitize_dns_upstream` keeps known words, an integer `rev`,
a plausible `since_ms`, and stores `device_status.dns_upstream_json`. Pinned by `server/testdata/dns_upstream_status.json`,
byte-identical in `launcher/app/src/test/resources/` (like `time_status_report.json`; both workflows' path filters
take the two copies).

## 5. The phone

### 5.1 Shape

`DnsFilterEngine.resolveUpstream(query, protect)` reads the cached config (`CustomUpstream?`, from the policy) and the
chooser (§5.4): custom allowed → `CustomResolver.resolve` (DoH or DoT); on a failure, the same query goes through
`resolveDoT(cloudflare)` at once. Not custom → today's DoT. Blocking (`classifyDomain`), `BlockedEventLog`, the
FCM and Spotify exemptions and the NODATA answer don't change. Logs name the kind and the exception class only,
never the host, URL or `e.message` (OkHttp's messages carry the URL).

### 5.2 DoH

One `OkHttpClient` per config (rebuilt when `rev` changes), owned by the engine:
- `socketFactory(ProtectedSocketFactory(vpn))`: `createSocket()` returns `Socket().also { vpn.protect(it) }` (the
  same unconnected-socket `protect` today's DoT does); the other overloads create, protect, then connect.
- `dns(BootstrapDns)` (§5.3), never the system resolver: the launcher's own look-ups go to `192.0.2.2`, i.e. back
  into this VPN.
- `connectTimeout(2 s)`, `callTimeout(3 s)`, HTTP/2 via ALPN, the default pool (one connection, multiplexed, 5 min
  idle). No cache, no redirects (`followRedirects(false)`: a 3xx is a failure), no cookies.
- Request: POST `url`, `Content-Type` and `Accept: application/dns-message`, the query with its ID set to 0 (RFC 8484
  §4.1). Response: 200, that content type, ≤ 65535 bytes, parsable by dnsjava; the original ID put back before relay.
  Anything else → `http`.
- TLS: the system CAs (no network security config, so user CAs aren't trusted) and OkHttp's hostname verifier,
  which checks IP SANs for an IP URL.

### 5.3 Bootstrap and DoT

`BootstrapDns.lookup(host)`: an IP literal returns itself. A name: A and AAAA queries built with dnsjava, sent through
`resolveDoT(cloudflare)` (pinned `1.1.1.1`, protected), never through `classifyDomain` (a list that blocks the
server's name can't break it). The answer is cached in memory and on disk (`dns_bootstrap.tsv`, host → addresses,
expiry) with the TTL clamped to 5 min-24 h; past expiry it is still used while a refresh runs, up to 7 days. No
address → `bootstrap` failure. Known cost, said in the ⓘ: a DoT id in the first label reaches Cloudflare in this
look-up (it is in the clear in SNI anyway); DoH keeps the id inside TLS. Recommend DoH for an id.

DoT to a custom server is today's `resolveUpstream` generalized to `resolveDoT(addresses, host, port)`: connect to
the first address (2 s), then the next. A name: SNI and endpoint identification on it, as today. An IP: no SNI (RFC
6066 forbids an IP there), and after the handshake `HttpsURLConnection.getDefaultHostnameVerifier().verify(ip,
session)` (Android's verifier checks IP SANs) or `tls`. Read timeout 3 s for custom, 5 s for Cloudflare. A fresh
connection per query, as today.

### 5.4 The fallback rule (`UpstreamChooser`, pure)

State: `{rev, failures, skipUntilMs, inUse, sinceMs, reason}`. `allowCustom(now)`: `now >= skipUntilMs`.
`onSuccess(now)`: failures 0, in use custom. `onFailure(now, reason)`: failures+1, `skipUntilMs = now + min(60 s ·
2^(failures-1), 15 min)`, in use Cloudflare. `onNetworkChange()` and a new `rev`: failures 0, `skipUntilMs` 0.
- Failure: no address, connect, TLS (including the verifier), HTTP non-200/redirect/wrong type, timeout, and a
  DNS answer with RCODE `REFUSED` (AdGuard Home's answer to an id not in "Allowed clients"). `SERVFAIL`, `NXDOMAIN`
  and AdGuard's own blocked answers (`0.0.0.0`, NXDOMAIN) are answers and pass through.
- Concurrent queries during an outage each answer through Cloudflare; the state is one `AtomicReference`.
- Battery: at most one failed attempt per back-off, so a dead server costs one 3 s attempt per 15 min at worst.
- Captive portal: custom and Cloudflare both fail and the query is dropped, as today; Android's portal check
  doesn't use the VPN's DNS. Leaving the portal is a network change: custom is tried again at once.
- `KidVpnService` registers a default-network callback (`registerDefaultNetworkCallback`) that calls
  `onNetworkChange()`; unregistered in `stopSelfCleanly`.

The status (§4.2) is the chooser's state with `since_ms` = when `inUse` last changed. Reported with the next regular
status report.

## 6. The PWA (Settings › Web Filter, `#server`)

Design 31's shapes. The group:
1. The inline picker "Look-ups go to": Cloudflare / Quad9 / Custom server (`POST /dns/upstream`, autosave, back to
   `/dns#server`). Custom with no parsable stored value: 400, the page rendered in place with the row below open
   and "Type your server's address first" under the field; nothing written.
2. A row "Custom Server" that opens (`<details>`, open when Custom is chosen and nothing is stored, or on a refusal):
   label, the hint (`Set (dns.example · DNS-over-HTTPS).` / `Not set.`), a password-type input, empty, `autocomplete="off"`,
   placeholder `https://… or a host name or IP`, Save, and with a value stored a "Forget Custom Server" checkbox
   in the same form (the Tailscale key's pattern). `POST /dns/upstream/custom` (full auth, `dns_filter::set_custom`):
   - a value → `parse_dns_server`; ok → store `normalized()`, `upstream = 'custom'`, `custom_rev + 1`, nudge all
     phones, 303 `/dns#server`. Typing it means using it.
   - refused → **400, rendered in place** (form action `…#server`), the typed value back in the input (in the body only,
     never a URL; `Cache-Control: no-store` as every page), `aria-invalid` and the reason under the field
     (`aria-describedby`). Never `&typed=` in a redirect for this field.
   - empty and Forget → clear, `custom_rev + 1`; `upstream = 'cloudflare'` if it was custom; nudge; 303.
   - empty without Forget → nothing changes, 303 (empty keeps, as the Tailscale key).
3. The footer, while Custom is chosen: one line per phone from its last report:
   - "Anna · Custom server in use"
   - "Ola · Cloudflare since 10:42 - the server's certificate wasn't accepted" (one wording per `reason`)
   - "Per · Cloudflare - update Vibb One on this phone to use your server" (no `dns_custom_v1`)
   - "Kari · Waiting for the phone" (no report with the current `rev`, or the filter is off: "Web filter off").
   A stored value that no longer parses: "The saved address isn't accepted any more: type it again" (phones on
   Cloudflare). The ⓘ: what the field takes, that the server needs a certificate the phone trusts, that the phone
   falls back to Cloudflare, that the phone's own lists still block first, and the DoT-id note (§5.3).

Security event `dns_server_changed` on every change of the three (Cloudflare/Quad9 had none), detail
`{"server":"custom","kind":"doh"}` - never host or path. The settings hub's Web Filter row is unchanged.

## 7. Security

- **SSRF**: none - the server only parses (§1.2). The phone is the only client of the address.
- **The id as a secret**: write-only, hinted, out of logs, events, URLs and redirects; in the database and backups
  (Q3) and in the policy to enrolled phones over TLS. The policy is already bearer-authenticated device data.
- **Downgrade**: a MITM that breaks TLS to the custom server makes the phone fall back to Cloudflare DoT - still
  encrypted and still behind the phone's lists; the status line shows it. Acceptable. It can't make the phone go
  unencrypted or unfiltered; there is no such path.
- **A custom server the parent chose** sees every allowed query and can answer anything (as Cloudflare can today).
  The phone's lists still block first. A wrong answer can't touch the FCM/Spotify exemptions (they are block
  decisions, not answers).
- **Response size and shape**: ≤ 65535 bytes, parsed by dnsjava before relay, ID restored; redirects refused.
- **Policy size**: ≤ ~700 bytes more; the value is capped at 512.
- **Logging**: server `tracing` never gets the value (only `kind`); the launcher logs kind and exception class.

## 8. Tests

Server:
- `dns_server::tests::classifies` (every row of §2's table) and `refuses` (every refusal, with its `Refusal`);
  `normalized_parses_to_itself`; `hint_hides_the_path_and_the_first_label`.
- `tests::device_api`: `policy_json_keys_snapshot` with `dns_upstream`; `custom_server_in_the_policy` (provider
  `cloudflare` + the object with `rev`); `custom_without_a_parsable_value_is_cloudflare` (`dns_upstream` null).
- `tests::settings_pages`: save → 303 `/dns#server` and the page shows the hint but never the path or the first
  label; each refusal → 400, the reason, nothing written; Custom picked with nothing stored → 400; Forget; empty
  keeps; the forms in `check_forms`; the event detail holds no host or path.
- `dns_upstream_status_contract` (the testdata file posted and both copies compared), `sanitize_dns_upstream`, the
  footer's lines (in use, fallback with reason, old launcher, stale `rev`).
- `backups`: `strip_secrets_in` keeps `custom_server` (pins Q3). The migrations-in-order test.

Launcher:
- `PolicyResponseCompatTest`: old JSON without `dns_upstream`; with a DoH and a DoT object; an unknown kind or an
  `http://` url → null → Cloudflare.
- `UpstreamChooserTest`: the back-off schedule, success, network change, new `rev`, `REFUSED` fails, `SERVFAIL` passes.
- `DohMessageTest`: ID zeroed and restored, wrong type / too big / 3xx refused. `BootstrapCacheTest`: TTL clamp,
  stale use, 7-day end. `DnsUpstreamStatusTest`: encodes exactly `dns_upstream_status.json`.
- Device check (`docs/testing/emulator.md`, a new §): custom DoH `https://dns.quad9.net/dns-query`, DoT
  `dns.adguard-dns.com`, DoT `9.9.9.9` resolve with the filter on (this proves the protected OkHttp sockets and the
  bootstrap); `https://wrong.host.badssl.com/dns-query` falls back with `tls` and the PWA line; a blocked domain
  still blocked; airplane mode on/off retries custom. On the user's AdGuard Home: the query log shows the client id.

## 9. Steps

1. **Server + wire** (one commit for the wire: `server/` and the launcher's `PolicyResponse`/`StatusReportRequest`
   fields, `PolicyResponseCompatTest`, the contract file and the workflow path filters). Migration, `dns_server.rs`,
   the handlers, the template, the status column and lines, the event.
2. **Launcher**: `CustomUpstream`, `UpstreamChooser`, `BootstrapDns`, the DoH client, `resolveDoT` generalized, the
   network callback, the status field and `dns_custom_v1`.

Either release order is safe: a new server with an old launcher keeps it on Cloudflare (and says so); a new launcher
with an old server never gets `dns_upstream`. Release them together. DEPLOY.md: the custom server is in backups
(Q3); the server never contacts it; the custom server needs a certificate from a public CA (AdGuard Home: encryption
on, "Allowed clients" set to the ids); phones need the launcher from step 2 (the PWA names those that don't).

## 10. Open questions (recommendation first)

1. **Two layers?** Keep the phone's lists when Custom is chosen: the phone blocks first, AdGuard Home filters the
   rest; the parent can set every list to Off on the phone if AdGuard should decide alone. Recommended: keep.
2. **Per-phone server** (an id per child in AdGuard Home)? Global now; a per-phone override on the phone's Web
   Filter page later, server-only (the policy is built per device). Recommended: later, if wanted.
3. **Backups**: keep the address (a restore keeps the phones on the parent's server) rather than strip it like the
   Tailscale key (a restore would put them on Cloudflare until it is typed again). Recommended: keep.
4. **Fallback**: always Cloudflare (the requirement), even for a parent who had Quad9. Recommended: as is; a
   "fall back to Quad9" choice only if asked.
