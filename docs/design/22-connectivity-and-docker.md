# 22 - Connectivity modes, public exposure, Docker image

Status: design, 2026-10-09. Revised after QA's design review and the user's answers (both at the end).

**What the user asked for (2026-10-09).** The admin chooses how the server is exposed:
- (a) tailnet only;
- (b) all public;
- (c) a public endpoint for the phones, with the PWA tailnet-only.

The user runs his own Caddy, pointed at either a tailnet DNS entry or a public DNS record. So the server stays
agnostic: it provides two listeners with safe defaults, and the docs show Caddy for both cases.

Each phone gets one of three connectivity modes: 1 embedded tsnet, 2 the Tailscale app, 3 direct HTTPS. It is set from
the PWA and the setup QR. The server also ships as a multi-arch Docker image. The aim is robust and properly secure,
sized for one family per install, with no multi-tenant machinery.

**What this builds on.**
- Design 03 (Tailscale app mode): its facts table, §3 and §9 stay the reference for mode 2. This note replaces its §6
  server listener, its §7 switch and its AdGuard choice.
- Design 19: SSE is the only nudge.

Paths: `S` = `server/src/`, `L` = `launcher/app/src/main/java/com/kidslauncher/mdm/`. Tags: [verified: ...],
[belief], [device].

## 0. Findings in today's code (fixed in every mode; checked against the code by QA)

1. **XFF trust (latent).** `S/security.rs:77` `client_ip` takes the left-most `X-Forwarded-For` entry from any peer.
   - `tailscale serve`, and Caddy without `trusted_proxies`, replace the header, so today's documented setup can't be
     forged.
   - nginx's `$proxy_add_x_forwarded_for` can be forged, and so can any non-loopback `BIND_ADDR` (the emulator loop).
     That lets a client dodge IP bans, or get someone else's IP banned.
2. **TOTP failure counter (Medium).** `S/handlers/auth.rs:127` resets the counter on a correct password. Whoever knows
   the password gets about 15 TOTP guesses per 75 min per IP, with no limit across IPs.
3. **TOTP replay (Low).** `check_current` doesn't store the last accepted step.
   - Session fixation is **not** exploitable. tower-sessions issues a fresh id for an id that isn't in its store, and
     a session is stored only after a correct password.
   - `cycle_id()` at login is kept as hygiene.
4. **CSRF (Low today).** There is only the cookie's `SameSite=Strict` (the tower-sessions 0.14 default, [verified:
   docs.rs source]).
   - `ts.net` is on the Public Suffix List, so tailnet hosts are cross-site to each other. A same-site sibling only
     matters on an own domain in (b).
   - No security headers. Inline `<script>` in 3 templates, inline handlers in 12.
5. **Enrollment (Low).**
   - No attempt limit. 8 chars of 31 is about 40 bits: about 2e-6 per live code at 1k req/s for 30 min.
   - The code is stored in plain text and lives 30 min.
   - The SELECT and the UPDATE are separate (`device_api.rs:22-62`). A race leaves the losing phone with a dead token.
6. **Token off tsnet (High for an `http://` URL).** `createMdmApi` (`L/server/MdmApi.kt`) and
   `CommandListenerService.buildClient` use the tsnet proxy only when it is up, and otherwise the open network.
   - With `http://100.x`, that is cleartext into carrier CGNAT space (100.64.0.0/10).
   - With https, TLS still protects.
   - The manifest allows cleartext globally.
7. **Provisioning GET (Low-Medium).**
   - The Wi-Fi password goes in the query string, so it ends up in proxy logs.
   - Every page load makes a new code.
   - Neither the QR page nor the settings form sends `Cache-Control`.
   - The Tailscale key is echoed back into the settings form.
8. **argon2 on the async workers.** `verify_password` runs inline on the tokio workers.
   - At most 4 run at once on the Pi (about 76 MiB, so no OOM), but those 4 block every worker. SSE keepalives, the
     device API and the admin all stall.
   - Unknown usernames skip argon2, so timing shows which usernames exist.
9. **One router** serves admin and device routes (`S/main.rs:764`): a proxy rule meant for the phones also exposes the
   admin. The SSE stream has no per-device cap.
10. **QA's additions.**
    - (a) A password change or a 2FA reset leaves every other session alive.
    - (b) `admin_users.totp_secret` is plaintext in every backup.
    - (c) `security_events` is never pruned.
    - (d) Deleting a device leaves its SSE stream open.
    - (e) `pending_admin_id` never expires.

## 1. Threat model

**Assets.**
- **Admin session.** Full control: live location and history, lock, ring and wipe, contacts, PINs, backups (the whole
  DB), server update and reboot (fixed root actions). It can also **push any APK silently**: the catalog is code
  execution on the kid's phone.
- **Device token.** One phone's view.
  - Its policy carries the override and kid PIN hashes. 4-6 digits under PBKDF2 fall offline in minutes, so **a token
    is as good as the override PIN**.
  - It also carries the contacts, the Storytel login (when on) and the catalog APKs.
  - It can *write* fake status and fake locations into Find My Device.
- **Enrollment code.** Gives a fresh token for its phone, and cuts off the real phone.
- **Tailscale auth key** (in provisioning settings, the QR and the phone's prefs). Makes a new tailnet node that reaches
  whatever the ACL allows.
- **Data dir.** The DB, and `data/keys/` (the Storytel key).

**Adversaries.**
- Internet bots: scanners, credential stuffing, floods.
- A targeted adult (for example an abusive ex-partner) after the child's location: phishing, a stolen parent phone with
  a live session, guessing.
- **The kid.** Technical, holds the phone, may see the setup QR, and has a laptop on the home LAN. He can MITM his own
  LAN, so LAN cleartext is out.
- On-path networks: public Wi-Fi, the carrier.
- The coordination server (Tailscale or Headscale), and the GitHub/GHCR supply chain.

| | (a) tailnet only (default) | (b) all public (`ADMIN_PUBLIC=on`) | (c) phones public, admin tailnet |
|---|---|---|---|
| Internet reaches | nothing | login, PWA, device API | enroll + token-authed device API |
| Tailnet reaches | admin + phone endpoint | same | admin + phone endpoint |
| Admin rests on | client-IP gate (§2) + ACL + password + TOTP | **password + TOTP alone**, plus login limits | as (a) |
| New risk | kid tailnet nodes reach the admin unless the ACL keeps them on the phone port | phishing, brute force, any web bug faces the internet | code and token guessing, API floods |

The server never needs to know which mode it is in:
- it binds loopback;
- the admin listener answers only loopback and tailnet clients unless `ADMIN_PUBLIC=on`;
- the phone listener carries only the device API.

(c) gives phones without Tailscale the same reach as (b), without putting the PWA on the internet.

## 2. Server listeners

**Routers.**
- **S0, a mechanical commit merged first:** the device routes (`main.rs:693-762`) move to `S/device_routes.rs`, as
  `pub fn device_routes() -> Router<AppState>`, with their limit layers (§3.1). A test asserts that `main.rs` contains
  no `"/api/devices` literal.
- `build_admin_router(state, session_layer, cfg)` = today's public, onboarding and admin routes, plus `/static`, plus
  `device_routes()` unless `ADMIN_DEVICE_API=off`, plus the admin gate below.
- `build_device_router(state)` = `device_routes()` plus `/healthz`. No session layer, no `/static`, and an empty-body
  404 fallback.
- `build_router` (the tests) stays the admin router with the device routes.
- `main` serves both listeners under one `try_join!`, with graceful shutdown on SIGTERM. Shutdown also ends the SSE
  streams (a watch merged into each one).
- Startup refuses two equal bind addresses and logs one exposure line.

| Env | Default (= today) | Meaning |
|---|---|---|
| `BIND_ADDR` | `127.0.0.1:3100` | admin listener |
| `DEVICE_BIND_ADDR` | unset: no second listener | phone listener, e.g. `127.0.0.1:3101` |
| `ADMIN_DEVICE_API` | `on` | the admin listener also serves `/api/devices/*` (old URLs); `off` once every phone has moved |
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | peers (IPs or CIDRs) whose `X-Forwarded-For` is believed |
| `ADMIN_PUBLIC` | `off` | (b): the admin also answers non-tailnet clients |
| `ADMIN_TAILSCALE_USERS` | unset | only behind `tailscale serve`: requires `Tailscale-User-Login` in this list |
| `SESSION_INACTIVITY_DAYS` | `30` | admin session lifetime without use |
| `DEPLOY_KIND` | `systemd` (the image: `docker`) | §6 |

**Admin gate (on by default).**
- The admin listener serves a request only when the resolved client IP is loopback or tailnet (100.64.0.0/10,
  fd7a:115c:a1e0::/48), or when it comes from a trusted peer that sent no XFF (local tools). The client IP comes from
  the walk under "Trusted proxies" below.
- Everything else gets 403 and the event `admin_refused` (throttled, §3.2).
- 100.64.0.0/10 is also carrier CGNAT space. Such a client could only arrive through a port forward on a CGNAT line,
  where public exposure doesn't work in the first place.
- `ADMIN_PUBLIC=on` turns the gate off for (b). The PWA then rests on password and TOTP alone: the Connection page and
  DEPLOY say so.
- Second check: requests carrying `Tailscale-Funnel-Request` are refused. Whether Funnel's XFF carries the real
  internet client is [belief].
- `Tailscale-User-Login` counts only when the resolved client IP is a tailnet IP. `tailscale serve` sets it and strips
  any client-sent copy [verified: `ipn/ipnlocal/serve.go`]. Every Caddy site in DEPLOY strips it
  (`header_up -Tailscale-User-Login`), so `ADMIN_TAILSCALE_USERS` fails closed behind Caddy.
- `/healthz` skips every guard.

**TLS is at the admin's own reverse proxy; the server never terminates TLS.** Built-in TLS would mean ACME, a cert
store, renewal, binding 80/443 and HTTP/2 tuning inside our binary, for what Caddy already does. DEPLOY shows Caddy for
both of the user's cases:
- **A public DNS record.** Caddy's automatic HTTPS (HTTP-01 or TLS-ALPN-01; ports 443 and 80 forwarded).
- **A tailnet DNS entry.** The site binds the tailnet IP only. The certificate is either:
  - for a `*.ts.net` name, fetched from the local `tailscaled` (Caddy does this by itself; tailscaled needs
    `TS_PERMIT_CERT_UID=caddy`) [verify]; or
  - for an own domain pointing at the tailnet IP, via DNS-01 (a Caddy build with the DNS provider's module).
- **Port split for the ACL.** Tailnet ACLs filter by IP and port, not by hostname. So the phones' tailnet site gets its
  own port (e.g. 8444), and the ACL lets `tag:kid` reach only that port.
- **Alternatives**, both documented: `tailscale serve` (today) and `tailscale funnel` (for a line behind CGNAT).

```
{
	email parent@example.com
	servers {
		timeouts {
			read_header 10s
		}
	}
}
phones.example.com {                     # public DNS record -> phone listener only
	request_body {
		max_size 1MB
	}
	reverse_proxy 127.0.0.1:3101 {
		header_up -Tailscale-User-Login
	}
}
pi.tail1234.ts.net {                     # tailnet entry -> PWA, tailnet IP only
	bind 100.101.102.103
	reverse_proxy 127.0.0.1:3100 {
		header_up -Tailscale-User-Login
	}
}
pi.tail1234.ts.net:8444 {                # tailnet entry -> phones (mode 1), own port for the ACL
	bind 100.101.102.103
	reverse_proxy 127.0.0.1:3101 {
		header_up -Tailscale-User-Login
	}
}
```

How Caddy behaves here:
- It replaces client `X-Forwarded-*` headers (never set `trusted_proxies` on it).
- It redacts `Authorization` in its logs.
- It flushes `text/event-stream` at once.
- Use no `encode` directive: the stream must not be compressed, and the music routes already gzip.

**Trusted proxies.**
- If the peer is in `TRUSTED_PROXIES`: walk `X-Forwarded-For` from the right, skip trusted entries, and take the first
  other one. A malformed header means the peer itself.
- Any other peer: the header is ignored.
- Limit keys are IPv4 /32 and IPv6 /64.

**SSE through proxies.**
- The proxy needs a read timeout of at least 300 s, no buffering and no compression. Caddy and `tailscale serve` do
  this as they are.
- nginx needs `proxy_buffering off; proxy_read_timeout 330s; proxy_http_version 1.1;`.
- Cloudflare's proxy cuts responses that are idle for 100 s [belief]. Use DNS-only, or `SSE_KEEPALIVE_SECS=90`.
- In mode 3, the 240 s keepalive also keeps the carrier's NAT mapping open. [device] Count stream drops on mobile data.
  A per-connection `?keepalive=` is built only if the drops show up.

**Request limits.**
- Bodies: 512 KiB on the device listener, 4 KiB on enroll. The admin keeps today's per-route limits.
- In flight: 512 on the device listener (a memory guard only), 8 on enroll.
- A 30 s timeout on non-streaming device routes. SSE, APK downloads and music files are exempt.
- Device API responses default to `Cache-Control: no-store`. The ETag routes set `private, no-cache`.
- Device API responses also carry `X-Content-Type-Options: nosniff` and `Content-Security-Policy: default-src 'none';
  sandbox`. On the tailnet, the phone site shares the admin's hostname, and cookies aren't scoped by port.

## 3. Public hardening

### 3.1 Device listener

**Enrollment** (migration: `devices.enrollment_code_hash` and `enrollment_code_kind`; the plaintext column is cleared,
so codes that are still open stop working).
- **One live code per phone**, of one of two kinds:
  - QR code: 26 chars of base32 (130 bits), only in the QR, valid 30 min;
  - typed code: today's 8 chars, valid 15 min.
- **Storage.** Only the SHA-256 of the normalised code is stored (upper-cased, spaces and dashes stripped). The
  plaintext exists only on the page that made it: the device page's "New code" (a POST, shown once) and the QR.
- **Atomic use.** `UPDATE devices SET token_hash=?, enrollment_code_hash=NULL, ... WHERE enrollment_code_hash=? AND
  enrollment_code_expires_at > datetime('now') RETURNING id`.
- **Limits.**
  - QR-shaped codes are always checked: one SHA-256 and one lookup, bounded only by the in-flight cap of 8.
  - Typed-shaped failures count toward the per-IP failure bucket below.
  - While a typed code is live, 20 typed-shaped failures server-wide pause typed codes for the rest of that hour.
    During the pause they get 429, the device page says so and offers the QR, and the event `enroll_typed_paused` is
    logged.
  - With no typed code live, typed attempts fail at once and don't count: there is nothing to guess.
- **Events.** `enroll_failed` (throttled, §3.2) and `device_enrolled` (with the IP).
- **Re-enrolling.** A new code for an enrolled phone stays allowed: it's the recovery path. The page says it
  disconnects the phone.

**Tokens.**
- Keep today's tokens: 256-bit random, SHA-256 at rest. On the phone they sit in prefs that are already excluded from
  backup and device transfer (`data_extraction_rules.xml`).
- An in-memory `token_hash -> device_id` index is rebuilt at start and after every write to `token_hash` (enroll,
  revoke, delete). A lookup never touches the DB; a failed one costs one SHA-256 and one hash probe.
- **Revoke access** (device page, with a confirm):
  - sets `token_hash = NULL`;
  - closes the phone's streams (deleting a device closes them too, QA d);
  - logs an event.

  The phone keeps enforcing its cache and shows "access revoked - enroll again" (L1, §4.2 R7).
- **No rotation.** Rotation only shortens a stolen token's life, and a lost rotation response strands the phone until
  someone re-enrolls it by hand. Instead, the device page shows "last access": when, tailnet or public, and for public
  the last 5 distinct client IPs (kept in memory).

**Rate limits** (`S/limits.rs`, two key kinds plus the SSE cap; buckets capped at 10k keys with oldest-first eviction):

| Key | Limit | Over the limit |
|---|---|---|
| valid token | bucket 600, refilling at 2/s; sized again from the music implementer's measured first sync | 429 + `Retry-After` |
| valid token, in flight | SSE and file/range downloads: 4; everything else: 8, except `policy`, `status` and `command-result`, which are always admitted (the bucket still applies) | 429 + `Retry-After` |
| IP /64, failed requests only (bad or missing token, typed-shaped enroll failures) | 30 per 10 min | 429 for 10 min. **A valid token never touches this bucket** |
| SSE streams per device | 2 | a third closes the oldest |

There is no global bucket before auth. Startup and the Connection page warn when every public request resolves to one
private IP: an untrusted proxy, or Docker hiding the client.

### 3.2 Admin (all modes; no step-up)

1. **Login.**
   - **Password step.**
     - argon2 runs in `spawn_blocking`, behind a semaphore: 1 at a time, a queue of 4, else 429.
     - A dummy verify for unknown users.
     - The per-IP ban as today, on the resolved client IP.
     - Password failures don't lock the account. Otherwise anyone who knows "admin" could lock the parent out of Find
       My Device.
   - **TOTP step.**
     - Failures count, and only a complete login resets the counter.
     - 5 failures lock the account for 15 min. The lock doubles each time, up to 24 h, and a lock running out doesn't
       reset the counter.
     - After a complete login, a banner: "N failed 2FA attempts since your last login (IPs ...): your password is
       known - change it".
     - The pending state lasts 5 min (QA e).
     - Replay: `admin_users.totp_last_step`; only a later step is accepted (at setup too).
2. **Sessions.**
   - `cycle_id()` after the password and after the TOTP.
   - The cookie is `__Host-handy` (Secure, Path=/, no Domain; HttpOnly and Strict kept). With `INSECURE_COOKIES` it is
     a plain `handy`, so the emulator loop can still log in. The rename logs everyone out once.
   - `admin_users.session_epoch` is stored in the session. A password change, a 2FA reset and "Sign out everywhere"
     each bump it (QA a).
   - `SESSION_INACTIVITY_DAYS`: DEPLOY suggests 7 for (b).
3. **Recovery.** `kid_phone_server reset-2fa <user>` clears the TOTP, the lock and its counter, and bumps the epoch.
   - Docker: `docker compose exec handy /app/kid_phone_server reset-2fa admin`.
   - Pi: `cd /opt/kid-phone-server && sudo -u kidphone ./kid_phone_server reset-2fa admin`.
4. **Headers** on every admin response:
   - HSTS `max-age=31536000` (browsers ignore it over http; no includeSubDomains, no preload);
   - `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`, `X-Frame-Options: DENY`;
   - `Cross-Origin-Opener-Policy: same-origin`, and a `Permissions-Policy` that turns off camera, microphone and
     geolocation;
   - `Cache-Control: private, no-cache` on HTML, which keeps the back-forward cache and scroll restore. `no-store` on
     location JSON, backups, the QR page, the provisioning settings, and the account and 2FA pages.
   - CSP phase 1: `default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src
     'self' data: https://tile.openstreetmap.org; connect-src 'self'; frame-ancestors 'none'; base-uri 'none';
     form-action 'self'; object-src 'none'`.
   - Phase 2 (S7): the inline JS moves to `/static`, and `script-src` drops `'unsafe-inline'`.
5. **CSRF.** A middleware on the admin listener for unsafe methods:
   - `Sec-Fetch-Site` of `same-origin` or `none` passes;
   - otherwise an `Origin`, when present, must match the `Host` header on host[:port] (the scheme isn't compared,
     because a proxy may not send `X-Forwarded-Proto`);
   - with neither header, the request passes: non-browser clients have no cookie.
6. **Provisioning** becomes a POST, so the Wi-Fi password stays out of URLs. The Tailscale key becomes write-only, like
   the Storytel login.
7. **Audit log.** `security_events` is pruned after 180 days (`retention::prune`). `enroll_failed`, `admin_refused` and
   failed device auth are written at most once per key per minute, with a count (QA c).
8. **The TOTP secret stays in the DB** (QA b). A backup already holds the argon2 hash, every location and the PIN
   hashes. Sealing only the secret would tie every login to `data/keys/`. DEPLOY says to keep backups and volume
   snapshots as safe as the admin password.
9. **No step-up** (user, 2026-10-09): the PWA is mainly tailnet-only, and password plus mandatory TOTP stays. A stolen
   parent phone is the phone's lock screen's job. **Passkeys come later** (design 23, if (b) is ever really used).

## 4. Launcher

### 4.1 Modes (wire values `tsnet`, `tailscale_app`, `direct`)

| | 1 tsnet (today) | 2 Tailscale app (**L2, later**) | 3 direct |
|---|---|---|---|
| URL (always https for a new endpoint) | tailnet URL | tailnet URL | public URL |
| Transport | tsnet SOCKS **only** | OS routing through Tailscale's VPN | the OS network |
| Always-on VPN | the launcher (`KidVpnService`) | `com.tailscale.ipn`, lockdown off (design 03 §3.3) | the launcher |
| DNS filter | on the phone | tailnet DNS -> the server's resolver (§4.5) | on the phone |
| tsnet runs | yes | only as design 03's fallback | no |
| Needs | auth key | Tailscale app (catalog) + key via managed config | a public URL |

Until L2, only `tsnet` and `direct` can be chosen. The DB's CHECK already lists `tailscale_app`, because changing a
SQLite CHECK later means rebuilding the table.

### 4.2 Choosing, storing and switching: pure `L/server/ConnectionPlan.kt`

**State.**
- An `Endpoint` is `(mode, url)`.
- Persisted in prefs with `commit()`:
  - `active`;
  - `previous`, kept for R3's grace hour only;
  - `trial {endpoint, sinceMs, attempts, nextAtMs, lastError}`;
  - `lastOkMs` per endpoint;
  - the fallback state;
  - per published candidate, when it was "last proven".
- The desired endpoint comes from the policy's `connection` (§5), else the QR, else a parent's Settings edit.

**`ServerConnection`**: one client factory for every server request.
- It replaces every reader of `mdm.serverUrl()` and `createMdmApi`: `MdmSyncWorker`, `CommandListenerService`,
  `AppDownloads`, `AppInstallReceiver`, `Provisioning` and the Settings enroll.
- **The token is bound to its origin.** Each endpoint's client attaches `Authorization` only when the request's
  scheme://host:port equals that endpoint's. Relative `@Url` paths resolve to it.
- External media (NRK, podcast feeds, covers) gets its own client without the interceptor.
- The music bridge's server fetches go through `ServerConnection`.

**Rules.**
- **R1** desired == active: nothing to do.
- **R2** desired != active: start a trial of the desired endpoint. Traffic stays on active.
  - Prerequisites: tsnet needs an auth key and tsnet up; direct needs nothing.
  - Before L2, a desired `tailscale_app` gives `trial_failed:unsupported`.
- **R3** The probe is an authenticated `GET api/devices/policy` over the trial path that `judgeFresh` accepts.
  - On success: previous = active, and active = the trial endpoint.
  - The old path stays up for 1 h (tsnet keeps running). Its state is never deleted (the tsnet dir).
- **R4** Failed probes retry after 1, 2, 5, 10 and 15 min, then pause for 6 h (or until a new policy generation or
  "Sync now"). The phone reports `trial_failed:<reason>`.
- **R5 Fallback.** The trigger is 20 min with only transport failures on active, **while Android reports a validated
  network**. A phone that is simply offline never falls back. Transport failures are connect, `dns`, TLS, timeout,
  proxy, and 502-504.
  - The candidates are **only what the current policy publishes**: `public_url` (direct), and `tailnet_url` through
    tsnet if a key exists. Never a remembered URL.
  - The first candidate that probes OK carries the traffic. Active is re-probed every 15 min.
  - An unused candidate is probed once a week and reported as "last proven", so a dead fallback shows before it is
    needed.
- **R6** Never drop the token or clear a URL, and never adopt an endpoint that fails `EndpointPolicy`. A URL the server
  stops publishing is forgotten at the next accepted policy (that includes `previous` after the grace hour).
- **R7 A 401 is checked.** It triggers one probe of the other published endpoints.
  - If another endpoint answers 2xx, the active URL now reaches some other service: count it as a path failure
    (`http_401`).
  - A 401 from every endpoint means revoked: SSE then retries every 15 min, and Settings shows "access revoked".
- **R8** A parent's Settings URL edit (behind the PIN) is a trial of (current mode, new URL). A setup QR sets active
  directly.

### 4.3 Transport, cleartext and pinning

- **`L/server/EndpointPolicy.kt`** (pure):
  - Schemes are http or https; no userinfo or fragment.
  - **Every newly adopted endpoint is https.** A plain http one is accepted only as a migrated `legacy` endpoint
    (§4.6), and never newly adopted.
  - Debug builds also adopt http for direct, for the emulator's `http://10.0.2.2:3100` and the smoke tests.
- **Mode-bound transport.**
  - tsnet requests go only through the SOCKS proxy. Without the proxy they fail (finding 6).
  - Every server client sets `followRedirects(false)` and `followSslRedirects(false)`: the API never redirects, so no
    token follows a 3xx.
  - A 429 honours `Retry-After`.
- **Legacy endpoints.** A legacy `http://` URL stays in use until the server publishes an https one that passes, and
  the PWA warns about it:
  - on a phone with a tsnet key, only through tsnet;
  - on a phone without a key (dev setups), as today. That is the only cleartext left on the OS network, and only where
    it exists today.
- **Network security config (L3).** `res/xml/network_security_config.xml`:
  - `base-config cleartextTrafficPermitted="false"`, system trust anchors only, **no exceptions**;
  - in the manifest, `networkSecurityConfig` replaces `usesCleartextTraffic`;
  - a `src/debug/res/xml/` overlay turns cleartext and user CAs back on (for mitmproxy).

  L3 ships when the PWA shows no phone left on a legacy endpoint.
- **No LAN cleartext.** Anyone on the Wi-Fi, the kid included, could read the token and with it the override PIN.
- **Certificate pinning: against.**
  - Caddy makes a new key at each renewal, Let's Encrypt rotates its intermediates, and Caddy falls back to ZeroSSL.
  - So a pin breaks in normal operation, and a broken pin cuts the phone off the very server that would ship the fix.
  - The threat a pin covers (a misissued certificate for the family's domain) is narrowed instead by a CAA record
    (DEPLOY) and by revocable per-phone tokens.

### 4.4 tsnet gating and battery

`TsnetClient.connectFromPreferences` runs only when the plan needs tsnet:
- active, trial or fallback on tsnet;
- or, once L2 exists, mode 2's design-03 fallback.

It is closed after R3's grace hour; the crash guard stays.

[device] Jelly Star, release build, the design 07 §3 protocol (8 h with the screen off, on mobile data and on Wi-Fi):
- Compare mode 1 with mode 3: %/h, radio wakeups, our alarms, SSE reconnects, and a ring's latency in Doze.
- Mode 3 should drop the DERP and control keepalives (about every 60 s) [inferred].

### 4.5 DNS filtering per mode

- **Modes 1 and 3.** `KidVpnService` as today. New: the active endpoint's host and tsnet's control host are never
  blocked, like `FcmHosts` (design 19 QA #9), so a parent's blocklist can't cut the phone off its server.
- **Mode 2 (S6 + L2).** Android allows one VPN, so `KidVpnService` can't run. **The server gets its own tailnet
  resolver.**
  - Tailnet DNS setup: the global nameserver is the server's tailnet IP, with "Override DNS servers" on.
  - `DNS_BIND_ADDR=<tailnet IP>:53` (UDP and TCP). It refuses non-tailnet, non-loopback addresses.
  - Answers come from the per-device compiled list (`dns_engine`). The list is keyed by **the source tailnet IP seen
    on the phone's authenticated tailnet requests** (the resolved client IP on the phone listener), not by what the
    phone reports.
  - Unknown sources get the global list. Parents' devices are listed as unfiltered on the DNS page, or untick "Use
    Tailscale DNS".
  - Upstream is DoT, as on the phone (hickory, MIT/Apache).
  - Blocked events go to `device_dns_events` while the phone's log is on.
  - Docker: the sidecar layout with kernel TUN (§6).
  - AdGuard Home (design 03 §4) stays a documented alternative.
  - Gaps, as in design 03:
    - Tailscale down: its 10-min fallback to tsnet + `KidVpnService`;
    - apps with their own DoH;
    - Tailscale's built-in excluded apps.
  - [device] Design 03 §9 checks 1-2 come first.

### 4.6 Setup QR v2 and compatibility

- **QR** (`PROVISIONING_ADMIN_EXTRAS_BUNDLE`):
  `{"v": "2", "mode": "direct", "server_url": "https://phones.example.com", "tailscale_auth_key": "",
  "enrollment_code": "<26 chars>"}`.
  - The values are strings: they are the safe type in the extras bundle [belief].
  - `server_url` is the URL for `mode`. A launcher that doesn't know `mode` treats the QR as v1 (`server_url` only).
  - The key is included only for tsnet.
  - The other URL arrives with the first policy.
- **Existing prefs** without connection state: active = tsnet if an auth key is stored, else direct, with the stored
  URL. An endpoint that fails `EndpointPolicy` becomes `legacy` (§4.3). Nothing moves until the server publishes
  something else.
- **Old launcher, new server.** It ignores `connection`, and `ADMIN_DEVICE_API=on` keeps its URL working.
- **New launcher, old server.** No `connection` key, so no trial (but no no-proxy fallback either).
- **Moving URLs.**
  - Phones on the admin URL, or on an `http://100.x` URL, move when the admin sets the tailnet URL to the phone site
    (`https://pi.<tailnet>.ts.net:8444`). Same mode, new URL, so R2 applies.
  - Then set `ADMIN_DEVICE_API=off`, and only then ship L3.

## 5. PWA and contract

- **Settings > Connection.** The URL and key fields move here from Settings > Provisioning; locale and time zone stay.
  - **Fields:**
    - tailnet URL for phones (today's `server_url`);
    - public URL for phones;
    - Tailscale auth key, write-only ("set (tskey-auth-...abcd)", replace, clear);
    - default mode for new phones (tsnet or direct until L2).
  - Both URLs must be https. A refused value gets a 400, with the values kept and a hint.
  - A read-only "how this server listens" block comes from the env: binds, `ADMIN_DEVICE_API`, `ADMIN_PUBLIC`, trusted
    proxies, Tailscale users, deploy kind.
  - **Warnings:**
    - a public URL without a phone listener;
    - `ADMIN_DEVICE_API` still on after every phone has moved;
    - `ADMIN_PUBLIC` on ("the PWA rests on password + TOTP alone");
    - every public request coming from one private IP (§3.1).
  - Saving nudges every phone.
- **Device page, "Connection" card** (`#connection`).
  - Mode radios, each disabled with its reason (no key, no public URL, a launcher without `connection_v1`), plus "If
    this way fails, try the others" (on by default).
  - **The phone's report**, for example:
    - "Connected via Direct (phones.example.com) since 14:02";
    - "Switching to Direct: trying since 14:02, last error: certificate not trusted";
    - "Fallback: using tsnet, Direct failing since 13:40";
    - "Fallback last proven: 3 Oct".
  - **Also shown:** last access (§3.1).
  - **Buttons:** New code, Provision, Revoke access.
  - **Warnings:** an older launcher, a `legacy` endpoint, a failed trial, fallback on.
  - **The filter card's text** follows the mode.
- **Elsewhere.**
  - The TOTP-failure banner (§3.2).
  - The Devices list shows a mode badge.
  - The Provision page gets a mode select (a POST) and says "valid 30 min, once".
  - Every change logs an event and nudges the phone.
- **Migrations** (the next free numbers; the music work may take 0052 first):
  - `device_policy.connection_mode TEXT NOT NULL DEFAULT 'tsnet' CHECK (... IN ('tsnet','tailscale_app','direct'))`
    and `connection_fallback INTEGER NOT NULL DEFAULT 1`;
  - `provisioning_settings.public_url` and `default_connection_mode`;
  - `device_status.connection_state_json`;
  - `admin_users.totp_last_step`, `lock_count` and `session_epoch` (S4).
- **Policy**, always sent. `policy_json_keys_snapshot` and `PolicyResponseCompatTest` change in the same commit:
  `"connection": {"mode": "direct", "tailnet_url": "https://pi.tail1234.ts.net:8444"|null, "public_url":
  "https://phones.example.com"|null, "fallback": true}`.
- **Status** `connection_state`, re-serialized through its known fields like `lock_state`, with capability
  `connection_v1`:
  `{active: {mode, url}, trial: {mode, url, since_ms, attempts, error}|null, fallback: {mode, since_ms}|null, legacy,
  candidates: [{mode, last_proven_ms}], last_ok_ms}`.
  - URLs are cut to 200 chars.
  - Errors are an enum: `dns`, `connect`, `tls`, `timeout`, `proxy`, `http_<code>`, `no_auth_key`, `tsnet_down`,
    `cleartext_refused`, `unsupported`.
  - Mode 2's fields come with L2.

## 6. Docker

**The image.**
- `ghcr.io/<owner>/handy-server`, tags `X.Y.Z` and `latest`, for `linux/amd64` and `linux/arm64`.
- The release job cross-builds static musl binaries for x86_64 and aarch64. The image only COPYs them: no compiler and
  no QEMU in the image build.
- The base is `gcr.io/distroless/static-debian12:nonroot`, pinned by digest. It has uid 65532, the CA bundle the rustls
  platform verifier reads, tzdata, and `/tmp`.
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
ENV BIND_ADDR=127.0.0.1:3100 DEPLOY_KIND=docker
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["/app/kid_phone_server", "healthcheck"]
ENTRYPOINT ["/app/kid_phone_server"]
```

**Subcommands.**
- `healthcheck`: GET `/healthz` on `BIND_ADDR`'s port, with a 3 s timeout. It dials `BIND_ADDR`'s host, mapping
  `0.0.0.0` to 127.0.0.1 and `::` to ::1. `/healthz` runs `SELECT 1`, says only `ok`, and skips every guard.
- `selftest-tls`: one HTTPS request to api.github.com through the app's reqwest/rustls stack. Any HTTP status passes
  (shared runners get rate-limited); only a failed TLS handshake or certificate check fails it. A missing root store
  fails silently in the app (server CLAUDE.md gotcha; v0.18.5 broke on it), so every built image runs this.
- `reset-2fa <user>` (§3.2).

**Startup** (every deploy kind):
- `data/` not writable by the process -> a clear error: "data/ not writable by uid 65532". A bind mount isn't seeded
  from the image; only a named volume is.
- With migrations pending, it first runs `VACUUM INTO data/backups/pre-migrate-<from>-<to>.db` (keeps 3). That is the
  pre-update backup a rollback needs.
- No admin and no password: it generates one into `data/initial-admin-password` (0600, `must_change_password`), and
  the log names the path, not the password. Today this case panics. `ADMIN_PASSWORD_FILE` is also accepted.

**Volumes and hardening.**
- `/app/data`, plus `/app/data/keys` as its own volume, so the Storytel key stays out of data backups, as on the Pi.
- A read-only rootfs, a tmpfs `/tmp`, `cap_drop: [ALL]` and `no-new-privileges`.
- `TZ` set in `.env`: `chrono::Local` drives the backup and update schedules, and distroless is UTC.

**`DEPLOY_KIND=docker` in the app.**
- The Updates page says: "Version X is out. Set `HANDY_VERSION=X` in `.env`, then `docker compose pull && docker
  compose up -d` (a pre-migration backup is taken by itself)".
  - It shows no Update now, no auto-apply and no helper-script lines.
  - The update check itself is unchanged: same tags.
- Hidden: the OS, Tailscale, reboot and format-drive cards, the live mirror and the external drive. No
  `update_requested` or schedule files are written.
- **Restore.**
  1. The PWA writes `data/restore_request` and exits non-zero; the restart policy brings it back.
  2. Startup reads the marker and deletes it before swapping the DB. It runs the same checks as `actions.sh` (name
     pattern, the zip holds `kidphone.db`, a prerestore copy, wal/shm removed).
  3. If `connect_db` fails after the swap, the prerestore copy goes back: there is no shell to break a restart loop.
- **Rollback.**
  1. Set the old `HANDY_VERSION`.
  2. Run `docker run --rm -v handy-data:/data busybox sh -c 'cp /data/backups/pre-migrate-<...>.db /data/kidphone.db
     && rm -f /data/kidphone.db-wal /data/kidphone.db-shm'`.
  3. `up -d`.

**Recommended layout: `network_mode: host`, with the host's `tailscaled` and Caddy.**
- `TRUSTED_PROXIES` keeps its default.
- docker-proxy never hides the client IP.
- The binds stay on 127.0.0.1.

```yaml
services:
  handy:
    image: ghcr.io/palchrb/handy-server:${HANDY_VERSION}
    restart: unless-stopped
    network_mode: host
    env_file: .env      # HANDY_VERSION, TZ, DEVICE_BIND_ADDR=127.0.0.1:3101; BIND_ADDR stays 127.0.0.1:3100
    volumes: [handy-data:/app/data, handy-keys:/app/data/keys]
    read_only: true
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
volumes: {handy-data: {}, handy-keys: {}}
```

**Bridge network.**
- Inside the container, `BIND_ADDR=0.0.0.0:3100`. On the host, publish the admin only on `127.0.0.1:3100`: Docker's
  published ports bypass ufw.
- Give the network a fixed subnet. `TRUSTED_PROXIES` = the bridge gateway, plus Caddy's IP if Caddy runs as a
  container.
- Without that, every client looks like the gateway. The admin gate then refuses it (fails closed), and the
  one-private-IP warning fires.

**Sidecar.**
- A `tailscale/tailscale` container with `TS_USERSPACE=false`, `/dev/net/tun` and `NET_ADMIN`; the app runs with
  `network_mode: service:tailscale` and becomes its own tailnet node.
- In the image's default userspace mode there is no tailnet IP in the netns. Tailnet clients then arrive as 127.0.0.1,
  which breaks the gate's meaning and S6.
- S6's port 53 needs this layout: `sysctls: net.ipv4.ip_unprivileged_port_start=53` on the sidecar. Docker refuses
  `net.*` sysctls with `network_mode: host`.

**Workflows** (third-party actions pinned by SHA; never `pull_request_target`):
- **`server-release.yml`, in this order:**
  1. Build: tests, the aarch64 tarball as today, and the x86_64 binary.
  2. `server-release-image` (`packages: write`, `id-token: write`, `attestations: write`):
     - buildx pushes by digest only;
     - smoke test on amd64 natively and on arm64 under QEMU: `selftest-tls`, then start and wait for `healthy`;
       `Config.User` is `65532`;
     - then tag `X.Y.Z` and `latest` (`imagetools create`);
     - `attest-build-provenance`;
     - OCI labels: source, `GPL-3.0-or-later`, version, revision.
  3. Publish the GitHub release **last**. Until then the update check can't announce a version whose image is missing.
- **`server-ci.yml`:** a `server-image` job runs only when `server/Dockerfile` or the workflow changed: an amd64 musl
  build, `docker build` and the same smoke test, with no push. The `server-ci` gate job includes it.
- **The GHCR package is public.** After the first image push, the owner sets the package's visibility to public once
  (GitHub > Packages > handy-server > Package settings). This step goes in the release docs.

## 7. Docs

**When.** The public and Docker sections land in R1's commit, marked "server >= 0.23.0". Users read `DEPLOY.md` from
`main`, so the docs must never run ahead of a released server.

**`DEPLOY.md`.** "2. Make it reachable" becomes "Choose how it's reachable":
- **Listeners and the admin gate.** What `DEVICE_BIND_ADDR`, `ADMIN_DEVICE_API` and `ADMIN_PUBLIC` do. **(b) warning:**
  with `ADMIN_PUBLIC=on`, the PWA rests on password + TOTP alone. Prefer (c).
- **Caddy, public DNS record.**
  1. A/AAAA records for `phones.example.com` -> the home IP, with DDNS if it changes. A public record names the home
     IP; a tailnet record doesn't.
  2. **CAA record:** `0 issue "letsencrypt.org"` and `0 issue "zerossl.com"`.
  3. Router: forward TCP 443 (+80 for HTTP-01) to the box's fixed LAN IP. IPv6: allow inbound 443 to the box.
  4. **CGNAT check:** the router's WAN IP is in 100.64.0.0/10, or differs from an IP-echo site -> no port forward is
     possible; use Funnel or a VPS.
  5. The Caddyfile from §2.
  6. Checks:
     - `curl -sI https://phones.example.com/devices` -> 404;
     - `/healthz` -> ok;
     - `/api/devices/policy` -> 401;
     - the admin through a public site -> 403.
- **Caddy, tailnet DNS entry.**
  - Bind to the tailnet IP.
  - Certificate: from `tailscaled` for `*.ts.net` (`TS_PERMIT_CERT_UID=caddy`), or DNS-01 for an own domain.
  - The phones' site goes on its own port, for the ACL.
- **`tailscale serve`** (today's setup), with `ADMIN_TAILSCALE_USERS`. That setting refuses tagged kid nodes, since
  tagged nodes carry no user login.
- **`tailscale funnel`**, behind CGNAT: `tailscale funnel --bg --https=8443 http://127.0.0.1:3101`, with Funnel
  allowed in the tailnet policy.
- **Tailscale keys, in every mode that hands one out** (a (c) phone with a tsnet fallback too):
  - tagged (`tag:kid`), pre-approved, not ephemeral, plus the ACL `tag:kid -> <phone port>` only;
  - auth keys expire within 90 days. The tsnet fallback lives on the node's stored state, not on the key.
- **Fix the stale line** "visit `http://<pi-tailscale-ip>:3100`": it can't work with the loopback bind.
- **SSE and proxy notes** (§2), a **Docker section** (§6, including the GHCR visibility step), and backups:
  - the TOTP-secret trade-off (§3.2.8);
  - `pre-migrate-*.db`;
  - "changing exposure": remove the Caddy site; phones fall back to the tailnet if the policy publishes it.

**Elsewhere.**
- `install.sh`'s closing message: drop "Tailscale Funnel for a public URL" and point to DEPLOY.
- The root, server and launcher `CLAUDE.md`s, and `PLAN.md`'s open question.
- A note atop design 03: "§6, §7 and the AdGuard choice are superseded by 22".

## 8. Steps (each green alone: server `cargo test`/fmt/clippy, launcher `assembleDebug assembleRelease testDebugUnitTest`)

| Step | Content | Check | Order / conflicts |
|---|---|---|---|
| **S0** extraction | device routes -> `device_routes.rs`, no behaviour change; the no-literal test | `cargo test` | first, merged at once, so the music implementer rebases once |
| **S1** listeners | §2: routers, `DEVICE_BIND_ADDR`, `ADMIN_DEVICE_API`, the admin gate + `ADMIN_PUBLIC`, `/healthz`, the Funnel check, shutdown ending SSE | router and gate tests (§9); on the Pi: both listeners, the curl matrix | edits `main()` and the router assembly |
| **S2** edge | `TRUSTED_PROXIES`/`client_ip`, `limits.rs`, the token index, device headers, the SSE cap, closing streams on delete, `ADMIN_TAILSCALE_USERS` | tests; on the Pi: a failed login from a tailnet device logs its 100.x IP | `commands_stream` in `device_api.rs` (the music work edits this file: small rebase) |
| **S3** enrollment | §3.1 | tests | after S2, in parallel with S4 in its own worktree (both touch `security.rs`: merge by hand) |
| **S4** admin auth | §3.2: argon2 off the workers, counters and lock, replay, cookie, epoch, `reset-2fa`, headers + CSP phase 1, CSRF, provisioning POST, write-only key, event pruning and throttling, the banner | tests; on the Pi: login, lock and banner, the iOS Safari PWA after the cookie rename | after S2 |
| **D1** Docker | §6 including the pre-migration backup (every deploy kind) | the `server-image` CI job; a `workflow_dispatch` dry run | after S1, in parallel with S2-S4 (`main()`, `system_update.rs`, `system_maintenance.rs`, `backups.rs`) |
| **R1** server 0.23.0 | S0-S4 + D1 as **one series**; **no `server-v*` tag while S1-S4 are pending** (tell the music implementer); DEPLOY's public and Docker sections in this commit | the release smoke test; the Pi behind Caddy; old launchers keep syncing; the GHCR package made public | - |
| **S5** contract | §5: migration, policy `connection`, status, Connection page, device card, QR v2, warnings; **the launcher DTO + compat test in the same commit** | snapshot + `PolicyResponseCompatTest` | after S3 (the QR) |
| **L1** launcher core | §4.2-4.4 and §4.6, `EndpointPolicy`, `ServerConnection` at every call site, the 401 check, the DNS host exemption, a Settings edit -> trial | unit tests; emulator: direct via 10.0.2.2, a URL move between two server instances, stopping one -> R5 | after S5. The music branch's bridge uses `ServerConnection` after a rebase |
| **R2** | server 0.24.0, a launcher release | the device run (§9) | - |
| **L3** strict NSC | §4.3 config + debug overlay + test | the PWA shows no legacy endpoint | after R2 |
| **S6 + L2** mode 2 | §4.5 resolver; design 03 §3 inside `ConnectionPlan` | after design 03 §10.1's spike; update design 03 first | its own design check-in |
| **S7** CSP phase 2 | inline JS -> `/static` | `pages_restore_scroll_after_auto_save` + node tests | any time; touches the music templates, so coordinate |

## 9. Tests

**Server, routers and the gate.**
- `main.rs` contains no `"/api/devices` literal.
- The device router answers 404 with an empty body and no `Set-Cookie`, for GET and POST, on: `/`, `/login`,
  `/devices`, `/devices/1`, `/devices/1/command/wipe`, `/settings`, `/backups/x/download`, `/update/trigger`,
  `/security`, `/music`, `/apps`, `/static/style.css`, `/sw.js` and `/auth/verify-2fa`. It serves every device route.
- With `ADMIN_DEVICE_API=off`, the admin router 404s `/api/devices/*`.
- The gate:
  - a public client through a trusted proxy -> 403, and a throttled event;
  - a tailnet client or loopback passes; `ADMIN_PUBLIC=on` passes everyone;
  - a `Tailscale-Funnel-Request` -> 403;
  - `Tailscale-User-Login` from a public client IP is ignored;
  - `/healthz` passes with every guard set.

**Server, edge.**
- `client_ip`: an untrusted peer with XFF -> the peer; a trusted one -> the right-most untrusted entry; IPv6 keys are
  /64.
- A valid token behind an IP whose failure bucket is full still gets 200.
- With the 8 general slots full of downloads, `policy` and `command-result` are still admitted.
- A third SSE stream closes the first. Deleting a device or revoking it closes its stream.
- Over 512 KiB -> 413. The policy carries `no-store`, and device responses carry `nosniff` and the sandbox CSP.

**Server, enrollment.**
- A QR code works once; two racing uses give one token; an expired code -> 401; no plaintext in the DB.
- With no typed code live, typed failures don't count. With one live, 20 failures pause typed codes, while QR codes
  still enroll and a flood of QR-shaped junk never blocks them.

**Server, admin auth.**
- TOTP failures survive a correct password. The lock doubles, and its expiry doesn't reset the counter. The banner
  appears after a complete login.
- The same code twice -> refused.
- A password change bumps the epoch, so another session is logged out.
- `reset-2fa` clears the lock and the TOTP.
- 20 parallel wrong passwords: an SSE keepalive still arrives on time, and the requests over the queue get 429.
- CSRF: a `Sec-Fetch-Site: cross-site` POST -> 403; a mismatched `Origin` host -> 403; same-origin and headerless
  requests pass.
- Headers and cache rules per page type. Provisioning answers no GET with a code. `security_events` is pruned and
  throttled.

**Server, contract and Docker.**
- The policy's `connection` in the snapshot.
- `connection_state` sanitised (unknown keys dropped, caps applied).
- QR v2 (`"v": "2"`; direct carries no key).
- The pre-migration `VACUUM INTO` (taken once, 3 kept).
- The restore marker: a good zip, a bad name, a zip without a DB, and a failing `connect_db` putting the old DB back.
- A non-writable `data/` gives a clear error.
- `DEPLOY_KIND` page text.

**Launcher.**
- `ConnectionPlanTest`, R1-R8:
  - the 20-min boundary only with a validated network;
  - candidates only from the current policy (a remembered URL is never probed);
  - a 401 with another endpoint 2xx -> a path failure; a 401 everywhere -> revoked;
  - the trial backoff and its 6 h pause; the weekly "proven" probe;
  - tsnet without a key -> `no_auth_key`; `tailscale_app` -> `unsupported`; a legacy endpoint is kept, never adopted.
- `EndpointPolicyTest`: schemes, https-only adoption, legacy, userinfo, debug vs release.
- The client factory:
  - no redirects;
  - tsnet without a proxy throws;
  - `Authorization` only on the endpoint's origin (an absolute `@Url` to another host carries none);
  - external media without the token.
- `ProvisioningExtras` v1/v2 (an unknown `mode` reads as v1); `PolicyResponseCompatTest` with and without
  `connection`; the status JSON shape.
- L3: `NetworkSecurityConfigTest` parses the release XML: cleartext off, no user CAs, no exceptions.

**Device (Jelly Star).**
- tsnet -> direct -> tsnet, on mobile data and on Wi-Fi.
- Stop Caddy: fallback within about 20 min, then back. Drop the public URL from the policy: it is never tried again.
- SSE drops in mode 3 on mobile data. Battery and ring latency, mode 1 vs mode 3 (§4.4).
- Revoke -> "enroll again", and the cache is still enforced.

## Open questions

None left. The user settled the exposure (his own Caddy, with a tailnet entry or a public record; Funnel documented for
CGNAT), step-up (none), and the image (public on GHCR). QA agreed with the rest (see the decisions below).

## QA review (design)

QA, 2026-10-09, against 3a8cfd05. Read: `S/main.rs`, `security.rs`, `handlers/{auth,device_api,devices,provisioning,
settings,backups}.rs`, `streams.rs`, `L/server/{MdmApi,TsnetClient,Provisioning,KidVpnService}.kt`,
`dto/ProvisioningExtras.kt`, the manifest, `server-release.yml`, `install.sh`, and the tower-sessions 0.14 and
totp-rs 5.7 sources. **Verdict**: the direction holds: a separate phone listener, TLS at a proxy, no pinning and no
rotation. Three gaps (#1-#3) would let one internet host or one stolen session undo it. And "a proxy mistake can't
expose the admin" holds only one way round. Settle #1-#3 in this note before S1.

**§0 against the code**

| # | Verdict | Note |
|---|---|---|
| 1 XFF | real, latent | `client_ip` takes the **left-most** entry. `tailscale serve` and Caddy (without `trusted_proxies`) replace XFF, so today's documented setup can't be forged. nginx's `$proxy_add_x_forwarded_for` can, and so can any non-loopback `BIND_ADDR` (the emulator loop; DEPLOY §2's stale "visit `http://<pi-tailscale-ip>:3100`", which can't work with the loopback bind). |
| 2 TOTP counter | real, Medium | `auth.rs:127`. About 15 guesses per 75 min per IP, unbounded across IPs. |
| 3 replay, fixation | replay real (Low); fixation **not exploitable** | tower-sessions gives a new id when the cookie's id isn't in the store (`get_record`), and a session is stored only after a correct password, so the only id an attacker can plant is his own post-password one. Keep `cycle_id()` as hygiene. |
| 4 CSRF | real, Low today | `ts.net` is on the PSL, so sibling hosts are cross-site. It matters in (b). The counts are right (3 inline `<script>`, 12 inline handlers). |
| 5 enrollment | real, Low | About 2e-6 per live code at 1k req/s for 30 min. The race leaves the losing phone with a dead token, not two live ones. |
| 6 token off-tsnet | real; **High** for a phone with an `http://` URL | With `https://`, TLS still protects. `CommandListenerService` has the same `proxy()?.let`. |
| 7 provisioning GET | real, Low-Medium | The QR page and the settings form also have no `Cache-Control`. |
| 8 argon2 | real, **mis-described** | `verify_password` runs inline on the tokio workers (no `spawn_blocking`): at most 4 run at once on the Pi (about 76 MiB, so no OOM), but those 4 block every worker, and SSE keepalives, the device API and the admin all stall. The fix is `spawn_blocking` behind the semaphore. A semaphore awaited on the worker doesn't fix it. |
| 9 one router | real | - |

Missed, of the same kind (fold into S4 unless noted):
- a. A password change or 2FA reset leaves every other session alive. Bump `session_epoch` on both.
- b. `admin_users.totp_secret` is plaintext, so every backup zip, live mirror and volume snapshot carries the second
  factor. Seal it with the `data/keys/` key as Storytel is (a restore on a new box then needs the key, or #5's
  `reset-2fa`), or write down the trade-off.
- c. `security_events` is never pruned, and S3 adds one row per failed enroll on a public listener. Prune after 180
  days, and write at most one row per key per minute, with a count.
- d. Deleting a device leaves its open SSE stream up (auth is checked only at connect). Close it on delete, as on
  revoke.
- e. `pending_admin_id` never expires (the design's 5 min fixes it).

### High

1. **High - the admin guard is opt-in and forgeable, so "a proxy mistake can't expose the admin" holds only for the
   device router.** Scenarios:
   - (i) A Caddyfile typo, `reverse_proxy 127.0.0.1:3100` (or `172.30.0.20:3100`: the compose leaves the admin at
     `0.0.0.0:3100` on the `edge` network next to Caddy), puts the whole admin on the internet. `ADMIN_HOSTS` is unset
     by default.
   - (ii) `ADMIN_TAILSCALE_USERS` believes `Tailscale-User-Login` from any trusted peer. Caddy on the same host is
     127.0.0.1, which is trusted, and Caddy passes client headers through, so a public client sends the header
     itself. The layer meant to catch (i) falls to the same misroute.
   - (iii) The image's `BIND_ADDR=0.0.0.0:3100` puts a cleartext admin on the LAN (the kid) and on any public IPv6 in
     the host layout, and on the sidecar's tailnet IP, bypassing serve.

   Each one silently turns (c) into (b), behind password + TOTP alone, in the mode sold as "the admin isn't on the
   internet at all". Fix (simplification #23):
   - The admin listener serves a request only when the resolved client IP (§2's walk) is loopback or tailnet
     (100.64.0.0/10, fd7a:115c:a1e0::/48), or when it comes from a trusted peer that sent no XFF (local tools, the
     healthcheck). Anything else gets 403 and an event.
   - `ADMIN_PUBLIC=on` is the explicit switch for (b).
   - `Tailscale-User-Login` counts only when the resolved IP is a tailnet IP, and DEPLOY's Caddyfiles strip
     `Tailscale-*`.
   - `ADMIN_HOSTS` becomes an optional extra, and the Funnel refusal stays as a second check.
   - The host and sidecar compose files set `BIND_ADDR=127.0.0.1:3100`.
   - Left over: a serve rule to the wrong port in (a) (`8444 -> 3100`) comes from a tailnet IP. `ADMIN_TAILSCALE_USERS`
     covers it, since tagged kid nodes carry no login, so recommend it in every mode that hands out a tsnet key.
2. **High - the device listener's global caps are a kill switch for any single internet host in (c).** The caps are 50
   req/s before auth for the whole listener, and 128 in flight. Slow enroll bodies hold a slot until the 30 s timeout,
   so about 5 new connections a second keep it full. While it's full, every phone gets a 429 or waits on policy, SSE
   and command-result, so lock, locate and ring don't arrive. A 429 isn't a path failure, so R5 never falls back.
   Separately, the per-IP enroll limit (10/h) counts successful and QR calls too. A carrier-CGNAT neighbour, or Docker
   or rootless setups where every client looks like the gateway, can then block QR setup. Fix:
   - A request with a valid token never touches a global or per-IP bucket. An in-memory `token_hash -> device_id` map,
     refreshed on enroll, revoke and delete, makes a failed lookup cost one SHA-256 and one hash probe.
   - The global and per-IP limits count only unauthenticated or failed requests.
   - Enroll gets its own in-flight cap (8). Its per-IP cap counts typed-shaped failures, and QR-shaped codes are always
     evaluated.
   - Drop the global pre-auth bucket. A large global in-flight cap (say 512) stays as a memory guard only.
   - Startup and the Connection page warn when every public request resolves to the same private IP.
3. **High - step-up failures are unlimited.** §3.2.1 limits TOTP at login only. A stolen session can try step-up codes
   at network speed: the threat model's stolen parent phone, or XSS while CSP keeps `'unsafe-inline'`. With 3 valid
   codes in 10^6, about 170k tries are expected, which is about an hour at 50/s. Step-up guards wipe, the catalog (an
   APK is code on the kid's phone), Connection (every phone and its token sent to the attacker's URL) and backup
   download. Fix:
   - Step-up failures count. 3 in one session end that session (event `stepup_failed`).
   - They don't lock the account: the thief has to start again from the password, and the parent isn't locked out.

### Medium

4. **Medium - the TOTP lock is a lever for whoever knows the password.** Five failures per 15 min keep new parent
   logins locked indefinitely. Logged-in sessions still work. After the first lock, `record_failed_login` locks again
   on every single failure, so about 96 guesses a day get through, about 10 % a year. Fix:
   - After a complete login, show a banner: "N failed 2FA attempts since your last login (IPs ...): your password is
     known, change it".
   - Lock periods double up to 24 h, and a lock's expiry doesn't reset the counter.
   - With #1, only tailnet attackers get this far in (a) and (c).
5. **Medium - step-up by TOTP for "2FA changes" breaks `reset_totp`.** That route exists for a parent who lost the
   authenticator and is still logged in. Nothing documents recovery from a full lockout either, and the distroless
   image has no shell. Fix:
   - The 2FA reset steps up with the current password instead.
   - Add `kid_phone_server reset-2fa <user>`: it clears TOTP and the lock and bumps the epoch.
     `docker compose exec handy /app/kid_phone_server reset-2fa admin` works without a shell. On the Pi, run the
     unit's binary as `kidphone`.
6. **Medium - fallback candidates outlive their endpoints.** `previous` and its state are "never deleted". Scenario:
   the family drops `phones.example.com` (§7 "changing exposure"). A squatter registers it and gets a certificate. The
   next 20-min tsnet outage sends the bearer token, which is as good as the override PIN, to the squatter. Fix:
   - R5's candidates are what the latest policy publishes: `tailnet_url` via tsnet if a key exists, `public_url`
     direct. Never a remembered URL.
   - `previous` serves only R3's 1 h grace. A URL the server stops publishing is forgotten at the next sync.
   - DEPLOY (own domain): a CAA record (letsencrypt.org, plus zerossl.com since Caddy can fall back to it) instead of
     CT watching, which nobody does.
7. **Medium - the cleartext rules leak in three places.**
   - (i) EndpointPolicy's "until L3, http to a tailnet IP literal" isn't tied to tsnet.
   - (ii) §4.6's migration makes a phone without a key `direct` with its stored URL. An `http://100.x` or LAN URL is
     then either cleartext over the OS network (finding 6 again), or refused, and the phone loses its server.
   - (iii) Mode 2's `Dns` filter checks the answer, not the route. With the Tailscale app off, a cached 100.x answer
     goes out over the carrier.

   Fix:
   - `http` only ever goes through tsnet's SOCKS, as legacy, and is reported. Every new endpoint is https: serve's
     8444, Funnel and Caddy all are.
   - A migrated endpoint that fails EndpointPolicy stays active as `legacy` (reported, never newly adopted) until the
     server publishes one that passes.
   - That drops the tailnet `Dns` filter and the `ts.net` cleartext exception in the NSC (#25).
8. **Medium - the token goes to any host the client is pointed at.** `createMdmApi`'s interceptor adds `Authorization`
   to every request, including `@Url` absolute URLs (`downloadTrackedApp`). §4.2 routes "the music bridge's fetches"
   through the same client, so an NRK, podcast or cover URL would get the token. Fix: `ServerConnection` attaches the
   token only when scheme://host:port equals the active endpoint's (unit-test it). External media gets its own client.
9. **Medium - the per-token cap of 8 in flight counts long requests.** One or two SSE streams, an APK download and a
   few music files fill it. Then policy and command-result get a 429, and a ring or lock waits for `Retry-After`. Fix:
   - SSE and file/range downloads get their own cap.
   - `policy`, `status` and `command-result` are always admitted (the token bucket still applies).
   - Size the 600-request bucket from the music implementer's measured first sync, not an estimate.
10. **Medium - the docs can run ahead of the code.** Users read `DEPLOY.md` from `main` (raw URLs). If (b) lands there
    before server-v0.23.0, a 0.22 server gets `admin.example.com -> 3100` with none of S2 or S4. ((c) on 0.22 fails
    closed: nothing listens on 3101.) The music implementer may also tag a server release from `main` between S1 and
    S4. Fix:
    - DEPLOY's public sections and the Docker section land in R1's commit, marked "server >= 0.23.0".
    - S1-S4 go in as one series, or nobody tags `server-v*` while S2-S4 are pending.
    - S1 starts with a mechanical `device_routes()` extraction, merged at once, so the music branch rebases once.
      Add a test that `main.rs` has no `"/api/devices` literal outside `device_routes()`. §9's path list misses
      routes added later.
11. **Medium - Docker updates don't work as written.**
    - The compose pins `0.23.0`, so "`docker compose pull && docker compose up -d`" changes nothing. Use
      `image: ghcr.io/palchrb/handy-server:${HANDY_VERSION}`, and the page says "set `HANDY_VERSION=X` in `.env`,
      then pull and up".
    - `server-release-image` runs after `publish`, and buildx pushes `X.Y.Z` before the smoke. The update check reads
      releases, so it can announce a version whose image is missing or broken. Build, push by digest, smoke, then tag
      `X.Y.Z` and `latest`, and publish the release last.
    - Nothing takes the "pre-update backup" that rollback relies on. On startup, with migrations pending, run
      `VACUUM INTO data/backups/pre-migrate-<from>-<to>.db` first (keep 3). It's cheap and covers systemd too.
12. **Medium - step-up covers destructive actions, but the targeted adult wants live location, location history and
    contacts** (adding himself as a caller). A stolen parent phone with a 30-day session gets all three without a
    code. **User decision**:
    - (a) step-up with a long window (e.g. 12 h) on locate, location history and contact edits; or
    - (b) leave it to the parent phone's lock screen, and default to a shorter `SESSION_INACTIVITY_DAYS` in (b).

    QA leans to (a) for contacts and (b) for locate, where speed matters.

### Low

13. **Low - `/healthz` must skip every admin guard** (`ADMIN_HOSTS` gives 421, `ADMIN_TAILSCALE_USERS` 403, and #1),
    or the container goes unhealthy as soon as a guard is set. The healthcheck dials `BIND_ADDR`'s host (`0.0.0.0` ->
    127.0.0.1, `::` -> ::1). `selftest-tls` passes on any HTTP status, because unauthenticated api.github.com
    rate-limits shared runners.
14. **Low - cookie, CSRF and redirect details.**
    - `__Host-` requires Secure, so with `INSECURE_COOKIES` (the emulator loop) use a plain name, or nobody can log in.
    - The Origin fallback should compare host[:port] only. Behind a proxy that sends no `X-Forwarded-Proto` (nginx's
      default), the guessed scheme is wrong, and every POST from a browser without `Sec-Fetch-Site` gets a 403.
    - `/auth/confirm?next=` refuses `//` and `/\`.
15. **Low - a 401 means revoked only if it comes from our server.** A public URL that now reaches another service's 401
    turns fallback off for good. Fix:
    - A 401 triggers one probe of the other published endpoints. Only a 401 everywhere means revoked.
    - R5 also counts `dns` failures (an expired domain, a stale DDNS).
    - Report "fallback last proven" per candidate (a weekly direct probe is cheap), so a dead fallback shows before
      it's needed.
16. **Low - Docker details.**
    - A bind mount isn't seeded from the image (only named volumes are). Fail with "data/ not writable by uid 65532"
      rather than a SQLite error.
    - Set `TZ` in the compose: `chrono::Local` drives the backup and update schedules, and distroless is UTC.
    - The sidecar needs `TS_USERSPACE=false`. The image defaults to userspace: no tailnet IP in the netns, tailnet
      clients arrive as 127.0.0.1, and #1 and S6 break.
    - Docker refuses `net.*` sysctls with `network_mode: host`, so S6 in Docker means the sidecar.
    - Restore: consume `restore_request` before the swap, and exit non-zero. If `connect_db` fails after a swap, put
      the prerestore copy back: there is no shell to break a loop.
    - The first-run password goes to `data/initial-admin-password` (0600), and the log names the path. Logs get
      shipped.
    - GHCR may create the package private on the first push [belief]: check, and make it public once (user action).
17. **Low - mode 2's resolver trusts self-reported `tailnet_ips`.** A token holder can claim another node's IP, and a
    sibling then gets this phone's list. Key on the source IP seen on the phone's authenticated tailnet requests
    (serve's XFF). For S6's check-in.
18. **Low - device listener headers.** Responses get `nosniff` and `Content-Security-Policy: default-src 'none';
    sandbox`. In (a) on `:8444` and in Funnel (c) the listener shares the admin's hostname, and cookies aren't scoped
    by port.
19. **Low - the typed-code pause is easy to trigger.** 20 junk requests an hour keep typed codes paused for good.
    Count failures only while a typed code is live: otherwise there's nothing to guess.
20. **Low - `Cache-Control: no-store` on all admin HTML turns off bfcache.** Back and forward then reload the page,
    which risks the PWA's scroll restore. Use `private, no-cache` for HTML. Keep `no-store` for location JSON,
    backups, the QR page, and the account and 2FA pages.
21. **Low - DEPLOY's tsnet keys.** In every mode that hands out a key (a (c) phone with a tsnet fallback too), use
    tagged, pre-approved, non-ephemeral keys plus the `tag:kid -> pi:8444` ACL, not only in (a). Auth keys expire
    within 90 days, so the tsnet fallback lives on the node's stored state, not on the key.
22. **Low - QR v2's `"v"`.** Send `"v": "2"`: strings are the safe type in ManagedProvisioning's extras bundle
    [belief]. A launcher that doesn't know a `mode` treats the QR as v1 (`server_url` only).

### Simplification

23. One default-on rule for the admin (#1's client-IP gate) in place of `ADMIN_HOSTS` plus the Funnel refusal as the
    main guard. `ADMIN_TAILSCALE_USERS` stays optional.
24. `limits.rs` with two key kinds: the token, and IP/64 for unauthenticated or failed requests, plus the SSE cap. No
    global pre-auth bucket (#2): less to tune, and nothing to DoS ourselves with.
25. https for every new endpoint (#7). There is then no tailnet `Dns` filter and no `ts.net` exception, L3 becomes
    "cleartext off, debug overlay on", and `NetworkSecurityConfigTest` shrinks.
26. Leave mode 2 out of S5 and L1, except its CHECK value: changing a SQLite CHECK later means rebuilding the table.
    No `tailscale_app` radio, no `vpn_owner`/`dns`/`tailnet_ips`/`tailscale_app` status fields, and no R2 branch for
    it until L2. Drop `previous` as a fallback candidate (#6).
27. Docker docs: one recommended layout. `network_mode: host` with the host's `tailscaled` and Caddy keeps the default
    `TRUSTED_PROXIES`, and docker-proxy never hides the client IP. Give the bridge and sidecar layouts a paragraph
    each, and drop the table.

**Open questions, QA's view**
1. **User decision.** An own domain puts the home IP in public DNS, which tells the targeted adult the family's ISP and
   rough location. Funnel avoids that, along with the port forward, DDNS and Caddy's RAM on the Pi; it costs the
   bandwidth cap on APK and music downloads. QA leans to Funnel first, and an own domain only if downloads hurt (then
   with CAA, #6).
2. Agree, and go further: no `ts.net` cleartext exception at all (#25).
3. Agree, keyed on the observed source (#17), with kernel-mode Tailscale in Docker (#16).
4. Agree, with the candidates limited to what the current policy publishes (#6).
5. Agree. Plain SHA-256 is enough. For the 8-char code it's cosmetic against someone with the DB, but harmless.
6. Agree on both. Add `reset-2fa` (#5).
7. Agree. Check the package's visibility after the first push (#16).

## Decisions after QA review

**User answers (2026-10-09)**
- **U1, exposure.** The user runs his own Caddy, with a tailnet DNS entry or a public DNS record. The design stays
  agnostic: two listeners, safe defaults, and Caddy shown for both cases (§2, §7). Funnel is only a documented
  alternative (for example behind CGNAT). The Funnel-vs-domain question is dropped.
- **U2, no TOTP step-up.** The PWA keeps password + mandatory TOTP. Every real §0 bug is still fixed. QA High 1's
  default-on admin gate is kept, with `ADMIN_PUBLIC=on` as the explicit opt-in for (b). DEPLOY warns that in (b) the
  PWA rests on password + TOTP alone.
- **U3, image.** The GHCR image is public: the owner sets it once after the first push, and the release docs say so
  (§6).
- **U4, High 2.** Taken as proposed (below).

**§0 as checked by QA**
- **§0 #1 XFF.** Changed: reworded as latent (left-most entry; serve and Caddy replace the header). The fix is
  unchanged (§2's walk), and DEPLOY's stale `http://<pi-tailscale-ip>:3100` line is fixed (§7).
- **§0 #2 TOTP counter.** Accepted: QA's rate figures are in §0, and the fix is in §3.2.1.
- **§0 #3 replay and fixation.** Accepted: fixation is removed as a finding, `cycle_id()` stays as hygiene, and the
  replay fix stands.
- **§0 #4 CSRF.** Accepted: the Public Suffix List note is added, and it matters in (b). The fix is §3.2.5.
- **§0 #5 enrollment.** Accepted: the odds and the race's real effect (a dead token for the losing phone) are in §0.
- **§0 #6 token off tsnet.** Accepted: High for `http://`, and `CommandListenerService` is named. The fix is §4.3.
- **§0 #7 provisioning GET.** Accepted: the missing `Cache-Control` is added; `no-store` is in §3.2.4.
- **§0 #8 argon2.** Accepted and corrected: a worker stall, not an OOM. The fix is `spawn_blocking` behind the
  semaphore.
- **§0 #9 one router.** No change.
- **QA a, sessions survive a password change.** Accepted: `session_epoch` is bumped by a password change, a 2FA reset
  and "Sign out everywhere".
- **QA b, TOTP secret in plaintext.** Sealing rejected, trade-off written down (§3.2.8, DEPLOY). A backup already
  holds the password hash, every location and the PIN hashes. Sealing only the secret would make a lost
  `data/keys/` lock the parent out of the admin.
- **QA c, `security_events` never pruned.** Accepted: pruned after 180 days, and high-volume kinds are written at most
  once per key per minute, with a count.
- **QA d, a deleted device keeps its stream.** Accepted: the stream closes on delete, as on revoke.
- **QA e, `pending_admin_id` never expires.** Accepted: it now expires after 5 min.

**High**
1. **The admin guard is opt-in and forgeable.** Accepted:
   - the client-IP gate is on by default, and `ADMIN_PUBLIC=on` is the opt-in for (b);
   - `Tailscale-User-Login` counts only from tailnet IPs, and every Caddy site strips it;
   - the image and compose bind `127.0.0.1`;
   - `ADMIN_HOSTS` is dropped (#23);
   - the Funnel check is kept as a cheap second check, because Funnel's XFF source is [belief].

   Added: Caddy's tailnet sites bind the tailnet IP, and the phones' tailnet site gets its own port for the ACL.
2. **Global caps are a kill switch.** Accepted as proposed (U4):
   - an in-memory token index; valid tokens never touch an IP or global bucket;
   - the per-IP bucket counts only failures (typed-shaped enroll failures included);
   - QR-shaped codes are always checked, behind an enroll in-flight cap of 8;
   - no global bucket before auth; 512 in flight as a memory guard only;
   - the one-private-IP warning.
3. **Step-up failures are unlimited.** Moot: step-up was removed (U2).

**Medium**
4. **The TOTP lock is a lever.** Accepted: the banner after login, the lock doubling up to 24 h, its expiry not
   resetting the counter, and `reset-2fa` clearing it.
5. **Step-up by TOTP breaks `reset_totp`.** Changed: with step-up gone, only the `reset-2fa <user>` CLI is added
   (Docker `exec` and Pi commands in §3.2.3).
6. **Fallback candidates outlive their endpoints.** Accepted:
   - R5's candidates are only what the current policy publishes;
   - `previous` serves only the grace hour;
   - unpublished URLs are forgotten;
   - a CAA record in DEPLOY replaces "CT watching".
7. **The cleartext rules leak.** Accepted:
   - every newly adopted endpoint is https;
   - a legacy `http` endpoint is kept (through tsnet on phones with a key) until an https one is published, and is
     never newly adopted;
   - the tailnet `Dns` filter and the `ts.net` exception are gone.
8. **The token goes to any host.** Accepted: `Authorization` is bound to the endpoint's scheme://host:port, and
   external media gets its own client.
9. **8 in flight per token counts long requests.** Accepted:
   - SSE and downloads get their own cap of 4;
   - `policy`, `status` and `command-result` are always admitted;
   - the 600 bucket is re-sized from the music implementer's measured first sync.
10. **The docs can run ahead of the code.** Accepted:
    - S0's mechanical extraction comes first, with the no-literal test;
    - S0-S4 + D1 go in as one series, with no `server-v*` tags while S1-S4 are pending;
    - the public and Docker docs land in R1, marked ">= 0.23.0".
11. **Docker updates don't work as written.** Accepted:
    - `${HANDY_VERSION}`, and the page text tells the user to set it;
    - push by digest, smoke test, then tag, and publish the release last;
    - a pre-migration `VACUUM INTO` backup for every deploy kind, and a busybox rollback recipe.
12. **Step-up for location and contacts.** Decided by the user: no step-up. A stolen parent phone is its lock screen's
    job; `SESSION_INACTIVITY_DAYS` stays 30 by default, and DEPLOY suggests 7 for (b).

**Low**
13. **`/healthz` and the self-test.** Accepted: `/healthz` skips every guard, the healthcheck dials `BIND_ADDR`'s host
    (mapped), and `selftest-tls` passes on any HTTP status.
14. **Cookie, CSRF and redirect details.** Accepted: a plain cookie name with `INSECURE_COOKIES`, and the Origin check
    compares host[:port] only. `/auth/confirm` went away with step-up.
15. **A 401 means revoked only from our server.** Accepted: a 401 probes the other published endpoints first, `dns`
    failures count for R5, and each candidate's "last proven" time comes from a weekly probe.
16. **Docker details.** Accepted, all six:
    - a bind-mount write check;
    - `TZ`;
    - `TS_USERSPACE=false`;
    - S6 in Docker = the sidecar;
    - the restore marker consumed first, a non-zero exit, and the old DB put back if `connect_db` fails;
    - the initial password in a file.

    GHCR visibility per U3.
17. **The resolver trusts self-reported IPs.** Accepted for S6: it keys on the observed source IP of authenticated
    tailnet requests.
18. **Device listener headers.** Accepted: `nosniff` and the sandbox CSP.
19. **The typed-code pause is easy to trigger.** Accepted: failures count only while a typed code is live.
20. **`no-store` turns off the back-forward cache.** Accepted: HTML gets `private, no-cache`, and `no-store` stays on
    location JSON, backups, the QR page, the provisioning settings and the account/2FA pages.
21. **DEPLOY's tsnet keys.** Accepted: tagged, pre-approved, non-ephemeral keys plus the ACL in every mode that hands
    out a key, and the 90-day expiry noted.
22. **QR v2's `"v"`.** Accepted: `"v": "2"` as a string, and an unknown `mode` reads as v1.

**Simplification**
23. **One default-on admin rule.** Accepted: `ADMIN_HOSTS` is removed. The Funnel check stays as one line (see High 1).
24. **Two key kinds in `limits.rs`.** Accepted: token and IP/64 failures, plus the SSE cap; no global pre-auth bucket.
25. **https for every new endpoint.** Accepted: no `Dns` filter, no `ts.net` exception; L3 = cleartext off, with the
    debug overlay on.
26. **Mode 2 out of S5 and L1.** Accepted: only the CHECK value stays. No radio, no status fields and no R2 branch
    until L2, and `previous` is no fallback candidate.
27. **One recommended Docker layout.** Accepted: host network recommended; bridge and sidecar get a paragraph each;
    the table is dropped.

**QA's open-question views**
1. **Funnel first.** Superseded by U1. QA's point that a public record reveals the home IP is in DEPLOY, next to the
   tailnet-entry option.
2. **No `ts.net` exception.** Agreed (#25).
3. **Mode 2 DNS.** Agreed: the own resolver, keyed on the observed source (#17), with kernel-mode Tailscale in Docker
   (#16).
4. **Fallback limited to published candidates.** Agreed (#6).
5. **Hashed codes.** Agreed: plain SHA-256.
6. **No rotation, no pinning, passkeys later, plus `reset-2fa`.** Agreed (#5).
7. **Image public.** Agreed (U3).
