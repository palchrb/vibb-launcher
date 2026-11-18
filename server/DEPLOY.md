# Deploying to a Raspberry Pi / DietPi

These steps get the Vibb Phone server (the parent app, a PWA) running on a Raspberry Pi Zero 2 W. The same
`install.sh` works on any 64-bit Debian-family Linux with systemd, aarch64 or x86_64 (a home server, a VM, a VPS): every
server release has a static tarball for each (`kid-phone-server-aarch64-unknown-linux-musl.tar.gz`,
`kid-phone-server-x86_64-unknown-linux-musl.tar.gz`), and the script takes the one for `uname -m`. Docker is optional
on both ("Docker" below). Other architectures (32-bit ARM, i686, ...) aren't supported.

## 1. Install

SSH into the Pi, then run:

```
curl -fsSLo install.sh https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/install.sh
less install.sh        # it runs as root: read it first
sudo bash install.sh
```

(Downloaded to a file rather than piped into `sudo bash`, so what runs as root is what you read, and a cut-off
download never runs half a script. The server it installs is checked against the project's signing key - "Release
signing" under "Updating".) **Until the first signed server release (0.24.0) is published, `install.sh` says so and
installs nothing**: releases before it aren't signed, and it never installs an unsigned one.

This downloads the newest server release (the newest `server-vX.Y.Z` release of the
[`palchrb/vibb-launcher`](https://github.com/palchrb/vibb-launcher) monorepo), sets it up as a background service that starts on boot, and prints an admin username/password at the end — **save that password**, you'll need it to log in the first time (and you'll be asked to change it immediately after).

**The server never acts on its host.** It runs as an unprivileged user and asks nothing of root: there is no update
button, no restart, no system or Tailscale updates, no reboot, no drive formatting and no schedule for any of it
(removed in server 0.67.0, 2026-11-04, the user: "jeg ønsker å gjøre ting på host selv"). You update the server (below, "Updating"),
and keep the system and Tailscale updated yourself (`apt upgrade`, `tailscale update`, or unattended-upgrades). The
server makes its backups itself, in its own `data/backups/` ("Backups").

The app only listens on the Pi itself (`127.0.0.1:3100`) by default — that's intentional for security.

## 2. Choose how it's reachable (server >= 0.23.0)

The server never terminates TLS and never listens publicly by itself: it binds loopback, and a proxy you run (Caddy,
or `tailscale serve`) brings HTTPS and decides who can reach it. Since 0.23.0 it has **two listeners**, so the admin
pages and the phones' API can be exposed differently:

| `.env` setting | Default | What it does |
|---|---|---|
| `BIND_ADDR` | `127.0.0.1:3100` | The admin listener: the PWA, and the phones' API while `ADMIN_DEVICE_API` is on. |
| `DEVICE_BIND_ADDR` | unset | The phone listener, e.g. `127.0.0.1:3101`: only the phones' API (`/api/devices/*`), app push's public routes (`/up/` and `/_matrix/push/v1/notify`, design 108) and `/healthz`; every other path is a bare 404. |
| `ADMIN_DEVICE_API` | `on` | The admin listener also serves the phones' API (the URLs phones set up before 0.23.0 use). `off` once every phone uses the phone listener. |
| `ADMIN_PUBLIC` | `off` | The admin listener answers **only loopback and tailnet clients** (100.64.0.0/10, fd7a:115c:a1e0::/48); everyone else gets 403 (logged as `admin_refused` in the security log). `on` lets every client in - see (b) below. |
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | Proxies whose `X-Forwarded-For` is believed: exact proxy addresses (a range only if every address in it is one of your proxies). Your proxy on the same box needs nothing here. |
| `ADMIN_TAILSCALE_USERS` | unset | Only behind `tailscale serve`: the admin pages also need one of these Tailscale logins (comma list). Tagged phones carry no login, so a phone node can't open the admin even if a serve rule points it there. |
| `SESSION_INACTIVITY_DAYS` | `30` | How long an admin session lasts without use. |
| `SESSION_MAX_DAYS` | `90` | How long an admin session lasts after its sign-in, however often it is used (1-3650): the installed PWA signs in again once in that time. |
| `PASSKEY_RP_ID` | unset | The domain parents' passkeys belong to (server with design 74). Unset: the address the PWA is opened at (an IP address gets no passkeys). **Recommended: the PWA's own name, e.g. `handy.example.com`** - other services on `example.com` then never see the passkey offered, and another address (the tailnet name) signs in with the password. A parent domain (`example.com`) makes one passkey work only at `example.com` itself and at the names you list in `PASSKEY_ORIGINS` (a second host under it is never taken from the request, so another service on the domain can't relay a sign-in). Checked at start: lower case, a name with a dot (or `localhost`), not an IP, not a public suffix (`com`, `co.uk`, `ts.net`...) - a bad value refuses to start and says why. Changing it later makes the passkeys made before stop working (each keeps the domain it was made for), and a browser with the app lock on ("Lock with Face ID") then asks for a new sign-in: Sign Out on the lock screen. |
| `PASSKEY_ORIGINS` | unset | With `PASSKEY_RP_ID`: every other address under it the PWA is opened at, and the outside name when your proxy hands the server another host name than the one in the browser: full origins, comma-separated, e.g. `https://handy.example.com,https://lan.example.com:8443`. Each must be `https://` (no `:443`) and under `PASSKEY_RP_ID`, which it needs. |

The admin gate is on by default: a proxy rule meant for the phones, or a typo in a Caddyfile, can't put the PWA on the
internet - it answers 403 to anything that isn't loopback or tailnet. `/healthz` (says `ok`) always answers.

**What the gate can't see.** It knows a client by the connection's address, and behind a trusted proxy by the
proxy's `X-Forwarded-For`. So:
- **`TRUSTED_PROXIES` holds exact proxy addresses, never a subnet with other hosts in it.** Any host in a trusted
  range can name a tailnet address in `X-Forwarded-For`, or send none and pass as a tool on the box.
- **Never point a TCP-level forwarder at the admin port (3100)**: `socat`, `ssh -L`, `tailscale serve
  --tls-terminated-tcp`, `tailscale funnel --tcp`, Caddy's layer4 module. They send no `X-Forwarded-For`, so every
  client they forward arrives from loopback and passes. Use an HTTP proxy (Caddy's `reverse_proxy`, `tailscale serve`
  with an `http://` target), which says who the client is.

**Three ways to expose it**:
- **(a) tailnet only** (default, and today's setup): admin and phones over Tailscale.
- **(c) phones public, PWA tailnet-only**: a public HTTPS name for the phone listener, the admin stays on the tailnet.
  For phones without Tailscale. Prefer this to (b).
- **(b) all public**: `ADMIN_PUBLIC=on` and the admin behind a public HTTPS name too. **The PWA then rests on the
  password and the 2FA code alone**, facing the whole internet (phishing, password guessing, any bug in a web page).
  Set `SESSION_INACTIVITY_DAYS=7` as well.

Which URL a phone uses is still set per phone for now - the setup QR's server URL (Settings > Phone Setup) or the
launcher's Settings > Server URL (behind the PIN). Either takes only an `https://` address, or `http://` to a Tailscale
IP address, which the phone then sends only through its embedded Tailscale, never over its own network (launcher
security review S-M1). A phone set up with the embedded Tailscale key sends every request
through Tailscale: give it the tailnet URL. A public URL is for a phone set up without a Tailscale key. Automatic
switching and fallback between the two come with the Connection page and a launcher update (design 22, later steps).
Phones that use the admin URL keep working while `ADMIN_DEVICE_API` is on.

### With `tailscale serve` (tailnet only, the simplest)

```
sudo tailscale serve --bg --https=443 http://127.0.0.1:3100
```

This gives you `https://<hostname>.<tailnet>.ts.net`, reachable only from your own tailnet (an HTTPS URL is what makes
the admin installable as a PWA). If this Pi already serves something else on port 443, use another port, e.g.
`--https=8443`. Plain `http://<tailscale-ip>:3100` doesn't work: the server only listens on 127.0.0.1.

To give the phones their own port (so the tailnet policy can let phones reach that port only), add
`DEVICE_BIND_ADDR=127.0.0.1:3101` to `.env`, restart, and:

```
sudo tailscale serve --bg --https=8444 http://127.0.0.1:3101
```

Then set the phones' server URL to `https://<hostname>.<tailnet>.ts.net:8444`, and once every phone uses it,
`ADMIN_DEVICE_API=off`. Also set `ADMIN_TAILSCALE_USERS=you@example.com` (your Tailscale login; several separated by
commas): `tailscale serve` passes the signed-in user, tagged phone nodes have none, so a serve rule pointed at the
wrong port still can't open the admin for a phone.

Use `tailscale serve`, **not** `tailscale funnel`, for the admin: Funnel makes it public (and the server refuses
Funnel requests to the admin anyway).

### With your own Caddy

The example below is also `server/deploy/Caddyfile.example`.

Caddy gets its own certificates and replaces any `X-Forwarded-For` a client sends (never set `trusted_proxies` in
Caddy for this site). Don't add `encode`: the phones' event stream must not be compressed. Strip
`Tailscale-User-Login` in every site, so nobody can send it through Caddy. Put `DEVICE_BIND_ADDR=127.0.0.1:3101` in
`.env` and restart. **Caddy 2.11 and later need `write_idle` raised** (the global `servers { timeouts { … } }` block
below): since 2.11 Caddy aborts any response that writes nothing for one minute (`RST_STREAM INTERNAL_ERROR` on
HTTP/2), and the phones' event stream writes only a keepalive every `SSE_KEEPALIVE_SECS` (240 s) - every stream died
60 s after each keepalive, and every phone reconnected every 5 minutes (found 2026-10-20 on the user's Caddy 2.11.6).
Any other proxy needs the same: its idle or read timeout for the upstream response above the keepalive interval.

```
{
	email parent@example.com
	servers {
		timeouts {
			read_header 10s
			# Caddy 2.11+ aborts a response that writes nothing for 1 minute (its write_idle default);
			# the phones' event stream writes a keepalive every 4 minutes. Keep this above that.
			write_idle 5m
		}
	}
}

# (c): a public DNS record -> the phone listener only
phones.example.com {
	request_body {
		max_size 1MB
	}
	reverse_proxy 127.0.0.1:3101 {
		header_up -Tailscale-User-Login
		# App push (design 108): Caddy reads a request body (a push is at most 4 KiB) before the server sees it,
		# so a slow client waits on Caddy, never on the server's push slots.
		request_buffers 8KiB
	}
}

# a tailnet DNS entry -> the PWA, on the tailnet IP only
pi.tail1234.ts.net {
	bind 100.101.102.103
	reverse_proxy 127.0.0.1:3100 {
		header_up -Tailscale-User-Login
	}
}

# the same tailnet entry -> the phones, on their own port (for the tailnet policy)
pi.tail1234.ts.net:8444 {
	bind 100.101.102.103
	reverse_proxy 127.0.0.1:3101 {
		header_up -Tailscale-User-Login
	}
}
```

Replace `100.101.102.103` with the box's own tailnet IP (`tailscale ip -4`).

**A tailnet DNS entry** (the PWA, and phones over Tailscale): the site binds the tailnet IP only. The certificate:
- for the `*.ts.net` name, Caddy fetches it from the local `tailscaled` by itself; tailscaled must allow it:
  `TS_PERMIT_CERT_UID=caddy` in `/etc/default/tailscaled`, then `sudo systemctl restart tailscaled` (and "HTTPS
  Certificates" turned on in the Tailscale admin console);
- for your own domain pointing at the tailnet IP, Caddy needs the DNS-01 challenge (a Caddy build with your DNS
  provider's module) - nothing on the internet can reach the site to check it otherwise.

**A public DNS record** (the phones in (c)):
1. A/AAAA records for `phones.example.com` -> your home IP (with dynamic DNS if it changes). Note that a public record
   names your home IP, and with it your ISP and rough area; a tailnet entry doesn't.
2. A CAA record, so only the two CAs Caddy uses can issue for the name: `0 issue "letsencrypt.org"` and
   `0 issue "zerossl.com"`.
3. On the router, forward TCP 443 (and 80, for the HTTP-01 challenge) to the box's fixed LAN IP. IPv6: allow inbound
   443 to the box.
4. Check for carrier-grade NAT first: if the router's WAN IP is in 100.64.0.0/10, or differs from what an IP-echo
   site shows, no port forward can work - use Tailscale Funnel (below) or a VPS instead.
5. Check from outside your network (mobile data):
   - `curl -sI https://phones.example.com/devices` -> `404`;
   - `curl -s https://phones.example.com/healthz` -> `ok`;
   - `curl -sI https://phones.example.com/api/devices/policy` -> `401`;
   - and if you ever pointed a public site at the admin port by mistake, it answers `403`.

**(b), the admin public too**: a public site with `reverse_proxy 127.0.0.1:3100` and `ADMIN_PUBLIC=on` in `.env`.
Read the warning above first.

### Tailscale Funnel (behind carrier-grade NAT)

Funnel gives the phone listener a public HTTPS URL without a port forward, DNS record or Caddy:

```
sudo tailscale funnel --bg --https=8443 http://127.0.0.1:3101
```

Funnel must be allowed for the node in the tailnet policy. Funnel's bandwidth is limited, which app and music
downloads will feel.

### Tailscale keys for the phones

In every setup that gives phones a Tailscale key (tailnet only, or a phone listener with the tailnet as a second
path): a **one-time** (not reusable), **tagged** (`tag:kid`), pre-approved, non-ephemeral key, and a tailnet policy
that lets `tag:kid` reach only the phones' port (`8444` above) on this box (security review M12).

One-time means one key per phone setup. Settings > Phone Setup holds a single key, and every setup QR carries it:
before setting up a phone (or setting one up again), make a new key in the Tailscale admin console and paste it
there. The phone uses it once; after that the key, and any photo of the QR, is worth nothing. Remove it from
Settings > Phone Setup afterwards if you like - it is dead either way. A phone already set up keeps working on its stored node
state; the key only matters for a setup. The key is write-only on Settings > Phone Setup since 0.23.0. The launcher
deletes its copy once the phone is on the tailnet (launcher security wave, M12) and comes up from the stored node state
after that - its Settings then say "On the tailnet - the key was deleted". A phone whose node was removed from the
tailnet may come back by scanning a new QR (tsnet logs in again with the key it was started with once its stored node
is refused) - not verified; if it doesn't, set it up again (wipe, then provision).

### Phones' long-lived connection through a proxy

The phones hold a long-lived connection to `/api/devices/commands/stream` (server-sent events, the only way the server
nudges a phone) that is quiet except for a keepalive every `SSE_KEEPALIVE_SECS` (240 s by default). Caddy and
`tailscale serve` pass it through unbuffered as they are. **Any other proxy needs a read timeout of at least 300 s, no
buffering and no compression on it** - for nginx `proxy_buffering off; proxy_read_timeout 330s; proxy_http_version
1.1;`. Cloudflare's proxy cuts connections idle for 100 s: use DNS-only there, or `SSE_KEEPALIVE_SECS=90`.

`SSE_KEEPALIVE_SECS` (5-240, default 240): each keepalive wakes the phone's radio, and the launcher reconnects after 300 s of
silence. `SSE_KEEPALIVE_SECS=120` in `.env` shortens it.

### The phones' certificate check (design 76)

Vibb One (with `server_pin_v1`) talks to this server over https only through a certificate chain from **Let's Encrypt
or ZeroSSL** (Sectigo) once the check is on for that phone. The server switches it on by itself after the phone has
shown only such certificates for three days (on Wi-Fi and mobile data); Device › Advanced › Certificate check shows
where each phone is, and a certificate from another issuer - an attack, or a proxy with its own CA - is told there and
pushed. Caddy's Let's Encrypt, its ZeroSSL fallback and `tailscale cert` are all in the set. **Behind a CDN that picks
its own CA** (Cloudflare's Universal SSL rotates Let's Encrypt, Google Trust Services and SSL.com) the check may switch
on during a Let's Encrypt period and a later rotation then cuts the phones off: turn such phones' check off on that
page, or give the CDN a fixed CA (Cloudflare: an Advanced certificate with Let's Encrypt) - or keep the phones on
DNS-only as above. A CAA record naming the CA, ideally with `accounturi` (`0 issue "letsencrypt.org;
accounturi=..."`), narrows who can issue for the name. **Recovery** for a phone that refuses the server's
certificate: on the phone, Settings (parent code) › Server URL › the **tailnet recovery address** below - the tailnet
path is never pinned - then fix the certificate (switch the address back to https) or turn the phone's check off on
its Advanced page. Re-scanning the setup QR also clears the check on the phone.

#### Tailnet recovery address

The one way back without the setup QR is plain `http://` to a **Tailscale IP literal** (`100.64.0.0/10` or
`fd7a:115c:a1e0::/48`): the phone sends it only through its own embedded Tailscale, WireGuard proves the peer, and no
certificate is checked. Nothing else is taken - not a LAN address, not a `*.ts.net` name over http. **Something must
answer the phones' API there**, and with the setups above nothing does by default: the server binds loopback, and
Caddy's and `tailscale serve`'s sites are https. Set one up before a phone needs it, and check it:

- **Server on a tailnet host, listening beyond loopback** (`DEVICE_BIND_ADDR=0.0.0.0:3101`, or the box's own
  tailnet IP `DEVICE_BIND_ADDR=100.101.102.103:3101`; Docker with `network_mode: host` the same): the address is
  `http://<the box's tailnet IP>:3101`. With `0.0.0.0` the phone listener answers every network the box is on, so
  firewall 3101 to the tailnet **and to your reverse proxy** - the address the proxy connects from - and deny it
  everywhere else:
  ```
  ufw allow in on tailscale0 to any port 3101 proto tcp
  ufw allow from <your proxy's address> to any port 3101 proto tcp   # e.g. ufw allow from 10.73.10.81 ...
  ufw deny 3101/tcp
  ```
  Leave out the proxy's line and every phone that comes in through the proxy loses the server. **Binding only the
  tailnet IP** (`DEVICE_BIND_ADDR=100.101.102.103:3101`) does the same to a proxy on the LAN, which connects to the
  box's LAN address: bind the tailnet IP only when nothing but the tailnet reaches the phone listener (no proxy, or
  a proxy that connects over the tailnet). The phone listener serves only `/api/devices/*` and `/healthz`, every
  request behind a phone's token.
- **Caddy on the same box, server on loopback**: one more site, plain http, on the tailnet IP only:
  ```
  http://100.101.102.103:3102 {
  	bind 100.101.102.103
  	reverse_proxy 127.0.0.1:3101 {
  		header_up -Tailscale-User-Login
  	}
  }
  ```
  The address is then `http://100.101.102.103:3102`. Caddy on **another** host can do the same with its own tailnet
  IP, proxying to the server's phone listener.
- **`tailscale serve`**: `tailscale serve --bg --http=3102 http://127.0.0.1:3101` gives `http://<the box's tailnet IP>:3102`
  (not Funnel: the tailnet only).
- **The tailnet policy**: the phones' tag must reach that port, e.g. `{"action": "accept", "src": ["tag:phone"],
  "dst": ["tag:server:3101"]}` (or the box's name or IP, and 3102 for the Caddy or serve site).
- **Check** from another tailnet device (not the box itself): `curl http://100.101.102.103:3101/healthz` says `ok`.
  On a phone with the tailnet joined, Settings › Server URL › that address, then "Sync now" says it synced; switch the
  address back to https afterwards.

The device page (Advanced › Certificate check) shows the address and sends it to the phones (Settings › Server
certificate). It is `PHONE_RECOVERY_URL` when set (plain `http://` to a Tailscale IP only - anything else is ignored
with a log line), else the setup QR's own address when that is one, else this box's tailnet address on the phone
listener (or on the admin listener while `ADMIN_DEVICE_API` is on) when that listener surely answers it (bound to the
tailnet IP, to `0.0.0.0` for an IPv4 address, or to `[::]` for an IPv6 one - and for IPv4 only when that socket isn't
IPv6-only). The server reads its tailnet address as the source the system picks toward Tailscale's resolver, without
sending anything, and only while a Tailscale interface is up (an address in `fd7a:115c:a1e0::/48`, or `tailscale0`),
so a carrier-NAT uplink in `100.64.0.0/10` isn't taken for the tailnet. Behind Caddy on loopback or on another host,
with Tailscale in userspace networking or in another container, it can't tell: set `PHONE_RECOVERY_URL` to the site
above.

### Changing how it's exposed

To stop the public phone URL: point the phones back at the tailnet URL first, then remove the Caddy site (or the
Funnel), and finally the DNS record. Keep the CAA record as long as you own the domain.

## Signing in and recovery (server >= 0.23.0)

- Every sign-in is the password, then a 2FA code. A code works once. Wrong passwords never lock the account (only
  the address that sent them, for an hour after 15); wrong 2FA codes do: 5 lock it for 15 minutes, every further lock
  doubles, up to 24 hours. After a sign-in that followed wrong codes, the Devices page says how many and from where:
  whoever typed them knew the password - change it.
- Changing the password or resetting 2FA signs out every other session; Settings › Account & Parents › "Sign Out
  Everywhere…" signs out this one too.
- **A second parent** (server >= the release with design 62): Settings › Account & Parents › Parents › "Add
  Parent…" asks for your password and shows a one-time password once, valid 72 hours; the new parent signs in with
  it and sets their own password and 2FA. "Withdraw…" takes back an invitation not used yet. Every parent can do
  everything, also remove another. On a server that sets `ADMIN_TAILSCALE_USERS`, add the new parent's Tailscale
  login there too, or the admin pages stay closed to them.
- **Lost the authenticator and signed out?** On the Pi:
  `cd /opt/kid-phone-server && sudo -u kidphone ./kid_phone_server reset-2fa admin` (in Docker:
  `docker compose exec handy /app/kid_phone_server reset-2fa admin`; use your own username). It removes the 2FA
  setup, the lock and its count, signs out every session, and **replaces the password**: the new one is in
  `data/initial-admin-password-<id>` - the command prints the exact file (on the Pi `sudo cat` it under
  `/opt/kid-phone-server/data/`; in Docker copy it out as for the first start). Sign in with it, set a new password,
  then a new authenticator. Replacing the password matters: the lock you are recovering from means someone else
  knows the old one. It also brings back an account another parent removed, with its username, and clears an
  expired one-time password.
- **Passkeys** (server with design 74): Settings › Account & Parents › Passkeys › "Add Passkey…" (your password
  first) makes a passkey on the phone or computer you are on - Face ID, a fingerprint, the phone's code or a security
  key with a PIN. The sign-in page then has "Sign In with Passkey": one tap, no 2FA code (the passkey is both
  factors). Every parent gets a notification when a passkey is added. Passkeys need https and a host name (not
  `http://` on the LAN, not an IP address); see `PASSKEY_RP_ID` above for which addresses they work at. Removing one
  signs out your other sessions. A Reset by another parent, a Remove and `reset-2fa` delete the account's passkeys.
- `reset-sign-in <user>` is the same command as `reset-2fa <user>` (a clearer name now that a sign-in can be a
  passkey).
- **Passkey Only** (server with design 74 step 2): Account › Passkeys › "Passkey Only" deletes your password and 2FA;
  you then sign in only with a passkey (one is enough; two are recommended). Turn the password on again on Account
  ("Turn the Password On Again…": a passkey, a new password and a new authenticator). **Lost every passkey?** Another
  parent's Reset… gives you a one-time password; with no other parent, `reset-sign-in <user>` (as above, `docker
  compose exec handy /app/kid_phone_server reset-sign-in <user>` in Docker) turns Passkey Only off, deletes the
  passkeys and writes a new password to `data/initial-admin-password-<id>`. Neither needs the old password. When every
  parent is on Passkey Only, the sign-in page shows only "Sign In with Passkey".
- Updating to 0.23.0 signs everyone out once (the session cookie is now `__Host-handy`; reopen the installed PWA on an
  iPhone and sign in again), and enrollment codes that were open stop working: make a new one (the phone's Advanced › New Setup Code).

## Docker (server >= 0.23.0)

The server also ships as a multi-arch image (`linux/amd64`, `linux/arm64`): `ghcr.io/palchrb/handy-server:X.Y.Z`
(and `latest`, the highest release). It is optional: the install script above runs the same server without Docker on
either architecture, and the Pi Zero 2 W stays with it. The image suits a home server or VPS that runs Docker anyway. It runs
as uid 65532 with no shell, keeps everything in `/app/data`, and listens on `127.0.0.1:3100` by default.

**Which root certificates the server trusts.** The image carries the base image's Debian CA bundle minus the roots
`server/deploy/distrusted-roots.txt` names (since the eMudhra key compromise of October 2026: Debian removed two roots
on 2026-10-20, and the base image follows only at its next rebuild, so the image removes them itself at build time and
the build fails if one survives; the release's smoke test then makes one real HTTPS request through the server's own
TLS stack). Without Docker the server trusts the host's store: keep `ca-certificates` updated (`apt upgrade`), which is
where the same removals arrive.

**Recommended: the host's network, with the host's Tailscale and Caddy.** The proxies' defaults then hold, and Docker
never hides the client's address. The files are in the repo: `server/deploy/compose.yaml`, `server/deploy/env.example`
(copy it as `.env`), `server/deploy/update-image.sh` and `server/deploy/Caddyfile.example` (option (c) above).
`compose.yaml`:

```yaml
services:
  handy:
    image: ghcr.io/palchrb/handy-server:${HANDY_VERSION}@${HANDY_DIGEST:?run ./update-image.sh X.Y.Z first}
    labels: {com.centurylinklabs.watchtower.enable: "false"}
    restart: unless-stopped
    network_mode: host
    env_file: .env
    volumes: [handy-data:/app/data, handy-keys:/app/data/keys]
    read_only: true
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
volumes: {handy-data: {}, handy-keys: {}}
```

`.env` next to it (the settings from section 2 and "Launcher provisioning settings" go here too; `update-image.sh`
writes `HANDY_VERSION` and `HANDY_DIGEST`):

```
TZ=Europe/Oslo
DEVICE_BIND_ADDR=127.0.0.1:3101
LAUNCHER_SIGNATURE_CHECKSUM=...
```

`TZ` matters: backups and the update check run on local time, and the image is UTC otherwise. Then
`./update-image.sh X.Y.Z` with the newest release (see "Updating" below): it verifies the image and starts it. With no `ADMIN_PASSWORD` set, the first start writes a password to
`/app/data/initial-admin-password` (the log names the file, never the password). The image has no shell, so copy it
out: `docker compose cp handy:/app/data/initial-admin-password . && cat initial-admin-password && rm
initial-admin-password`. Sign in as `admin` and change it - the server deletes its copy then.

- **Volumes**: `/app/data` holds the database, backups and caches; `/app/data/keys` (the Storytel, Web Push and Child PIN keys - `kid-pin.key`: lose it and each Child PIN has to be set again before it can be sent or shown) is its own
  volume, so copies of the data volume don't carry it. Use named volumes: a bind-mounted directory isn't seeded from
  the image and must belong to uid 65532 (`chown -R 65532:65532 dir`), or the server stops with "data/ is not
  writable by uid 65532".
- **Updating** (privacy review 2026-11-17 H1): by hand, `./update-image.sh X.Y.Z` in the folder with `compose.yaml`.
  It looks up the release's image digest, runs `gh attestation verify oci://ghcr.io/palchrb/handy-server@<digest>
  --owner palchrb` - the build provenance the release workflow signed for exactly that image -, and only when that
  passes writes `HANDY_VERSION` and `HANDY_DIGEST` into `.env` and runs `docker compose pull && docker compose up -d`.
  Compose pulls by the digest, so what runs is what was verified, and a refused check changes nothing. It needs
  `docker` with buildx and `gh` signed in (`gh auth login`, any account: it only reads). No Watchtower for this
  container, and never `latest`: an image pushed to the tag would otherwise run within the hour, unchecked - the
  GitHub account alone would be the key to the server and so to the phones. The label in `compose.yaml` keeps a
  Watchtower the host runs for other containers off this one. Settings › Software Updates shows this server's
  version and the newest release; it never updates anything itself. Before migrating the database, the new
  version copies it to `data/backups/pre-migrate-<from>-<to>.db` (the newest 3 are kept). A pre-migration copy keeps
  what the old schema held until it rotates out (e.g. 0126's dropped `weather_places`, the cells' old place names).
- **Rolling back**: put the pre-migration copy back, then the old release, verified the same way:

  ```
  docker compose stop handy
  docker run --rm -v handy-data:/data busybox sh -c 'cp /data/backups/pre-migrate-<from>-<to>.db /data/kidphone.db && rm -f /data/kidphone.db-wal /data/kidphone.db-shm && chown 65532:65532 /data/kidphone.db'
  ./update-image.sh <the old X.Y.Z>
  ```

  (The volume's name may carry the compose project's prefix: `docker volume ls`.)
- **Restoring a backup** from the Backups page works in the container too: the server stops, swaps in the backup at
  its next start (Docker's restart policy brings it back), and keeps the previous database in
  `data/backups/prerestore-<time>/` (the newest 3 are kept); if the backup won't open, the previous database goes
  back. A backup whose database is over 1 GiB is refused.
- Copy `data/backups` off the host yourself: the server never copies anything out of its volume.
- **Recovery**: `docker compose exec handy /app/kid_phone_server reset-2fa admin` (it also replaces the password,
  and turns Passkey Only off; see "Signing in and recovery").

**Bridge network instead**: inside the container set `BIND_ADDR=0.0.0.0:3100` (and `DEVICE_BIND_ADDR=0.0.0.0:3101`),
and publish them only on the host's loopback (`ports: ["127.0.0.1:3100:3100", "127.0.0.1:3101:3101"]` - Docker's
published ports bypass ufw). Give the network a fixed subnet and set `TRUSTED_PROXIES` to its gateway (plus Caddy's
address if Caddy runs as a container too). Without that, every client looks like the gateway: the admin gate refuses
them, and the server warns at startup.

**Tailscale as a sidecar instead of on the host**: a `tailscale/tailscale` container with `TS_USERSPACE=false`,
`/dev/net/tun` and the `NET_ADMIN` capability, and the server with `network_mode: service:tailscale`, so it becomes its
own tailnet node. In Tailscale's default userspace mode the container has no tailnet IP, tailnet clients then arrive
as 127.0.0.1, and the admin gate can't tell them from local tools.

**Making the image public (once, for the repo owner)**: after the first `server-v*` release has pushed an image,
GitHub may have created the package private. Open GitHub > your profile > Packages > `handy-server` > Package
settings > Change visibility > Public. The release workflow builds each architecture's binary natively (plain `cargo`,
`x86_64-unknown-linux-musl` on `ubuntu-24.04`, `aarch64-unknown-linux-musl` on the `ubuntu-24.04-arm` runner; no
`cross`, no QEMU), builds each image from the binary in that architecture's tarball and tests it natively on its own
runner before the two are joined and get a version tag, and publishes the GitHub release only after that.

## Updating

```
curl -fsSLo update.sh https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/update.sh
sudo bash update.sh
```

Run it over SSH, by hand: the server never updates itself (Settings › Software Updates shows this server's version,
the newest release and this command, nothing more). Downloads the newest stable `server-vX.Y.Z` release (prereleases
and drafts are skipped), checks its signature ("Release signing" below) and the release's published `.sha256`, and
swaps the binary in place. Doesn't touch your `.env`. The download is unpacked and
checked before the service is stopped, so a failed download leaves the old version running; if anything fails after the
stop, the service is started again. Before the swap it copies the database and the old binary to
`/var/backups/kid-phone-server/<timestamp>/`, because the new version may migrate the database on its first start. Old
backups are pruned (newest 3 kept) only after the new version is running, so failed retries can't push out the
pre-update copy.

To install from a different fork, prefix `bash` with `KPS_REPO=owner/repo` (for both `install.sh` and `update.sh`):
`sudo KPS_REPO=someone/vibb-launcher bash install.sh`, with the fork's own script. The fork must keep this repo's layout
(`server/deploy/`), publish server releases as `server-vX.Y.Z`, and sign them with its own key, pinned in its scripts
("Release signing").

Updates only ever go forward: `update.sh` refuses a release that isn't newer than the installed one (and Software
Updates calls an older release "You have the latest version"). To go back on purpose (see "Rolling back" below for the database), run `update.sh` with
`KPS_ALLOW_DOWNGRADE=1` - it then installs the newest release even if older.

### Release signing (server >= 0.24.0)

Root on the Pi installs and runs only release files signed with the project's [minisign](https://jedisct1.github.io/minisign/)
key (security review M5): `install.sh` and `update.sh` check the tarball for their machine (each of the two has its
own `.minisig`). `update.sh` itself is signed too. The public key is pinned in the scripts themselves (`SERVER_SIGNING_PUBKEY`), and each signature's
trusted comment names the file and the release tag, so a signed file can't be passed off under another name or as
another release. `server-release.yml` signs both tarballs and `update.sh` in its `server-release-sign` job, with the `SERVER_MINISIGN_KEY`
secret of the protected `release` environment, before any image is pushed or tagged: without the secret, or when it
doesn't match the pinned key, the run stops there and nothing is pushed or published.

**One-time setup by the repo owner, before the first `server-v0.24.0` tag:**
1. On your own computer (not the Pi, not CI), with minisign installed (`apt install minisign`, `brew install minisign`):
   `minisign -G -W -p handy-server.pub -s handy-server.key`. `-W` leaves the secret key without a password, since CI
   signs unattended; the GitHub secret is what protects it. Keep `handy-server.key` somewhere safe offline (a password
   manager) and never in the repo.
2. GitHub > the repo > Settings > Environments > `release` (the environment the launcher and music releases already
   sign in):
   - **Environment secrets** > Add: name `SERVER_MINISIGN_KEY`, value the whole content of `handy-server.key` (both
     lines). Not a repository secret: the release reads it only from this environment, so a workflow pushed to some
     branch can't.
   - **Deployment branches and tags**: add a tag rule `server-v*` next to `launcher-v*` and `music-v*`, or the release
     job can't enter the environment.
3. Pin the public key, the second line of `handy-server.pub` (it starts with `RW`), in both scripts:
   `KEY='RW...'; sed -i "s|^SERVER_SIGNING_PUBKEY=.*|SERVER_SIGNING_PUBKEY=\"$KEY\"|" server/deploy/install.sh server/deploy/update.sh`,
   then `cargo test deploy_scripts` (from `server/`) and commit.
4. Optional, as the review suggests: turn on immutable releases (Settings > General > Releases). The `release`
   environment's required reviewer, if it has one, then approves each server release too.

Until the key is pinned, the scripts refuse every release ("No release signing key is pinned"). A `server-v*` tag
pushed before steps 2-3 stops at the signing job, with nothing pushed or published: delete the tag, finish the setup,
and tag again.

- A fresh install from `main` refuses releases older than 0.24.0 (they aren't signed).
- **A new key** (lost or leaked): make a new pair, replace the secret and the pinned key, release, and run the new
  `update.sh` from `main` on every server - the scripts refuse anything the old key didn't sign, by design.

### Rolling back the server

An older binary refuses to start against a database that a newer version has already migrated, so roll back both
together, from the backup `update.sh` made:

```
sudo systemctl stop kid-phone-server
B=/var/backups/kid-phone-server/<timestamp>        # the one taken just before the bad update
sudo cp "$B/kid_phone_server" /opt/kid-phone-server/kid_phone_server
sudo rm -f /opt/kid-phone-server/data/kidphone.db-wal /opt/kid-phone-server/data/kidphone.db-shm
sudo cp "$B"/kidphone.db* /opt/kid-phone-server/data/
sudo chown kidphone:kidphone /opt/kid-phone-server/kid_phone_server /opt/kid-phone-server/data/kidphone.db*
sudo systemctl start kid-phone-server
```

Anything the devices reported after that backup (status, locations) is lost. The server never updates itself, so
nothing installs the bad version again until you run `update.sh`.

Rolling back below 0.67.0 brings back a server whose Software Updates and Backups buttons (Update, Restart, Reboot,
Format, Restore) only write `data/update_requested`, which nothing reads any more: they do nothing, so update and
restore by hand as above. `/var/lib/kid-phone-server/installed_version` still names 0.67.0 or later, so a later
`update.sh` to a 0.66.x release needs `KPS_ALLOW_DOWNGRADE=1`.

## Launcher provisioning settings

The server needs to know which launcher build to provision. These live in `/opt/kid-phone-server/.env` (restart the
service after editing: `sudo systemctl restart kid-phone-server`):

| Setting | Default |
|---|---|
| `LAUNCHER_ADMIN_COMPONENT` | `me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver` |
| `LAUNCHER_APK_URL` | `https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk` (only launcher releases are ever "latest") |
| `LAUNCHER_SIGNATURE_CHECKSUM` | none - required for the provisioning QR code |
| `PHONE_SERVER_URL` | none - the phones' server address in the setup QR while Settings > Phone Setup has none saved; an address saved there wins, and the page says which one is used. `https://`, or `http://` to a Tailscale IP address (100.64.0.0/10, `fd7a:115c:a1e0::/48`) - taken with a warning in the log, on Phone Setup and on the Connect page (Tailscale encrypts the way, but only https proves the server to the phone; prefer `https://<name>.ts.net` from `tailscale serve`); any other `http://` (a LAN address, a `*.ts.net` name) is ignored with a log line, and Phone Setup refuses it the same way. The phones apply the same rule to the address they store (launcher security review S-M1): a phone set up earlier with a plain-http LAN address stops reaching the server once Vibb One is updated, until the https address is typed in its Settings (Server URL, behind the parent code) |
| `PHONE_RECOVERY_URL` | none - the tailnet recovery address for a phone whose certificate check refuses this server (design 76; "Tailnet recovery address" above): plain `http://` to a Tailscale IP only, e.g. `http://100.101.102.103:3101`; anything else is ignored with a log line. Unset: the setup QR's address when it is one, else this box's tailnet address on a listener that answers it, when the server can tell |
| `SERVER_RELEASE_REPO` | `palchrb/vibb-launcher` (where the Updates page looks for new server versions - only `server-vX.Y.Z` releases count) |
| `MET_CONTACT` | none - your e-mail address or website, added to the User-Agent of the server's requests to MET Norway for the lock screen's weather (design 75; "Weather on the lock screen" below). MET asks for it and may block clients without a contact; with it MET can tie the grid cells it is asked for to that address. 3-100 printable ASCII characters without quotes (no æ, ø, å), else ignored with a log line |
| `PWA_DEFAULT_LANG` | none - `en` or `nb`: the admin pages' language for sign-in pages and for an account's first signed-in request (stored once as that account's language), ahead of the browser's `Accept-Language`; unset = the browser's language, else English. `?lang=` and an account's own choice (Settings > Account > Language) always win; any other value is ignored with a log line |

`LAUNCHER_SIGNATURE_CHECKSUM` is the SHA-256 of the launcher's **signing certificate** (not of the APK file),
base64url-encoded without padding: 43 characters. Until it is set, Devices > Provision shows a warning and no QR code.
Compute it either from the release keystore or from a release APK; both must print the same value:

```
keytool -exportcert -alias handy -keystore handy-release.p12 \
  | openssl dgst -binary -sha256 | openssl base64 -A | tr '+/' '-_' | tr -d '='

apksigner verify --print-certs kids-launcher-mdm.apk | sed -n 's/.*SHA-256 digest: //p' \
  | xxd -r -p | openssl base64 -A | tr '+/' '-_' | tr -d '='
```

An invalid value (wrong length, `+`/`/`/`=` characters) is logged at startup and ignored.

**The server checks what the QR names** (design 86): it downloads the APK at `LAUNCHER_APK_URL` (at start, then at
most once an hour when Phone Setup or a phone's Connect page is opened; "Check Again" on either page checks at once),
reads its package, version and signing certificate, and shows the result under Settings > Phone Setup, "Launcher for
new phones": "New phones get Vibb One X", or what is wrong. A signer that isn't `LAUNCHER_SIGNATURE_CHECKSUM`'s, another
package than `LAUNCHER_ADMIN_COMPONENT`'s, or an address that gives no APK means a phone would stop at "Can't set up
device": the Connect page then shows the warning instead of a QR code. The file is deleted as soon as it is read.

`releases/latest` only ever serves a stable launcher release, so while the newer launchers are release candidates
(prereleases) the QR installs an older one, and the page says so ("The setup QR code installs Vibb One 0.34.0 from
21 Oct - older than 0.39.0-rc.6"). To set phones up with a specific release - a release candidate too - point the QR
at that release's own asset and restart the service:

```
LAUNCHER_APK_URL=https://github.com/palchrb/vibb-launcher/releases/download/launcher-v0.39.0-rc.6/kids-launcher-mdm.apk
```

The signer check still guards it: an APK signed with another key shows the warning and no QR code. Remember to move it
on (or remove the line, back to `releases/latest`) when a newer release should be the one new phones get - the page
says when it is older than the newest launcher release.

### The launcher's own self-update

The launcher updates itself through the Apps catalog, like any other app. **Nothing to add**: since 2026-10-15 the
server creates the launcher's row at startup when no app under Apps is marked as the launcher, and logs
`created the launcher's catalog row (palchrb/vibb-launcher, launcher-v*, me.vibb.launcher)`. It is:

- name: **Vibb One** (the launcher's name on the phone since design 34; migration 0077 renamed a row still called
  "Vibb" or "Kids Launcher" - any other name is yours and stays)
- source: GitHub releases, the repo of `LAUNCHER_APK_URL` (`https://github.com/<owner>/<repo>/releases/...`; the
  default is `palchrb/vibb-launcher`); the catalog skips the repo's `server-v*` and `music-v*` releases and takes the
  newest `launcher-v*` release that carries the APK. A `LAUNCHER_APK_URL` elsewhere (your own host) creates **no**
  row and the log says so: add the launcher under Apps yourself and turn on "This is the launcher app"
- "File name contains": `kids-launcher-mdm.apk`, and "Tag starts with": `launcher-v`
- "Include pre-releases": **off** (release candidates are published as pre-releases and must never reach the phones)
  - an existing row that watched `palchrb/kids-launcher-mdm` is moved to the monorepo by migration 0031, which also
    turns this off (with it on, every `launcher-vX.Y.Z-rc.N` would roll out to every phone)
- "This is the launcher app": on (every phone gets it; it can't be removed while on)
- package: the one in `LAUNCHER_ADMIN_COMPONENT` (`me.vibb.launcher` by default) - a release of any other package is
  refused and shown on the row, never sent to a phone; the first release's APK pins the signing key (a row that still
  says `com.kidslauncher.mdm` or `.debug` is renamed by migration 0037)

A launcher row you made yourself is never changed. If you added the launcher by hand **without** turning on "This is
the launcher app" (or uploaded it as a manual app with the launcher's package), the server creates no second row and
logs a warning naming yours instead: if it is the launcher, turn the switch on on its page (Source & Signing). To stop launcher updates, untick "Enabled" on its page - a launcher row that is deleted comes
back at the next start. The **music app** is still added by hand (Music › Settings, "Add to the App Catalog").

A release launcher can't be downgraded: Android refuses an install with a lower versionCode. If a launcher release is
broken, fix it forward by releasing the old (or fixed) code under a higher `launcher-v*` tag.

## Vibb music library (server 0.21.0, design 21 step 1)

The Music tab holds the library for the Vibb music app; each phone has a Music page (`/devices/{id}/music`, the
Music row on its page).

- **Order**: install this server (`server-v0.21.0` or later) **before any stable `music-v*` release** is published in
  the repo. Older servers don't know the `music-v` tag prefix, and their launcher row (blank asset filter) could pick
  `vibb-music.apk` as the launcher update. Migration 0049 sets the launcher row to `launcher-v*` releases and
  `^kids-launcher-mdm\.apk$` (only where its cached release already is a `launcher-v*` one) and adds the music app's
  catalog row; anything else: Music › Settings, "Add to the App Catalog", and the app's "Tag starts with" (Source & Signing).
- **Storytel key**: the family's Storytel login is encrypted with `MUSIC_SECRET_KEY` (base64, 32 bytes) from `.env`,
  or else with the key file `/opt/kid-phone-server/data/keys/music-secret.key`, which the server writes (0600, in a
  0700 directory) at its first start - `data/` is the only place the service may write (`ProtectSystem=strict`).
  `MUSIC_SECRET_KEY_FILE` points elsewhere, but only somewhere the service can write. No backup holds the key: the
  backup zip has the database and the image stores, `update.sh` copies the database files - don't copy `data/keys/`
  anywhere yourself. A database restored
  on another box, or a lost key file, means entering the login again in Music › Settings. Without a usable key the
  Storytel form is off and the phones get 503.
- **Smoke check after the update**: `journalctl -u kid-phone-server -b | grep -i storytel` shows "Storytel logins are
  sealed with ..." - never "Storytel logins are off: ..." (that line says which file and why).
- **Own files** are stored in `data/music_files/` and are **not** in the backups (too big). After a restore without
  them they are listed as missing (the phones keep their copies); upload them again to restore them in place. Their
  covers are in the backups. Files left by restoring an *older* backup show in Music › Settings ("Files no entry
  uses") with Delete Files… - nothing is deleted by itself.
- Rolling back to 0.20 means restoring the `update.sh` backup: 0.20 refuses to start on a database with migration 0049
  (and 0050, the library revision).

## Vibb music: the server sweeps the sources (server 0.22.0, design 21b)

The server now lists every NRK and RSS entry itself and sends the phones the lists; the phones only download the
audio from the source.

- **What it fetches**: psapi.nrk.no (one 5-episode page per NRK entry at each check, manifests only for new
  episodes), the RSS feeds (a conditional GET), gfx.nrk.no and feed images (covers, at most once a day). One request
  at a time, 200 ms apart, 4 s between entries. Music › Settings, "Check for new episodes", sets how often (every 1/3/6/12/24 h, default 6 =
  the Vibb Pi; NRK every 12 h at 6). Check now on an entry checks it at once (15 min per entry, 12 an hour, 48 a day).
- **Addresses**: a feed on the home LAN or the tailnet works (its first check fixes it as LAN). A public feed is never
  followed to a private address (redirects, DNS answers and media URLs are checked).
- **The first start** lists everything within about 20-30 minutes for a 40-entry library (a 100-episode NRK podcast
  is about 103 requests the first time). The log says what each check did:
  `journalctl -u kid-phone-server | grep "music sweep"` (`entry 12 "Abels tårn": 1 request, 0 new`).
- **Checks on the Pi after the update**: an NRK podcast, an NRK series of more than 100 episodes, a
  `serie/<slug>/<programId>` link, two RSS feeds (one oldest first, one with a rolling window) and a LAN feed: the
  first fill, the logged request counts and the 304s (`curl -H "If-None-Match: ..."` of
  `/api/devices/music/entries/{id}/items` with a phone's token); a restart, and a `kill -9` mid-fill (it doesn't loop:
  the entry waits 15 minutes, then its fill starts over - a fill is written in one go); a setting change and the Check now limits; a 21a import filled in the background; a 404 feed keeping
  its list; a public feed that redirects to a LAN address refused; yesterday's NRK series HLS URL still playing; the
  Music tab's rows on a phone in light and dark, iOS Safari included.
- **Order**: this server before any stable `music-v*` release, as for 0.21.
- Rolling back to 0.21 means restoring the `update.sh` backup: 0.21 refuses to start on a database with migration
  0051.

## Vibb music: episode art (design 27, migration 0070)

The sweep also fetches each NRK/RSS episode's own picture and the phones show it in the player.

- **What it fetches**: an episode's picture (NRK's 512 px variant, an RSS item's `itunes:image`) from gfx.nrk.no or
  the feed's image host, at most 20 a check and 400 a day server-wide, the first 200 episodes of each entry in play
  order, newest first. A picture that is the series cover (by URL, or by its print under another URL or size) costs
  nothing more. The first days after the update fill the back catalogue (30 NRK entries x 100 episodes is at most
  about 3,000 requests, so a week or more); after that it is one request per new episode with a new picture. A
  picture whose URL only changes its query (signed or cache-busting links) isn't fetched again; one that moves is
  fetched again at most weekly. An image host that is down costs 3 requests a check and no picture's tries. Once,
  right after the update, each NRK entry's next check reads its root and its episode pages again for the bigger
  picture (3-4 requests per entry). The log line says `, art 7 (3 own, 4 same)`.
- **Storage**: `data/music_art/` holds the pictures (512 px JPEGs, about 40-70 KB each; expect up to ~150 MB for 30
  NRK entries with pictures for every episode). It is **not** in the backups: after a restore the server forgets
  the pictures it can't find and fetches them again, a few at a time. Pruned with the episodes.
- **Checks on the Pi after the update**: an NRK podcast with episode pictures, one without (the log says `same`), an
  RSS feed with per-item pictures; the log lines and `requests`; `du -sh data/music_art` after a day.
- Rolling back means restoring the `update.sh` backup: an older server refuses to start on a database with migration
  0070.

## Vibb music: Spotify (server 0.26.0, design 26c, migration 0074)

Spotify is opt-in: nothing changes until a parent sets up a Spotify app on Music › From Spotify. The server ships no
Spotify client ID - each family registers its own (bring your own app, Spotify's Development mode).

- **The app**: the page walks through it - developer.spotify.com/dashboard with a **Premium** account, a new app (a name
  not starting with "Spot"), the redirect URI the page shows, APIs **Web API** and **Web Playback SDK**, each child's
  Spotify account under User Management (one per phone; **at most 5**), then the **Client ID** (never the secret: the
  server uses PKCE and stores no client secret) into the page. Or in `.env`:

  | `.env` setting | Default | What it does |
  |---|---|---|
  | `SPOTIFY_CLIENT_ID` | unset | The app's client ID (32 letters and digits). Wins over the page's, whose field is then read-only. A client ID is public; it is stored in the clear. |
  | `SPOTIFY_REDIRECT_URI` | unset | `https://<the PWA's host>/music/spotify/callback`, or exactly `http://127.0.0.1:43117/callback` (the paste, below). Wins over the page's. |
  | `SPOTIFY_ARTIST_ALBUMS` | unset (off) | `1` (or `true`) lists an artist as the tracks of its newest 20 albums and singles - with a track list, resume and the order setting - instead of playing it as a Spotify context in Spotify's order. Opt-in: turn it on once the design's checklist item 18 shows the albums endpoint works for your app; an artist Spotify refuses falls back to a context. |

  A value that doesn't fit is ignored with one warning in the log at start.
- **Spotify audiobooks** (2026-11-09; `SPOTIFY_AUDIOBOOK_SPIKE` is gone - it was the test): an audiobook shared as a
  show link is listed as its chapters, without the free "sample" chapter, and its entry page says that a Spotify
  audiobook needs listening time on the child's own account (on Premium Family the plan manager buys "Audiobooks+ for
  Plan Members" for the child). Without it Spotify plays the sample instead, and the phone pauses that and says the
  book needs listening time. The server log says what a show held (`a Spotify show of audiobook chapters`: the
  chapters, the samples left out, the chapters' uri kind; `a Spotify show listed nothing`).
- **A Spotify login needs the PWA on https, or the paste.** Spotify accepts only https redirect URIs (or a loopback
  address). Behind `tailscale serve` or Caddy with https, the redirect URI is the PWA's own https address, and a login
  must be started **from that address** (the page refuses another address of the server with a link to the right one).
  The page suggests the https form only when the proxy says `X-Forwarded-Proto: https`; if it suggests the loopback form
  behind your https proxy, type the https form in. Without https (e.g. Docker on the LAN over http) use the loopback
  redirect: after Spotify's consent the browser shows "can't connect" with the code in its address bar - paste that
  address into the page. The callback answers without a session but stays behind the admin gate, so the browser must
  reach the PWA as usual (tailnet). Log the children in from a private window - one account per phone, the child's own;
  your own Spotify account is never logged in (you paste the links, and a playlist lists its tracks only when a child's
  account owns it or collaborates on it). A login lasts 6 months (Spotify's limit): the page and the phone's card warn
  14 days before.
- **Keys and backups**: each account's refresh token is sealed with the music key (`MUSIC_SECRET_KEY` or
  `data/keys/music-secret.key`), like the Storytel login. Backups hold the sealed tokens, never the key: a restore on
  another box, or a lost key, means logging the accounts in again.
- **What it fetches**: `accounts.spotify.com` and `api.spotify.com` (one request at a time through the music gate),
  Spotify's short links (`spotify.link`, `spotify.app.link`) when one is added, and covers and track pictures from Spotify's image hosts. Playlists
  and shows are checked at the sweep's interval (a playlist's tracks are read again only when it changed, or after a
  day), albums, tracks, episodes and artists daily. The quota is per developer account and shared with the phones'
  play calls: if Spotify says it is used up, the server pauses its Spotify reads for an hour.
- **The phones**: a phone gets Spotify entries only while its Music page (Devices › the phone › Music) says "Plays as
  <account>". For such a phone the Web Filter never blocks Spotify's hosts - a parent's own block of `spotify.com` or
  `scdn.co` is overridden on that phone for every app (the phone's Web Filter page says so, and marks the blocks).
- **Testing only, never on a real server**: `SPOTIFY_ACCOUNTS_BASE` and `SPOTIFY_API_BASE` point the server at
  `scripts/fake-spotify.py` and are honoured **only** as `http://127.0.0.1:<port>` or `http://[::1]:<port>` (anything
  else is ignored with a warning; while one is set Spotify requests may go to a private address, and the start logs a
  warning naming them). `INSECURE_COOKIES` also drops `Secure` from the login cookie (the emulator loop over http).
- **Order**: the launcher and the music app that read design 30's two switches first, then the server with design
  30's migration 0073 (0.25.0), then this one with 0074 - or 0073 and 0074 in one release, **never 0074 before
  0073** (the pre-migration backup compares the highest version only). A dev database that ran the Spotify branch
  before its merge of main holds its old 73 and stops at `VersionMismatch(73)`: start it on a fresh `data/`.
- **Smoke check**: `journalctl -u kid-phone-server -b | grep -i spotify` shows no "ignored" warning (and no override
  line on a real server); the Music page's "From Spotify ›" row says "No app", then "Signed in" after a login.
- Rolling back means restoring the `update.sh` backup: an older server refuses to start on a database with
  migration 0074.

## The Locate map (design 33)

The parent's browser loads the map's tiles directly from OpenStreetMap with an origin-only `Referer` (OSM sees
the PWA's address, e.g. its tailnet host name, never a path), so the parent's browser needs to reach
`tile.openstreetmap.org`; the server itself never contacts OSM.

## Notifications (design 35)

The bell and the inbox (`/notifications`) need nothing. Notifications on the parents' devices (Web Push) need:

- **Outbound HTTPS (443)** from the server to the browsers' push services: `*.push.apple.com` (iPhone, iPad, Mac
  Safari), `fcm.googleapis.com` (Chrome, Edge on Android, Samsung Internet, Brave, Opera), `jmt17.google.com`
  (a Chromium built without Google's API keys: Debian's, Raspberry Pi OS's),
  `*.notify.windows.com` (Edge on Windows) and `updates.push.services.mozilla.com` (Firefox) - through the Pi's
  firewall and, in Docker, the container's network. The server sends to no other host.
- **The right time.** Each push is signed with the time in it; a wrong clock makes the push services refuse every one
  ("The push service refused the server's signature - check the server's clock" on Notifications). The Pi has no clock
  of its own: after a power cut it needs NTP (`timedatectl` shows "System clock synchronized: yes").
- **`PUSH_PAYLOAD`** (optional) in `.env`: `declarative` (the default: Safari 18.4+ reads the notification from the
  payload itself and shows it the moment it arrives, badge included, without running the worker - not `mutable`:
  with that flag iOS 26 ran the worker and then displayed nothing, whether the worker replaced the notification or
  left it alone, found 2026-10-19/20) or `classic` (a plain Web Push payload, the worker's `push` shows it
  everywhere). Chrome, Edge and Firefox treat both the same. No receipt comes from a Safari device on the
  declarative payload, so Apple's acceptance (201) is the delivery: the inbox says "Delivered to iPhone · Safari",
  Getting Notifications "Last delivered …", and such a device is never "May not be showing". The server log's
  `push receipt` lines say which worker event confirmed each push from the other browsers
  (`event=push|push-declarative|pushnotification|click`) and the worker's version; on Safari a tap on the
  notification opens its link without the worker.
- **`VAPID_SUBJECT`** (optional) in `.env`: a `mailto:` or `https:` address the push services may contact about the
  server's pushes. They read it in clear with every push, so without it the server sends the project's page
  (`push::DEFAULT_SUBJECT`, the same for every server), never the PWA's own address with your tailnet name (security
  review P4, 0.57.0; before, each device's own address was sent). Existing subscriptions keep working.
- **The key** `data/keys/vapid-p256.pk8` is made on the first start (0600) and is never backed up, like the Storytel
  key. After a restore onto a new machine (or a lost key file) every device's notifications are turned off: turn them
  on again on each device (Notifications › This Device). A damaged key file is left as it is and notifications stay
  off until it is fixed or removed (the log names the file).
- **Tailscale on the parent's device.** A device that shows a notification confirms it to the server; without the
  tailnet the confirmation can't arrive and Notifications says it "may not be showing" after three unconfirmed
  pushes, or one a day old.

## Weather on the lock screen (design 75, migrations 0122 and 0126)

Device › Weather turns it on per phone (off by default). The server then asks, only when a phone asks it and its
cached answer is due (per grid cell of about 2 x 2 km: at least 30 minutes apart in the Nordic region, at least 3 hours
apart in the rest of the world, or longer when MET's own cache headers say so - MET's terms of 2026-11-05), one outside service:

- **MET Norway** (`api.met.no`, HTTPS 443): Locationforecast 2.0 for the forecast, Nowcast 2.0 when the kid opens the
  card. MET's terms ask every client to identify itself with a way to contact its operator, and block clients that
  don't. The server sends `vibb-server/<version> github.com/<SERVER_RELEASE_REPO>`; **set `MET_CONTACT`** in `.env` to
  your e-mail address or website (3-100 printable ASCII characters - no æ, ø, å - and no quotes) and it is added, so MET can reach you rather
  than block every install that shares the string (the log warns once while it is unset). With it, MET can tie the
  cells it is asked for - roughly where your family is, to about 2 km - to that address. MET's data is CC BY 4.0.

MET's edge sometimes answers a rarely asked cell with a quick 503 before it serves it: the server asks again within
seconds (MET's `Retry-After` when it is at most a minute, else after 5 s and 15 s; at most three requests) and only then
waits 5-60 minutes, serving the old forecast. The log shows `status_5xx` only when the quick tries did not get it, and an `info` line
when a retry got it; `cut` means MET sent a forecast cut short, whose whole hours were kept.

No place name is looked up: until 2026-11-04 the server also asked Kartverket (`api.kartverket.no`) for the nearest
settlement's name once per cell; that lookup is gone, and migration 0126 drops its cache (`weather_places`).

The phones only ever ask this server. The cache (`weather_cells`) has no phone in it: a cell nobody asked for in 48
hours (or the longest location retention, when that is shorter) goes, and a phone's Location set to Off erases it at
once. **Backups** (and the pre-migration and pre-restore copies) keep them as they keep the fixes.

## App push (UnifiedPush) (design 108, migrations 0152 and 0153)

Vibb One is the phone's UnifiedPush distributor, and this server is the push server behind the addresses it hands to
the phone's apps - Element X first - and a Matrix push gateway. A push comes in from the app's own server (a Matrix
homeserver, a Mastodon instance), waits in the server's queue and wakes the phone over its existing event stream; no
Google, no ntfy app and no third party. The routes live on the **phone listener only** (`DEVICE_BIND_ADDR`), and
nothing about a push is logged.

- **Turn it on:** Settings › App push, the phones' public address (`https://phones.example.com`: `https://` and the
  name only, at the root of the name - Element X looks for `https://<name>/_matrix/push/v1/notify`). Saving it turns
  app push on for every phone whose own switch is on (Device › Apps › App push, on by default). A server reachable
  only on the tailnet gets no app push: the apps' servers post from the internet, and Synapse refuses Tailscale
  addresses for pushes anyway. Option (b) serves app push only with `DEVICE_BIND_ADDR` set.
- **Element X** (its Play build stays): once per account, in Element X, Settings › Notifications › Advanced › Push
  provider › Vibb One. Device › Apps says when an allowed Element X hasn't connected. Switching back to Google is the
  same setting.
- **Caddy:** a site that proxies the whole phone host (`reverse_proxy 127.0.0.1:3101`, as above) serves app push as
  it is; add `request_buffers 8KiB` inside its `reverse_proxy` block (as in the example above), so Caddy holds a client
  that sends its body slowly and the server's push slots never wait on one. The clients' addresses count as everywhere
  on the phone listener (`TRUSTED_PROXIES` names the proxy). If the site has access logging on, skip `/up/*`: the path
  is a capability (`log_skip /up/*`, Caddy 2.8 and later).
- **nginx:** `location /up/ { proxy_pass http://127.0.0.1:3101; access_log off; client_max_body_size 8k; }` and
  `location = /_matrix/push/v1/notify { proxy_pass http://127.0.0.1:3101; client_max_body_size 8k; }`.
- **Check from outside** (mobile data):
  - `curl -s https://phones.example.com/_matrix/push/v1/notify` -> `{"unifiedpush":{"gateway":"matrix"}}`;
  - `curl -s -o /dev/null -w '%{http_code}' -X POST --data x https://phones.example.com/up/AAAAAAAAAAAAAAAAAAAAAAAAAAA`
    -> `410`.
  Settings › App push shows when an app last checked the address and when the last push came in.
- **A homeserver on your own LAN** that resolves `phones.example.com` to a private address needs it in Synapse's
  `ip_range_whitelist` (Synapse refuses private ranges for pushes by default).
- **Changing the address:** the old one keeps working 30 days while the phones hand every app its new address; change
  it again within those days and every replaced address keeps its own 30 days. Emptying the field turns app push off
  for every phone and forgets the old addresses.
- **Limits:** a push is at most 4096 bytes and waits at most 24 hours; each phone takes 120 at once and then one every
  5 seconds (every push wakes the phone, so a steady flood would drain its battery; Device › Apps names an app that
  pushes about 300 times a day or more), each app address 30 and then one every 2 seconds; each sending address 40
  requests at once and then 20 a second; an address that was removed answers `410` for 30 days.
- **The server's clock must be right** (NTP; the VM's is). A registration made with a VAPID key (Fedilab with a Mastodon
  4.4+ account) only takes pushes signed by the app's server, and the signature carries an expiry the server judges
  against its own clock: with the clock badly off, signed pushes are refused with `503` and `Retry-After: 300` (which
  keeps the sender's subscription) until it is fixed (design 108b §4.8).

## Useful commands on the Pi

- Check it's running: `systemctl status kid-phone-server`
- View logs: `journalctl -u kid-phone-server -f`
- Restart it: `sudo systemctl restart kid-phone-server`

## Backups

Built in - see Settings › **Backups** in the admin UI. "Back Up Now" makes one on demand, the Schedule group sets automatic ones; they stay in `data/backups/` on the server - the server never copies anything off the machine, so download one every so often (or copy `/opt/kid-phone-server/data/backups/` elsewhere yourself). Each backup in the list has Download, Restore… and Delete…; Upload a Backup… adds one from elsewhere. The database itself lives at `/opt/kid-phone-server/data/kidphone.db` if you ever need it directly.

**Restoring a backup** on a systemd install is done over SSH (the server never restarts itself): its Restore… in the
list shows the command for that file - only for a plain name (`backup-` and letters, digits, `.`, `_`, `-`, then
`.zip`; rename a file like `backup-... (1).zip` first), and quoted - the same as

```
echo 'backup-20261104-020000.zip' | sudo -u kidphone tee /opt/kid-phone-server/data/restore_request
sudo systemctl restart kid-phone-server.service
```

The server takes the marker at its next start (the same restore as the container's): the backup's `kidphone.db` must
be an SQLite database of at most 1 GiB, the database it replaces is kept in `data/backups/prerestore-<time>/` (the
newest 3), a backup that won't open is undone, and everyone signs in again. A backup that isn't in
`data/backups/` yet goes there first (Upload a Backup…, or copy it in as the `kidphone` user). In Docker Restore…
asks for the file name and restarts the container itself ("Docker" above).

- **Keep backups as safe as the admin password.** A backup (and a copy of the data volume) holds
  everything: every location, the PIN hashes, the password hash - and the 2FA secret, which is kept unsealed on
  purpose: sealing it would tie every sign-in to the key file in `data/keys/`, and losing that file would lock you out
  of the admin (design 22).
- Since 0.24.0 a backup (and the pre-migration copy) holds **no admin sessions** and **no
  Tailscale key for the phones**, so a stolen copy signs no one in and joins nothing to the tailnet; a restore signs
  everyone out, and the next phone's setup needs a new key in Settings > Phone Setup (it is one-time anyway). Everything in `data/` is readable by the service user only. A copy you keep
  elsewhere is as private as the place you keep it: an encrypted disk, or at home.
- **A backup keeps data past the retention windows.** It holds every location, DNS log and event as they were when it
  was made, until the backup itself is deleted - so with 14 daily backups kept, data lives about 14 days past its
  window - and a copy you made elsewhere, until you delete it. A "Backups to keep" that isn't a number (only a
  hand-edited `data/backup_schedule.conf` has one) keeps 14, and is logged.
- Restoring a backup keeps the database it replaces in `data/backups/prerestore-<time>/` (the newest 3). Restores
  done by the old root helper (server 0.66 and older) kept theirs in `/var/backups/kid-phone-server-prerestore/`,
  which stays until you delete it.
- Since 0.23.0 the server also copies the database to `data/backups/pre-migrate-<from>-<to>.db` before an update
  changes it (the newest 3 are kept). Rolling back a Pi still uses the `update.sh` copy ("Rolling back the server");
  the pre-migration copy is a second one.
- The weather's caches (design 75: the grid cells asked for and their place names) are in every copy too, and stay
  there after a phone's Location is set to Off, as its fixes do.
- Since migration 0123 a backup also holds each contact photo's **kept original** (`contact_photo_originals/<hash>.jpg`:
  the photo as uploaded, at most about 3 MP, without location or camera data, for "Adjust crop"), stored in the zip as
  it is - a JPEG doesn't compress further. That is about 0.5-1.5 MB per contact with a photo against 30-60 KB for its
  square: 30 photos make each zip about 30 MB, so 14 kept backups need a few hundred MB in `data/backups/`. Removing a photo removes its original at once; the backups keep it until they are deleted.

## MollySocket

The server ships no MollySocket installer. An existing MollySocket install keeps running on its own until you remove it
(`sudo systemctl disable --now mollysocket`, then delete `/opt/mollysocket` and its `tailscale serve` on port 8443).
Vibb One is the UnifiedPush distributor (design 108), without a third-party relay: see "App push (UnifiedPush)" above.
