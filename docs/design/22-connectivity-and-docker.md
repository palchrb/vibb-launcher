# 22 - Connectivity modes, public exposure, Docker image

Status: design, 2026-10-09, for QA design review. User request (2026-10-09): the admin chooses the server's exposure
((a) tailnet only, (b) all public on an own domain, (c) public phone endpoint + tailnet-only PWA; the user's own setup
is likely (c)). Each phone gets one of three connectivity modes (1 embedded tsnet, 2 the Tailscale app, 3 direct
HTTPS), set from the PWA and the setup QR. The server also ships as a multi-arch Docker image. It must be robust and
properly secure, sized for one family per install, with no multi-tenant machinery.
Builds on design 03 (Tailscale app mode: its facts table, §3 and §9 stay the reference for mode 2; this note replaces
its §6 server listener, §7 switch and its AdGuard choice) and design 19 (SSE is the only nudge). Paths: `S` =
`server/src/`, `L` = `launcher/app/src/main/java/com/kidslauncher/mdm/`. Tags: [verified: ...], [belief], [device].

## 0. Findings in today's code (fixed by this design in every mode)

1. `S/security.rs:77` `client_ip` believes `X-Forwarded-For` from any peer. Behind anything but `tailscale serve`,
   IP bans can be dodged (send a new XFF each time) and forged (get the parent's IP banned).
2. `S/handlers/auth.rs:127`: a correct password resets the failure counter before the TOTP step. An attacker who knows
   the password gets 4 TOTP tries per password retry, which is unlimited except for the IP ban, and with #1, unlimited.
3. No TOTP replay protection (`check_current`, last step not stored). No session id cycling at login or at 2FA
   (`auth.rs:133,147,277`), so session fixation is possible.
4. No CSRF defence beyond the cookie's `SameSite=Strict` (tower-sessions 0.14 `SessionConfig::default()`: `id`,
   HttpOnly, Strict, Secure [verified: docs.rs source]). It doesn't cover same-site origins (another subdomain of an
   own domain in (b)). No security headers. Inline `<script>` in 3 templates and inline handlers in 12.
5. Enrollment (`S/handlers/device_api.rs:22-62`): no attempt limit; 8 chars of 31 (~40 bits), stored in plain text,
   valid for 30 min; the SELECT and the UPDATE are separate (two racing uses can both get a token).
6. The launcher sends its bearer token without tsnet when tsnet is down (`L/server/MdmApi.kt` `createMdmApi`, "falls
   back to the device's normal network path"). With an `http://100.x` URL, that is cleartext into the carrier's CGNAT
   range: 100.64.0.0/10 is carrier space too. The manifest allows cleartext globally.
7. The provisioning page is a GET: the Wi-Fi password goes into the query (proxy access logs), and every load
   regenerates the code. The Tailscale auth key is echoed back into the settings form.
8. Every login runs argon2 (19 MiB) without a bound, so 20 parallel attempts take a 512 MB Pi Zero 2 W down. Unknown
   usernames skip argon2, so timing tells which usernames exist.
9. Admin and device routes share one router and listener (`S/main.rs:764`): a proxy rule meant for the phones also
   exposes the admin. The SSE stream has no per-device cap.

## 1. Threat model

**Assets.** The **admin session** has full control: live location plus history, lock, ring and wipe, contacts,
setting PINs, **pushing any APK silently** (the catalog is code execution on the kid's phone), backups (the whole DB),
server update and reboot (fixed root actions). A **device token** gives one phone's view. Its policy carries the
override and kid PIN hashes: 4-6 digits under PBKDF2 fall offline in minutes, so **a token is as good as the override
PIN**. It also carries contacts, the Storytel login (when on) and catalog APKs. A token can also *write* fake status
and locations into Find My Device. An **enrollment code** gives a fresh token for its phone and cuts off the real phone.
The **Tailscale auth key** (provisioning settings, QR, phone prefs) makes a new tailnet node, which reaches whatever
the ACL allows: with the default policy, the admin UI too. The **data dir** holds the DB and `data/keys/` (the
Storytel key).

**Adversaries.**
- Internet bots: scanning, credential stuffing, floods.
- A targeted adult (abusive ex-partner) after the child's location: phishing, a stolen parent phone with a session,
  guessing.
- **The kid**: technical, holds the phone, may see the setup QR, has a laptop on the home LAN. He can MITM his own
  LAN, so LAN cleartext is out.
- On-path networks: public Wi-Fi, the carrier.
- The coordination server (Tailscale or Headscale), and the GitHub/GHCR supply chain.

| | (a) tailnet only (default) | (b) all public | (c) phones public, admin tailnet |
|---|---|---|---|
| Internet reaches | nothing | login, PWA, device API | enroll + token-authed device API |
| Tailnet reaches | admin + device API | same | admin + device API |
| Admin rests on | tailnet + ACL + password + TOTP | password + TOTP + limits + step-up (§3.2) | tailnet + ACL/Tailscale-user allowlist + password + TOTP |
| New risk | kid tailnet nodes (modes 1-2, a photographed key) see the admin unless ACL'd | phishing, login brute force/DoS, any web bug is internet-facing | code/token guessing, API floods, a proxy misroute (guarded by §2) |

Recommendation: (c) for families who want phones without Tailscale; (b) is supported, but (c) is safer for the same
reach; (a) stays the default.

## 2. Server listeners

**Routers.** `fn device_routes() -> Router<AppState>` holds the device routes (`main.rs:693-762`) **with their own
limit layers (§3.1)**. `build_admin_router(state, session_layer, cfg)` = today's public, onboarding and admin routes
plus `/static`, plus `device_routes()` unless `ADMIN_DEVICE_API=off`. `build_device_router(state)` = `device_routes()`
+ `/healthz` only: no session layer, no `/static`, and an empty-body 404 fallback. The device router is built only
from that function, so no admin path can reach it by a proxy mistake. `build_router` (used by the tests) stays the
admin router with the device routes, so existing tests are unchanged. `main` serves both under one `try_join!` with
graceful shutdown on SIGTERM. Shutdown also ends the SSE streams (a watch merged into each stream), so neither
`docker stop` nor `update.sh` waits on them. Startup refuses equal bind addresses and logs one exposure line.

| Env | Default (= today) | Meaning |
|---|---|---|
| `BIND_ADDR` | `127.0.0.1:3100` | admin listener |
| `DEVICE_BIND_ADDR` | unset: no second listener | phone listener, e.g. `127.0.0.1:3101` |
| `ADMIN_DEVICE_API` | `on` | admin listener also serves `/api/devices/*` (old URLs); `off` once every phone moved |
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | peers (IPs/CIDRs) whose `X-Forwarded-For` is believed |
| `ADMIN_HOSTS` | unset (any) | `Host` allowlist on the admin listener, else 421 |
| `ADMIN_TAILSCALE_USERS` | unset | admin requires `Tailscale-User-Login` from a trusted peer, in this list |
| `DEPLOY_KIND` | `systemd` (image: `docker`) | §6 |

The admin listener always refuses requests that carry `Tailscale-Funnel-Request`, so a `tailscale funnel` on the admin
port can't expose it. `tailscale serve` strips client-sent `Tailscale-*` headers, sets `X-Forwarded-For` to the
source, and marks Funnel requests with `Tailscale-Funnel-Request: ?1` [verified: `ipn/ipnlocal/serve.go`, main].
`ADMIN_TAILSCALE_USERS` refuses tagged kid nodes and shared nodes even without ACLs, because tagged nodes carry no user
login. A bind on `0.0.0.0`/`::` outside Docker logs a warning.

**TLS: a reverse proxy, never built in.** Built-in TLS would mean ACME, a cert store, renewal, binding 80/443
(`CAP_NET_BIND_SERVICE`) and HTTP/2 tuning inside our binary, for what existing tools already do. The server stays on
loopback (or the container network). Three fronts:
- `tailscale serve`: tailnet, as today;
- **Caddy** (recommended for an own domain): automatic certificates via HTTP-01/TLS-ALPN-01, HTTP/2, sane edge
  defaults, about 40 MB RSS [device: measure on the Pi Zero 2 W];
- **`tailscale funnel`**, for (c) without a domain or port forward: ports 443/8443/10000 only, Tailscale's relays with
  a bandwidth cap, TLS ends on the box.

Caddyfile for (c); (b) adds an `admin.example.com` block proxying to `127.0.0.1:3100`:
```
{
	email parent@example.com
	servers {
		timeouts {
			read_header 10s
		}
	}
}
phones.example.com {
	request_body {
		max_size 1MB
	}
	reverse_proxy 127.0.0.1:3101
}
```
Caddy replaces client `X-Forwarded-*` headers unless `trusted_proxies` is set (never set it on the edge), redacts
`Authorization` in logs, and flushes `text/event-stream` at once. No `encode`: the stream must not be compressed, and
music routes gzip already.

**Trusted proxies.** If the peer is in `TRUSTED_PROXIES`, walk `X-Forwarded-For` from the right, skip trusted
entries, and take the first other one; if the header is malformed, use the peer. Any other peer: the header is
ignored. Limit keys are IPv4 /32 and IPv6 /64. Docker values are in §6.

**SSE through proxies.** Read timeout >= 300 s, no buffering, no compression. Caddy, serve and funnel do this as they
are. nginx needs `proxy_buffering off; proxy_read_timeout 330s; proxy_http_version 1.1;`. Cloudflare's proxy cuts
responses idle for 100 s [belief]: use DNS-only, or `SSE_KEEPALIVE_SECS=90`. In mode 3 the 240 s keepalive is also
the carrier-NAT keepalive. [device] Count drops on mobile data; a per-connection `?keepalive=` is built only if they
show.

**Request limits.**
- Bodies: device listener 512 KiB, enroll 4 KiB; the admin keeps today's per-route limits.
- In-flight requests: device 128, admin 64.
- A 30 s timeout on non-streaming device routes (not on SSE, APK downloads or music files).
- Device API responses default to `Cache-Control: no-store`; the ETag routes set `private, no-cache`.
- Header-read limits are the proxy's job (hyper's default 30 s [belief]).

## 3. Public hardening

### 3.1 Device listener

**Enrollment** (migration: `devices.enrollment_code_hash`, `enrollment_code_kind`; the plaintext column is cleared,
so outstanding codes die).
- One live code per phone, in one of two kinds:
  - **QR code**: 26 chars of base32 (130 bits), only in the QR, valid 30 min;
  - **typed code**: today's 8 chars, valid 15 min.
- Stored as SHA-256 of the normalised code (upper-cased, spaces and dashes stripped). The plaintext exists only on the
  page that made it: the device page's "New code" (POST, shown once) and the QR.
- Consumed atomically: `UPDATE devices SET token_hash=?, enrollment_code_hash=NULL, ... WHERE enrollment_code_hash=?
  AND enrollment_code_expires_at > datetime('now') RETURNING id`.
- Limits:
  - per IP (/64): 10 enroll calls per hour;
  - **typed-code failures server-wide: 20 per hour**, then typed codes get 429 for the rest of the hour (the device
    page says so; event `enroll_typed_paused`). 20 guesses an hour over a 15-min window against 31^8 ≈ 8.5e11 is
    negligible.
  - Failures with a QR-shaped code don't count toward the global cap (130 bits), so a flood can't block QR setup.
- Events: `enroll_failed` (IP, kind) and `device_enrolled` (IP).
- A new code for an enrolled phone stays allowed (the recovery path); the page says it disconnects the phone.

**Tokens.**
- Keep: 256-bit random, SHA-256 at rest, and on the phone in prefs already excluded from backup and device transfer
  (`data_extraction_rules.xml`).
- **Revoke access** (device page, step-up): `token_hash = NULL`, closes the phone's streams, logs an event. The phone
  keeps enforcing its cache and shows "access revoked - enroll again". After 3 consecutive 401s, L1 slows its SSE
  retries to 15 min.
- **No rotation now.** Rotation only shortens a stolen token's life, and a lost rotation response strands the phone
  until someone re-enrolls it by hand. Only a two-phase scheme (old and new accepted until the new one is seen) is
  safe; build it only if the access info below ever shows a token used from two places. Instead, the device page shows
  "last access": when, tailnet or public, and for public the client IP (last 5 distinct, in memory).

**Rate limits** (in-memory `S/limits.rs`: keyed token buckets, at most 10k keys with oldest-first eviction, IPv6 keyed
by /64; no new crate):

| Key | Limit | Over |
|---|---|---|
| whole device listener, before auth | 50 req/s, burst 200 | 429 |
| per IP, requests that **failed** auth | 30 per 10 min | 429 for 10 min, counted after the token lookup, so a revoked phone never blocks its siblings on the same home IP |
| per token | bucket 600, +2/s; 8 in flight | 429 + `Retry-After` (a first music sync is ~400-600 requests) |
| per device SSE streams | 2 | a third closes the oldest (a reconnect after a half-dead stream must win) |

### 3.2 Admin (required for (b), applied in every mode)

1. **Login.**
   - Password step: one argon2 verify at a time (semaphore, queue 4, else 429), with a dummy verify for unknown
     users. Per-IP ban as today (15 failures in 15 min -> 1 h) on the trusted client IP.
   - **Password failures no longer lock the account**: anyone who knows "admin" could otherwise keep the parent out of
     Find My Device.
   - TOTP step (the password is already proven): 5 failures lock the account for 15 min. The counter resets only on a
     complete login (finding 2). The pending state lasts 5 min.
   - Replay: `admin_users.totp_last_step`; only a later step is accepted (also at setup and step-up).
2. **Sessions.**
   - `cycle_id()` after the password and after the TOTP.
   - Cookie `__Host-handy` (Secure, Path=/, no Domain; HttpOnly and Strict kept). The rename logs everyone out once.
   - `admin_users.session_epoch` is stored in the session; "Sign out everywhere" bumps it.
   - Inactivity expiry stays 30 days (`SESSION_INACTIVITY_DAYS` can lower it).
3. **Step-up.** These need a TOTP confirmed in this session within 15 min (`reauth_at`): wipe, delete device, revoke
   access, new enrollment code or QR, Connection settings, catalog add/edit source/upload APK, backup
   download/upload/restore, password and 2FA changes, Update now. POST forms show a code field only when stale; GETs
   (backup download) go through `/auth/confirm?next=<local path>`.
4. **Headers** on every admin response:
   - HSTS `max-age=31536000` (browsers ignore it over http; no includeSubDomains, no preload);
   - `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`, `X-Frame-Options: DENY`;
   - `Cross-Origin-Opener-Policy: same-origin`; `Permissions-Policy` that turns off camera, microphone and
     geolocation;
   - `Cache-Control: no-store` on HTML and JSON (location data); `/static` keeps its caching.
   - CSP phase 1: `default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src
     'self' data: https://tile.openstreetmap.org; connect-src 'self'; frame-ancestors 'none'; base-uri 'none';
     form-action 'self'; object-src 'none'`.
   - Phase 2 (its own step): move the inline JS to `/static` and drop `'unsafe-inline'` from `script-src`.
5. **CSRF**: an origin middleware on the admin listener for unsafe methods.
   - `Sec-Fetch-Site` `same-origin` or `none` passes.
   - Otherwise an `Origin`, when present, must equal the request's own origin (host from `Host`; scheme from a trusted
     proxy's `X-Forwarded-Proto`, else http).
   - With neither header the request passes (non-browser clients have no cookie).
   - No template tokens. GETs stay side-effect free: provisioning becomes a POST (finding 7), and the Tailscale key
     becomes write-only like Storytel.
6. **Passkeys: later** (design 23, once (b) is really used). They are the one defence against real-time phishing
   (password and TOTP relayed), but:
   - the RP ID ties them to one hostname, so moving between ts.net and an own domain means registering again;
   - recovery still needs TOTP;
   - `webauthn-rs` (MPL-2.0, GPL-compatible) is a big dependency.

   In (c), the user's setup, the admin isn't on the internet at all.

## 4. Launcher

### 4.1 Modes (wire values `tsnet`, `tailscale_app`, `direct`)

| | 1 tsnet (today) | 2 Tailscale app | 3 direct |
|---|---|---|---|
| URL | tailnet URL | tailnet URL | public URL |
| Transport | tsnet SOCKS **only** | OS routing through Tailscale's VPN | OS network, HTTPS |
| Always-on VPN | launcher (`KidVpnService`) | `com.tailscale.ipn`, lockdown off (design 03 §3.3) | launcher |
| DNS filter | on-device | tailnet DNS -> server resolver (§4.5) | on-device |
| tsnet runs | yes | only as the design-03 fallback | no |
| Needs | auth key | Tailscale app (catalog) + key via managed config | a public URL |

### 4.2 Choosing, storing and switching: pure `L/server/ConnectionPlan.kt`

An `Endpoint` is `(mode, url)`.
- **Persisted** (prefs, `commit()`): `active`, `previous`, `trial {endpoint, sinceMs, attempts, nextAtMs, lastError}`,
  `lastOkMs` per endpoint, `fallbackSince`.
- **Desired**: from the policy's `connection` (§5), else the QR, else a parent's Settings edit.
- **One client factory**: `ServerConnection.client()/baseUrl()` replaces every reader of `mdm.serverUrl()` and
  `createMdmApi` (`MdmSyncWorker`, `CommandListenerService`, `AppDownloads`, `AppInstallReceiver`, `Provisioning`,
  Settings enroll). The music bridge's fetches use it too.

Rules:
- **R1** If desired == active, do nothing.
- **R2** If desired != active, start a trial of desired; traffic stays on active.
  - Prerequisites: tsnet needs an auth key and tsnet up. tailscale_app follows design 03 §3: Tailscale >= 1.80, config
    pushed, always-on set, VPN up with DNS 100.100.100.100. direct needs nothing.
  - Every trial leaves the old path working. tsnet and direct don't need the VPN, and Tailscale without an exit node
    doesn't capture internet traffic.
- **R3** The probe is an authenticated `GET api/devices/policy` over the trial path that `judgeFresh` accepts.
  - On success, previous = active and active = trial.
  - The old path's extras stay up for 1 h (tsnet kept running, for R5). Its state is never deleted (tsnet dir,
    Tailscale install).
- **R4** Probe failures retry after 1, 2, 5, 10 and 15 min, then pause for 6 h (or until a new policy generation or
  "Sync now"). The phone reports `trial_failed:<reason>`. A failed tailscale_app trial hands always-on back to the
  launcher at once.
- **R5** Fallback: the active path has had only transport failures (connect, TLS, timeout, proxy, 502-504) for 20 min
  **while Android reports a validated network**. A phone that is simply offline never falls back.
  - If fallback is allowed, try previous, then direct (if a public URL is published), then tsnet (if a key exists).
    tailscale_app is never a fallback.
  - The first probe that passes carries the traffic (state `fallback`), and active is re-probed every 15 min.
- **R6** Never drop the token or clear a URL. Never adopt an endpoint that fails `EndpointPolicy`. A 401 is
  revocation, not a path failure: no fallback hunting.
- **R7** A parent's Settings URL edit (behind the PIN) is a trial of (current mode, new URL). A setup QR sets active
  directly.

### 4.3 Transport, cleartext and pinning

- **`L/server/EndpointPolicy.kt`** (pure):
  - http/https only; no userinfo or fragment; https always allowed.
  - http only for tsnet/tailscale_app endpoints on `*.ts.net` names. Headscale names and IP literals need https.
  - Until L3, http to a tailnet IP literal is allowed and reported as `legacy_cleartext`.
  - Debug builds also allow http for direct (emulator `http://10.0.2.2:3100`, smoke tests).
- **Mode-bound transport**:
  - tsnet goes only through the SOCKS proxy; without the proxy the request fails (finding 6).
  - tailscale_app: an OkHttp `Dns` that, for http, accepts only answers in 100.64.0.0/10 or fd7a:115c:a1e0::/48, so
    cleartext rides WireGuard.
  - Every server client sets `followRedirects(false)` and `followSslRedirects(false)`: the API never redirects, so a
    3xx is a misconfiguration and no token follows it.
  - A 429 honours `Retry-After`.
- **Network security config (L3)**:
  - `res/xml/network_security_config.xml`: `base-config cleartextTrafficPermitted="false"`, system trust anchors
    only; `domain-config` for `ts.net` (includeSubdomains) with cleartext allowed.
  - Manifest: `networkSecurityConfig` added, `usesCleartextTraffic` removed.
  - `src/debug/res/xml/` overlay: base cleartext on and user CAs (for mitmproxy).
  - The config can't express 100.64.0.0/10 (domains are names or exact IPs), so IP-literal http URLs stop working in
    L3. L1 moves them first (§4.8). In L3 an endpoint the config blocks counts as failed at once (R5 without the
    20 min).
- **No LAN cleartext.** Anyone on the Wi-Fi, the kid included, could read the token and with it the override PIN.
- **Certificate pinning: against.**
  - Caddy makes a new key at each renewal; Let's Encrypt rotates its intermediates; Caddy falls back to ZeroSSL. So
    key, intermediate and CA pins all break in normal operation.
  - A broken pin cuts the phone off the server that delivers the fixed launcher: a lost phone that needs physical
    re-provisioning.
  - The threat it covers (a misissued certificate for the family's domain) is small. Certificate Transparency
    (crt.sh) and revocable per-phone tokens cover it.

### 4.4 tsnet gating and battery

`TsnetClient.connectFromPreferences` runs only when the plan needs tsnet: active, trial or fallback on tsnet, or mode
2's design-03 fallback. It is closed after R3's grace hour; the crash guard stays. [device] Jelly Star, release build,
design 07 §3 protocol (8 h screen off, mobile data and Wi-Fi). Compare mode 1 with mode 3 (and mode 2 once it
exists): %/h, radio wakeups, our alarms, SSE reconnects, and a ring's latency in Doze. Mode 3 should drop DERP and
control keepalives (~60 s) [inferred].

### 4.5 DNS filtering per mode

- **Modes 1 and 3**: `KidVpnService` as today. New: the active endpoint's host and tsnet's control host are never
  blocked, like `FcmHosts` (design 19 QA #9). A parent's blocklist must not cut the phone off its server.
- **Mode 2**: Android allows one VPN, so `KidVpnService` can't run. **Recommended: the server's own tailnet resolver
  (S6).**
  - Tailnet DNS: the global nameserver is the server's tailnet IP, "Override DNS servers" on.
  - The server binds `DNS_BIND_ADDR=<tailnet IP>:53` (UDP+TCP). It refuses non-tailnet, non-loopback addresses.
  - Answers come from the per-device compiled list (`dns_engine`, already built per phone), keyed by the source tailnet
    IP. WireGuard authenticates that IP; the launcher reports its tailnet IPs.
  - Unknown sources get the global list. Parents' devices: list them as unfiltered on the DNS page, or untick "Use
    Tailscale DNS".
  - Upstream is DoT like the launcher (hickory, MIT/Apache, back as a dependency). Blocked events go to
    `device_dns_events` while the phone's log is on.
  - Win: the PWA's DNS page drives all three modes. AdGuard Home (design 03 §4) stays a documented alternative.
  - Interim until S6: Tailscale DNS -> a public family resolver, or none.
  - Gaps as design 03: Tailscale down (its 10-min tsnet + `KidVpnService` fallback), apps with their own DoH,
    Tailscale's built-in excluded apps.
  - [device] Design 03 §9 checks 1-2: all lookups reach it without an exit node, and the source is the phone's tailnet
    IP.

### 4.6 Setup QR v2 and compatibility

- **QR** (`PROVISIONING_ADMIN_EXTRAS_BUNDLE`): `{"v": 2, "mode": "direct", "server_url": "https://phones.example.com",
  "tailscale_auth_key": "", "enrollment_code": "<26 chars>"}`.
  - `server_url` is the URL for `mode`, so an old launcher (reads `server_url`, `tailscale_auth_key`,
    `enrollment_code`) behaves as before.
  - The key is included only for tsnet/tailscale_app.
  - The other URL comes with the first policy.
  - The PersistableBundle path reads the same keys.
- **Existing prefs** without connection state: active = (tsnet if an auth key is stored, else direct) with the stored
  URL; no previous. Nothing moves until the server publishes something else.
- **Old launcher, new server**: it ignores `connection`. `ADMIN_DEVICE_API=on` keeps its URL working.
- **New launcher, old server**: no `connection`, so no trial (but no no-proxy fallback either).
- **URL moves**: phones on the admin URL (`https://pi.<tailnet>.ts.net` -> 3100) and on IP-literal http URLs move
  when the admin sets the tailnet URL to the phone listener (`https://pi.<tailnet>.ts.net:8444`). Same mode, new URL,
  so R2. Then `ADMIN_DEVICE_API=off`, and only then L3.

## 5. PWA and contract

- **Settings > Connection** (Provisioning's URL/key fields move here; locale and time zone stay):
  - Fields:
    - tailnet URL for phones (today's `server_url`);
    - public URL for phones (https only);
    - Tailscale auth key, write-only ("set (tskey-auth-...abcd)", replace, clear);
    - default mode for new phones.
  - A read-only "how this server listens" block from the env (binds, `ADMIN_DEVICE_API`, trusted proxies, admin
    hosts/users, deploy kind).
  - Warnings:
    - a public URL without a phone listener;
    - `ADMIN_DEVICE_API` still on after every phone moved;
    - (c) without `ADMIN_HOSTS` or `ADMIN_TAILSCALE_USERS`.
  - Behaviour: step-up; a refused save is a 400 with the values kept; a save nudges every phone.
- **Device page, "Connection" card** (`#connection`):
  - Mode radios, each disabled with its reason (no key, no public URL, launcher without `connection_v1`, Tailscale
    app mode not built yet).
  - "If this way fails, try the others" (default on).
  - The phone's report, e.g.:
    - "Connected via Direct (phones.example.com) since 14:02";
    - "Switching to Direct: trying since 14:02, last error: certificate not trusted";
    - "Fallback: using tsnet, Direct failing since 13:40".
  - Also shown: VPN owner and DNS path, the Tailscale app version, last access (§3.1).
  - Buttons: New code, Provision, Revoke access.
  - Warnings: older launcher, `legacy_cleartext`, trial failed, fallback on. The filter card's text follows the mode.
- **Elsewhere**: the Devices list shows a mode badge. The Provision page gets a mode select (POST) and says "valid
  30 min, once". Every change logs an event and nudges.
- **Migration** (next free number; the music work may take 0052 first): `device_policy.connection_mode TEXT NOT NULL
  DEFAULT 'tsnet' CHECK (... IN ('tsnet','tailscale_app','direct'))`, `connection_fallback INTEGER NOT NULL DEFAULT
  1`; `provisioning_settings.public_url`, `default_connection_mode`; `device_status.connection_state_json`.
- **Policy** (always sent; `policy_json_keys_snapshot` and `PolicyResponseCompatTest` in the same commit):
  `"connection": {"mode": "direct", "tailnet_url": "https://pi.tail1234.ts.net:8444"|null, "public_url":
  "https://phones.example.com"|null, "fallback": true}`.
- **Status** `connection_state`, re-serialized through known fields like `lock_state`, capability `connection_v1`:
  `{active: {mode, url}, trial: {mode, url, since_ms, attempts, error}|null, fallback: {mode, since_ms}|null,
  legacy_cleartext, vpn_owner, dns: "launcher_vpn"|"tailnet"|"off", tailnet_ips[<=4], tailscale_app: {version,
  vpn_up}|null, last_ok_ms}`.
  - URLs are cut to 200 chars.
  - Errors are an enum: `dns`, `connect`, `tls`, `timeout`, `proxy`, `http_<code>`, `no_auth_key`, `tsnet_down`,
    `tailscale_missing`, `cleartext_refused`, `unsupported`.

## 6. Docker

- **Image**: `ghcr.io/<owner>/handy-server`, tags `X.Y.Z` and `latest`, `linux/amd64` + `linux/arm64`.
- **Build**: the release job cross-builds static x86_64 and aarch64 musl binaries; the image only COPYs them (no
  compile, no QEMU in the image build).
- **Base**: `gcr.io/distroless/static-debian12:nonroot`, pinned by digest. It provides uid 65532, the CA bundle the
  rustls platform verifier reads, tzdata and `/tmp`.
- **The Pi Zero 2 W keeps the tarball + systemd as its main path.**
```dockerfile
FROM --platform=$BUILDPLATFORM busybox@sha256:<digest> AS dirs
RUN mkdir -p /out/data/keys && chmod 700 /out/data/keys
FROM gcr.io/distroless/static-debian12:nonroot@sha256:<digest>
ARG TARGETARCH
WORKDIR /app
COPY --from=dirs --chown=65532:65532 /out/data /app/data
COPY dist/${TARGETARCH}/kid_phone_server /app/kid_phone_server
COPY dist/static /app/static
ENV BIND_ADDR=0.0.0.0:3100 DEPLOY_KIND=docker
EXPOSE 3100 3101
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["/app/kid_phone_server", "healthcheck"]
ENTRYPOINT ["/app/kid_phone_server"]
```
- **Subcommands**:
  - `healthcheck`: GET `http://127.0.0.1:<admin port>/healthz` with a 3 s timeout. `/healthz` runs `SELECT 1` and
    says only `ok`.
  - `selftest-tls`: one HTTPS GET to api.github.com through the app's reqwest/rustls stack. A missing root store fails
    silently (server CLAUDE.md gotcha; v0.18.5 broke on it), so every built image runs this.
- **Volumes**: `/app/data`, plus `/app/data/keys` as its own volume: the Storytel key stays out of data backups, as
  on the Pi.
- **Container hardening**: read-only rootfs, tmpfs `/tmp`, `cap_drop: [ALL]`, `no-new-privileges`.
- **Config** is env only.
  - `ADMIN_PASSWORD_FILE` is new.
  - No admin and no password: generate one, log it once in a banner, with `must_change_password` (today this panics).
- **`DEPLOY_KIND=docker` in the app**:
  - Updates page: "Version X is out. Create a backup, then pull the new image: `docker compose pull && docker compose
    up -d`". No Update now, no auto-apply, no helper-script lines; the update check is unchanged (same tags).
  - Hidden: the OS, Tailscale, reboot and format-drive cards, the live mirror and the external drive. No
    `update_requested` or schedule files.
  - **Restore**: the PWA writes `data/restore_request` and exits; `restart: unless-stopped` brings the app back, and
    startup swaps the DB before `connect_db`. Same checks as `actions.sh`: name pattern, the zip holds `kidphone.db`, a
    prerestore copy, wal/shm removed.
  - Rollback = the old tag + the pre-update backup (migrations go forward only).
- **Network layouts** (`TRUSTED_PROXIES` for each):

| Layout | Tailnet | TRUSTED_PROXIES | Trade-off |
|---|---|---|---|
| bridge, ports published on host loopback, host `tailscaled`/Caddy | host's node | the bridge gateway (fixed subnet), Caddy's fixed IP | simple; app can't bind the tailnet IP |
| sidecar `tailscale/tailscale`, app `network_mode: service:tailscale`, `TS_SERVE_CONFIG` | own node "handy" | `127.0.0.1` | isolation, NAS-friendly; `/dev/net/tun` + `NET_ADMIN` on the sidecar only |
| `network_mode: host` | host's node | `127.0.0.1` | simplest; no network isolation, binds must be explicit |

- **Port 53**: nothing today. Phase E removed the DNS listener, and `install.sh` cleans the old DNAT rules. S6 in
  Docker needs the sidecar or host layout: bind the tailnet IP:53 with `sysctls:
  net.ipv4.ip_unprivileged_port_start=53` on the netns owner. That is netns-scoped: no root, no DNAT. The bridge
  layout can't, because the tailnet IP isn't in the container.
- **Published ports bypass ufw**: publish the admin on `127.0.0.1` only.

Compose for (c) with host `tailscaled` (the admin goes through the host's `tailscale serve`):
```yaml
services:
  handy:
    image: ghcr.io/palchrb/handy-server:0.23.0
    restart: unless-stopped
    env_file: .env      # DEVICE_BIND_ADDR=0.0.0.0:3101, TRUSTED_PROXIES=172.30.0.1,172.30.0.10, ADMIN_HOSTS=...
    volumes: [handy-data:/app/data, handy-keys:/app/data/keys]
    ports: ["127.0.0.1:3100:3100"]
    read_only: true
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    networks: {edge: {ipv4_address: 172.30.0.20}}
  caddy:
    image: caddy:2@sha256:<digest>
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes: [./Caddyfile:/etc/caddy/Caddyfile:ro, caddy-data:/data]   # reverse_proxy 172.30.0.20:3101
    networks: {edge: {ipv4_address: 172.30.0.10}}
networks: {edge: {ipam: {config: [{subnet: 172.30.0.0/24}]}}}
volumes: {handy-data: {}, handy-keys: {}, caddy-data: {}}
```

**Workflows** (third-party actions pinned by SHA; never `pull_request_target`):
- `server-release.yml`:
  - The build job also builds `x86_64-unknown-linux-musl` and uploads an `image-bins` artifact. The aarch64 tarball
    is unchanged.
  - New job `server-release-image`: `needs: [build, publish]`; `packages: write`, `id-token: write`,
    `attestations: write`.
    1. Buildx pushes `X.Y.Z`.
    2. Smoke: amd64 natively and arm64 under QEMU, each `selftest-tls`, then start and wait for `healthy`;
       `Config.User` is `65532`.
    3. Only then `imagetools create -t latest`.
    4. `attest-build-provenance` (verify with `gh attestation verify oci://...`).
    5. OCI labels: source, `GPL-3.0-or-later`, version, revision.
- `server-ci.yml`: a `server-image` job, only when `server/Dockerfile` or the workflow changed: amd64 musl build,
  `docker build` and the same smoke, no push. The `server-ci` gate job includes it.

## 7. Docs

- `DEPLOY.md` "2. Make it reachable" becomes "Choose how it's reachable", one section per mode:
  - **(a)**:
    - today's `tailscale serve` for the admin;
    - the phone listener `DEVICE_BIND_ADDR=127.0.0.1:3101` + `tailscale serve --bg --https=8444
      http://127.0.0.1:3101`;
    - a tagged auth key (`tag:kid`) and the tailnet policy `tag:kid -> pi:8444` only (design 03 §5's sample, without
      the MollySocket 8443);
    - `ADMIN_TAILSCALE_USERS`.
  - **(c) with an own domain**:
    1. A/AAAA `phones.example.com` -> the home IP (DDNS if it changes).
    2. Router: forward TCP 443 (+80 for HTTP-01) to the box's fixed LAN IP. **CGNAT check**: the router's WAN IP is
       in 100.64.0.0/10, or differs from what an IP-echo site shows -> no port forward possible; use Funnel or a VPS.
       IPv6: allow inbound 443 to the box.
    3. Caddy from its official apt repo, with the Caddyfile above.
    4. `.env`: `DEVICE_BIND_ADDR`, `ADMIN_HOSTS` (Caddy on the same host is `127.0.0.1`: trusted by default).
    5. The public URL in the PWA, then per phone "Direct".
    6. Checks: `curl -sI https://phones.example.com/devices` -> 404; `/healthz` -> ok; `/api/devices/policy` -> 401.
  - **(c) with Funnel**: `tailscale funnel --bg --https=8443 http://127.0.0.1:3101`; the public URL is
    `https://<host>.<tailnet>.ts.net:8443`; Funnel is allowed in the tailnet policy (nodeAttrs).
  - **(b)**: (c) plus `admin.example.com` -> 3100 and `ADMIN_HOSTS`; recommend (c) instead.
- Also in `DEPLOY.md`: the proxy/SSE notes (§2), a Docker section (§6), and "changing exposure" (remove the Caddy
  site; phones fall back to the tailnet if they have it).
- Elsewhere:
  - `install.sh`'s closing message: drop "Tailscale Funnel for a public URL", point to DEPLOY.
  - The `CLAUDE.md`s (root, server, launcher), and `PLAN.md`'s open question.
  - A note atop design 03: "§6, §7 and the AdGuard choice are superseded by 22".

## 8. Steps (each green alone: server `cargo test`/fmt/clippy, launcher `assembleDebug assembleRelease testDebugUnitTest`)

| Step | Content | Check | Parallel / conflicts |
|---|---|---|---|
| **S1** listeners | §2 routers, `DEVICE_BIND_ADDR`, `ADMIN_DEVICE_API`, `/healthz`, Funnel refusal, graceful shutdown ending SSE | router tests (§9); on the Pi: both listeners, the curl matrix | edits only `main.rs`'s bottom and `main()`; agree a slot with the music implementer (who adds routes there) |
| **S2** edge | `TRUSTED_PROXIES`/`client_ip`, `limits.rs`, device layers, SSE cap, `ADMIN_HOSTS`, `ADMIN_TAILSCALE_USERS` | tests; on the Pi: a login failure from a tailnet device logs its 100.x IP, not 127.0.0.1 | after S1; `commands_stream` in `device_api.rs` (music edits that file: small, rebase) |
| **S3** enrollment | §3.1 codes, hashing, atomic consume, limits, events, one-time display | tests | after S2; parallel with S4 in its own worktree (both touch `security.rs`: merge by hand) |
| **S4** admin auth | §3.2 1-5 + provisioning POST + write-only key | tests; on the Pi: login, step-up, the iOS Safari PWA after the cookie rename | after S2 |
| **D1** Docker | §6 Dockerfile, subcommands, `DEPLOY_KIND`, restore marker, password bootstrap, workflows, compose | `server-image` CI job; a `workflow_dispatch` dry run | after S1, parallel with S2-S5 (`main()`, `system_update.rs`, `system_maintenance.rs`, `backups.rs`) |
| **R1** server 0.23.0 | S1-S4 + D1, DEPLOY (a)/(c)/(b) + Docker | release smoke; the Pi on (c) with Caddy or Funnel; old launchers keep syncing | - |
| **S5** contract | §5 migration, policy `connection`, status, Connection page, device card, QR v2, warnings; **launcher DTO + compat test in the same commit** | snapshot + `PolicyResponseCompatTest` | after S3 (QR) |
| **L1** launcher core | §4.2-4.4, §4.6, `EndpointPolicy`, `ServerConnection` at every call site, the 401 path, the DNS host exemption, Settings edit -> trial | unit tests; emulator: direct via 10.0.2.2, a URL move between two server instances, kill one -> R5 | after S5; the music branch's bridge must use `ServerConnection` after a rebase |
| **R2** | server 0.24.0, launcher release | device run (§9) | - |
| **L3** strict NSC | §4.3 config + debug overlay + test | every phone shows a non-legacy endpoint | user go-ahead (Q2) |
| **S6 + L2** mode 2 | §4.5 resolver; design 03 §3 inside `ConnectionPlan` | after design 03 §10.1's spike; update design 03 first | own design check-in |
| **S7** CSP phase 2 | inline JS -> `/static` | `pages_restore_scroll_after_auto_save` + node tests | any time; touches music templates, so coordinate |

## 9. Tests

- **Server, listeners**:
  - The device router answers 404 with an empty body and no `Set-Cookie` for GET and POST of `/`, `/login`,
    `/devices`, `/devices/1`, `/devices/1/command/wipe`, `/settings`, `/backups/x/download`, `/update/trigger`,
    `/security`, `/music`, `/apps`, `/static/style.css`, `/sw.js` and `/auth/verify-2fa`.
  - It serves every device route.
  - With `ADMIN_DEVICE_API=off`, the admin router 404s `/api/devices/*`.
  - Admin refusals: `Tailscale-Funnel-Request` -> refused; a bad `Host` with `ADMIN_HOSTS` -> 421; with
    `ADMIN_TAILSCALE_USERS`, no or another login (or an untrusted peer) -> 403.
- **Server, edge**:
  - `client_ip`: untrusted peer + XFF -> peer; trusted -> the right-most untrusted entry; IPv6 keys /64.
  - Limits: buckets with a fake clock; a revoked token on a shared IP doesn't block a valid one; a third SSE stream
    closes the first.
  - 413 over 512 KiB; `no-store` on the policy.
- **Server, enrollment**: a QR code works once; two racing uses -> one token; expired -> 401; no plaintext in the DB;
  typed codes paused after 20 failures while QR codes still enroll.
- **Server, admin auth**:
  - TOTP failures survive a correct password; the same code twice -> refused; the session id changes at login.
  - CSRF: `Sec-Fetch-Site: cross-site` POST -> 403; a mismatched `Origin` -> 403; same-origin and headerless pass.
  - The headers are on every admin page; step-up redirects or asks, then passes within 15 min; provisioning answers
    no GET with a code.
- **Server, contract and Docker**:
  - Policy `connection` in the snapshot; `connection_state` sanitised (unknown keys dropped, caps);
  - QR v2 (direct: no key; old keys kept);
  - Docker: the restore marker (good zip, bad name, zip without a DB); `DEPLOY_KIND` page text; `/healthz`.
- **Launcher**:
  - `ConnectionPlanTest`: R1-R7, including the 20-min boundary only with a validated network, the fallback order,
    401 = no fallback, the trial backoff and its 6 h pause, an invalid URL never adopted, tsnet without a key ->
    `no_auth_key`, a failed tailscale_app trial returns always-on.
  - `EndpointPolicyTest`: schemes, ts.net, CGNAT and Tailscale IPv6, userinfo, debug vs release.
  - The client factory: no redirects; tsnet without a proxy throws; the tailnet `Dns` refuses public answers for
    http.
  - `ProvisioningExtras` v1/v2; `PolicyResponseCompatTest` with and without `connection`; the status JSON shape.
  - L3: `NetworkSecurityConfigTest` parses the release XML (base cleartext off, no user CAs, only ts.net excepted).
- **Device (Jelly Star)**:
  - tsnet -> direct -> tsnet on mobile data and on Wi-Fi.
  - Stop Caddy: fallback within ~20 min, then back.
  - SSE drops in mode 3 on mobile data; battery and ring latency, mode 1 vs 3 (§4.4).
  - Revoke -> "enroll again", and the cache is still enforced.

## Open questions (recommendation first)

1. **Your exposure**: (c) with an own domain + Caddy on the Pi, or Funnel? Own domain + Caddy if the line has no
   CGNAT; Funnel otherwise. Phones on Direct.
2. **L3, the strict config**: it ends http to IP-literal tailnet URLs. Yes, once every phone reports a name-based or
   https endpoint; keep `*.ts.net` cleartext allowed (it only rides WireGuard).
3. **Mode 2 DNS**: the server's own tailnet resolver (S6) rather than AdGuard Home, so the PWA's lists apply in every
   mode. Mode 2 waits for design 03's spike.
4. **Fallback to the public endpoint** for a phone set to a tailnet mode: on by default, switchable per phone.
5. **Hash enrollment codes** (shown once at creation, a new code to see another): yes.
6. **Token rotation**: no; revoke + "last access" instead. **Passkeys**: later (design 23).
7. **Image name** `ghcr.io/palchrb/handy-server`: yes.
