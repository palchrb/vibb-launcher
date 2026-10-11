# Deploying to a Raspberry Pi / DietPi

These steps get Kids Device MDM running on a Raspberry Pi Zero 2 W. Only 64-bit (aarch64) is supported.

## 1. Install

SSH into the Pi, then run:

```
curl -fsSL https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/install.sh | sudo bash
```

This downloads the newest server release (the newest `server-vX.Y.Z` release of the
[`palchrb/vibb-launcher`](https://github.com/palchrb/vibb-launcher) monorepo), sets it up as a background service that starts on boot, and prints an admin username/password at the end — **save that password**, you'll need it to log in the first time (and you'll be asked to change it immediately after).

The app only listens on the Pi itself (`127.0.0.1:3100`) by default — that's intentional for security.

## 2. Choose how it's reachable (server >= 0.23.0)

The server never terminates TLS and never listens publicly by itself: it binds loopback, and a proxy you run (Caddy,
or `tailscale serve`) brings HTTPS and decides who can reach it. Since 0.23.0 it has **two listeners**, so the admin
pages and the phones' API can be exposed differently:

| `.env` setting | Default | What it does |
|---|---|---|
| `BIND_ADDR` | `127.0.0.1:3100` | The admin listener: the PWA, and the phones' API while `ADMIN_DEVICE_API` is on. |
| `DEVICE_BIND_ADDR` | unset | The phone listener, e.g. `127.0.0.1:3101`: only the phones' API (`/api/devices/*`) and `/healthz`; every other path is a bare 404. |
| `ADMIN_DEVICE_API` | `on` | The admin listener also serves the phones' API (the URLs phones set up before 0.23.0 use). `off` once every phone uses the phone listener. |
| `ADMIN_PUBLIC` | `off` | The admin listener answers **only loopback and tailnet clients** (100.64.0.0/10, fd7a:115c:a1e0::/48); everyone else gets 403 (logged as `admin_refused` in the security log). `on` lets every client in - see (b) below. |
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | Proxies whose `X-Forwarded-For` is believed: exact proxy addresses (a range only if every address in it is one of your proxies). Your proxy on the same box needs nothing here. |
| `ADMIN_TAILSCALE_USERS` | unset | Only behind `tailscale serve`: the admin pages also need one of these Tailscale logins (comma list). Tagged phones carry no login, so a phone node can't open the admin even if a serve rule points it there. |
| `SESSION_INACTIVITY_DAYS` | `30` | How long an admin session lasts without use. |

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

Which URL a phone uses is still set per phone for now - the setup QR's server URL (Settings > Provisioning) or the
launcher's Settings > Server URL (behind the PIN). A phone set up with the embedded Tailscale key sends every request
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

Caddy gets its own certificates and replaces any `X-Forwarded-For` a client sends (never set `trusted_proxies` in
Caddy for this site). Don't add `encode`: the phones' event stream must not be compressed. Strip
`Tailscale-User-Login` in every site, so nobody can send it through Caddy. Put `DEVICE_BIND_ADDR=127.0.0.1:3101` in
`.env` and restart.

```
{
	email parent@example.com
	servers {
		timeouts {
			read_header 10s
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

One-time means one key per phone setup. Settings > Provisioning holds a single key, and every setup QR carries it:
before setting up a phone (or setting one up again), make a new key in the Tailscale admin console and paste it
there. The phone uses it once; after that the key, and any photo of the QR, is worth nothing. Remove it from
Provisioning afterwards if you like - it is dead either way. A phone already set up keeps working on its stored node
state; the key only matters for a setup. The key is write-only on Settings > Provisioning since 0.23.0. (The
launcher still keeps its copy of the used key; deleting it after the first connect comes with the launcher's security
wave.)

### Phones' long-lived connection through a proxy

The phones hold a long-lived connection to `/api/devices/commands/stream` (server-sent events, the only way the server
nudges a phone) that is quiet except for a keepalive every `SSE_KEEPALIVE_SECS` (240 s by default). Caddy and
`tailscale serve` pass it through unbuffered as they are. **Any other proxy needs a read timeout of at least 300 s, no
buffering and no compression on it** - for nginx `proxy_buffering off; proxy_read_timeout 330s; proxy_http_version
1.1;`. Cloudflare's proxy cuts connections idle for 100 s: use DNS-only there, or `SSE_KEEPALIVE_SECS=90`.

### Changing how it's exposed

To stop the public phone URL: point the phones back at the tailnet URL first, then remove the Caddy site (or the
Funnel), and finally the DNS record. Keep the CAA record as long as you own the domain.

## Signing in and recovery (server >= 0.23.0)

- Every sign-in is the password, then a 2FA code. A code works once. Wrong passwords never lock the account (only
  the address that sent them, for an hour after 15); wrong 2FA codes do: 5 lock it for 15 minutes, every further lock
  doubles, up to 24 hours. After a sign-in that followed wrong codes, the Devices page says how many and from where:
  whoever typed them knew the password - change it.
- Changing the password or resetting 2FA signs out every other session; Account > "Sign out everywhere" signs out
  this one too.
- **Lost the authenticator and signed out?** On the Pi:
  `cd /opt/kid-phone-server && sudo -u kidphone ./kid_phone_server reset-2fa admin` (in Docker:
  `docker compose exec handy /app/kid_phone_server reset-2fa admin`). It removes the 2FA setup, the lock and its
  count, signs out every session, and **replaces the password**: the new one is in `data/initial-admin-password`
  (on the Pi `sudo cat /opt/kid-phone-server/data/initial-admin-password`; in Docker copy it out as for the first
  start). Sign in with it, set a new password, then a new authenticator. Replacing the password matters: the lock
  you are recovering from means someone else knows the old one.
- Updating to 0.23.0 signs everyone out once (the session cookie is now `__Host-handy`; reopen the installed PWA on an
  iPhone and sign in again), and enrollment codes that were open stop working: make a new one on the device page.

## Docker (server >= 0.23.0)

The server also ships as a multi-arch image (`linux/amd64`, `linux/arm64`): `ghcr.io/palchrb/handy-server:X.Y.Z`
(and `latest`, the highest release). The Pi Zero 2 W stays with the install script above; the image is for a home server or VPS. It runs
as uid 65532 with no shell, keeps everything in `/app/data`, and listens on `127.0.0.1:3100` by default.

**Recommended: the host's network, with the host's Tailscale and Caddy.** The proxies' defaults then hold, and Docker
never hides the client's address. `compose.yaml`:

```yaml
services:
  handy:
    image: ghcr.io/palchrb/handy-server:${HANDY_VERSION}
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

`.env` next to it (the settings from section 2 and "Launcher provisioning settings" go here too):

```
HANDY_VERSION=0.23.0
TZ=Europe/Oslo
DEVICE_BIND_ADDR=127.0.0.1:3101
LAUNCHER_SIGNATURE_CHECKSUM=...
```

`TZ` matters: backups and the update check run on local time, and the image is UTC otherwise. Then
`docker compose up -d`. With no `ADMIN_PASSWORD` set, the first start writes a password to
`/app/data/initial-admin-password` (the log names the file, never the password). The image has no shell, so copy it
out: `docker compose cp handy:/app/data/initial-admin-password . && cat initial-admin-password && rm
initial-admin-password`. Sign in as `admin` and change it - the server deletes its copy then.

- **Volumes**: `/app/data` holds the database, backups and caches; `/app/data/keys` (the Storytel key) is its own
  volume, so copies of the data volume don't carry it. Use named volumes: a bind-mounted directory isn't seeded from
  the image and must belong to uid 65532 (`chown -R 65532:65532 dir`), or the server stops with "data/ is not
  writable by uid 65532".
- **Updating**: the Updates page says when a version is out. Set `HANDY_VERSION=X.Y.Z` in `.env`, then
  `docker compose pull && docker compose up -d`. Before migrating the database, the new version copies it to
  `data/backups/pre-migrate-<from>-<to>.db` (the newest 3 are kept).
- **Rolling back**: set the old `HANDY_VERSION`, put the pre-migration copy back, start:

  ```
  docker compose stop handy
  docker run --rm -v handy-data:/data busybox sh -c 'cp /data/backups/pre-migrate-<from>-<to>.db /data/kidphone.db && rm -f /data/kidphone.db-wal /data/kidphone.db-shm && chown 65532:65532 /data/kidphone.db'
  docker compose up -d
  ```

  (The volume's name may carry the compose project's prefix: `docker volume ls`.)
- **Restoring a backup** from the Backups page works in the container too: the server stops, swaps in the backup at
  its next start (Docker's restart policy brings it back), and keeps the previous database in
  `data/backups/prerestore-<time>/` (the newest 3 are kept); if the backup won't open, the previous database goes
  back. A backup whose database is over 1 GiB is refused.
- The Updates page has no OS, Tailscale or reboot controls in the container, and the Backups page no external drive:
  copy `data/backups` off the host yourself.
- **Recovery**: `docker compose exec handy /app/kid_phone_server reset-2fa admin` (it also replaces the password,
  see "Signing in and recovery").

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
settings > Change visibility > Public. The release workflow tests every image (amd64 natively, arm64 under QEMU)
before it gets a version tag, and publishes the GitHub release only after that.

## Updating

```
curl -fsSL https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/update.sh | sudo bash
```

Downloads the newest stable `server-vX.Y.Z` release (prereleases and drafts are skipped), checks its signature
("Release signing" below) and the release's published `.sha256`, and swaps the binary in place. The in-app "Update
now" runs the `update.sh` that release carries, signed (not a branch's copy), from a file, never piped. Doesn't touch your `.env`. The download is unpacked and
checked before the service is stopped, so a failed download leaves the old version running; if anything fails after the
stop, the service is started again. Before the swap it copies the database and the old binary to
`/var/backups/kid-phone-server/<timestamp>/`, because the new version may migrate the database on its first start. Old
backups are pruned (newest 3 kept) only after the new version is running, so failed retries can't push out the
pre-update copy.

To install from a different fork, prefix `bash` with `KPS_REPO=owner/repo` (for both `install.sh` and `update.sh`):
`... | sudo KPS_REPO=someone/vibb-launcher bash`. The fork must keep this repo's layout (`server/deploy/`) and publish
server releases as `server-vX.Y.Z`. The root-side updater remembers the repo it was installed from.

**Installs from before the monorepo** (installed from `palchrb/kid-phone-server`): the server itself follows the move
(`SERVER_RELEASE_REPO=palchrb/kid-phone-server` and the old `LAUNCHER_APK_URL` in `.env` are read as the new defaults,
and a launcher row in the Apps catalog that watches `palchrb/kids-launcher-mdm` is repointed by a migration), but the
root-side updater still fetches `update.sh` from the old repo. Re-run the installer once from the new URL above; it keeps
`.env` and the database. Until then the server refuses "Update now" and scheduled auto-updates and shows the command.
(The old repo's `deploy/update.sh` is replaced by a stub that forwards here - `docs/legacy/kid-phone-server-update.sh`.)

Updates only ever go forward: the Updates page, the scheduler and `update.sh` refuse a release that isn't newer than the
installed one. To go back on purpose (see "Rolling back" below for the database), run `update.sh` with
`KPS_ALLOW_DOWNGRADE=1` - it then installs the newest release even if older.

### Release signing (server >= 0.24.0)

Root on the Pi installs and runs only release files signed with the project's [minisign](https://jedisct1.github.io/minisign/)
key (security review M5): `install.sh` and `update.sh` check the tarball, and the root-side updater checks `update.sh`
before it runs it. The public key is pinned in the scripts themselves (`SERVER_SIGNING_PUBKEY`), and each signature's
trusted comment names the file and the release tag, so a signed file can't be passed off under another name or as
another release. `server-release.yml` signs both with the `SERVER_MINISIGN_KEY` secret and refuses to publish without
it, or when it doesn't match the pinned key.

**One-time setup by the repo owner, before the first `server-v0.24.0` tag:**
1. On your own computer (not the Pi, not CI), with minisign installed (`apt install minisign`, `brew install minisign`):
   `minisign -G -W -p handy-server.pub -s handy-server.key`. `-W` leaves the secret key without a password, since CI
   signs unattended; the GitHub secret is what protects it. Keep `handy-server.key` somewhere safe offline (a password
   manager) and never in the repo.
2. GitHub > the repo > Settings > Secrets and variables > Actions > New repository secret: name
   `SERVER_MINISIGN_KEY`, value the whole content of `handy-server.key` (both lines).
3. Pin the public key, the second line of `handy-server.pub` (it starts with `RW`), in both scripts (six places,
   actions.sh's copies included):
   `KEY='RW...'; sed -i "s|^SERVER_SIGNING_PUBKEY=.*|SERVER_SIGNING_PUBKEY=\"$KEY\"|" server/deploy/install.sh server/deploy/update.sh`,
   then `cargo test deploy_scripts` (from `server/`) and commit.
4. Optional, as the review suggests: turn on immutable releases (Settings > General > Releases), and move the secret
   into an environment with required reviewers.

Until the key is pinned, the scripts refuse every release ("No release signing key is pinned").

- **The way in**: a Pi on 0.23 or older updates to 0.24.0 the old way (its `actions.sh` takes `update.sh` from the
  tag), but 0.24.0's `update.sh` already checks the tarball's signature, installs `minisign`, and rewrites
  `actions.sh` to check every later `update.sh`. A fresh install from `main` refuses releases older than 0.24.0 (they
  aren't signed).
- **A new key** (lost or leaked): make a new pair, replace the secret and the pinned key, release, and re-run
  `install.sh` on every Pi - an installed `actions.sh` refuses anything the old key didn't sign, by design.

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

Anything the devices reported after that backup (status, locations) is lost. Turn off scheduled automatic
updates on the Updates page first, or the next scheduled check installs the bad version again.

## Launcher provisioning settings

The server needs to know which launcher build to provision. These live in `/opt/kid-phone-server/.env` (restart the
service after editing: `sudo systemctl restart kid-phone-server`):

| Setting | Default |
|---|---|
| `LAUNCHER_ADMIN_COMPONENT` | `me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver` (the launcher's package is `me.vibb.launcher` since 2026-10-06; an `.env` that still has the old `com.kidslauncher.mdm/...` default is read as the new one) |
| `LAUNCHER_APK_URL` | `https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk` (only launcher releases are ever "latest") |
| `LAUNCHER_SIGNATURE_CHECKSUM` | none - required for the provisioning QR code |
| `SERVER_RELEASE_REPO` | `palchrb/vibb-launcher` (where the Updates page looks for new server versions - only `server-vX.Y.Z` releases count) |

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

### The launcher's own self-update

The launcher updates itself through the Apps catalog, like any other app. Add it once under **Apps > Add**:

- source: GitHub, repo `palchrb/vibb-launcher` (the catalog skips the repo's `server-v*` releases and takes the
  newest `launcher-v*` release that carries the APK)
- asset filename filter: `kids-launcher-mdm.apk`
- "include pre-releases": **off** (release candidates are published as pre-releases and must never reach the phones)
  - an existing row that watched `palchrb/kids-launcher-mdm` is moved to the monorepo by migration 0031, which also
    turns this off (with it on, every `launcher-vX.Y.Z-rc.N` would roll out to every phone)
- package name: `me.vibb.launcher` (a row that still says `com.kidslauncher.mdm` or `.debug` is renamed by migration
  0037)

#### Rollout of the package rename (`com.kidslauncher.mdm` -> `me.vibb.launcher`, 2026-10-06)

A renamed launcher is a different app to Android. Launcher builds from before the rename install whatever APK the
launcher row serves, without checking its package - so a renamed release reaching such a phone is installed **next to**
the old launcher as a second app. And once this server is updated, the provisioning QR names
`me.vibb.launcher/...` (an `.env` holding the old default is read as the new one). Do it in this order:

1. **Phones still on the old package**: provision them again with the renamed launcher (wipe, then the QR or
   `adb shell dpm set-device-owner me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver`) - or, until you
   can, untick "Enabled" on the launcher's page under Apps, so no phone is offered the renamed APK.
2. **Tag the renamed launcher** as a stable `launcher-vX.Y.Z` release, so it is GitHub's "latest" and
   `releases/latest/download/kids-launcher-mdm.apk` is the renamed build.
3. **Only then update this server.** Until step 2 is done, the provisioning QR doesn't work (it names the new package
   while "latest" still serves the old one) - provision with adb meanwhile.
- then, on the app's page, turn on "This is the launcher app"

A release launcher can't be downgraded: Android refuses an install with a lower versionCode. If a launcher release is
broken, fix it forward by releasing the old (or fixed) code under a higher `launcher-v*` tag.

## Removing FCM (server 0.20.0, launcher design 19)

FCM is gone: the phones' own connection to this server (the SSE stream) is the only way changes reach them at once, and
every phone also checks in by itself every 30 minutes (15 while its connection is down). One launcher APK now works the
same with anyone's server, and no nudge goes through Google. Other apps' notifications (Element X) still use Google's
push service: Play services stays installed and is never restricted. The device page's "Play and kiosk" card says
whether the phone is connected right now.

Order, if this server ever had FCM configured:

1. Release and install the server first (`server-v0.20.0` or later, `update.sh`). Migration 0048 drops the stored FCM
   installation IDs. A phone that was using FCM gets no nudges from this update until its next check-in (at most
   30 minutes), then switches to the stream by itself; "Sync now" in its Settings or a reboot shortens that.
2. Remove `FCM_SERVICE_ACCOUNT_FILE` from `/opt/kid-phone-server/.env` (while it is set, the server logs one warning at
   startup and ignores it), delete the key file (`sudo rm /etc/kid-phone-server/fcm-service-account.json`) and every
   other copy, and **revoke the key**: delete the service account (or its key) in Google Cloud IAM - that is what makes
   the stored IDs and any old backup useless. Then delete the Firebase project.
3. Then the launcher (a `launcher-v*` tag on design 19's launcher commits or later). Only after that tag's release
   build has run, delete the repository variables `HANDY_FCM_PROJECT_ID`, `HANDY_FCM_APPLICATION_ID`,
   `HANDY_FCM_API_KEY` and `HANDY_FCM_SENDER_ID` (Settings > Secrets and variables > Actions > Variables): a tag on an
   older commit still checks them.

Rolling back to 0.19 means restoring the `update.sh` backup ("Rolling back the server" above): 0.19 refuses to start
on a database with migration 0048.

`SSE_KEEPALIVE_SECS` (5-240, default 240 since 0.20.0, 120 before): each keepalive wakes the phone's radio, and the
launcher reconnects after 300 s of silence. `SSE_KEEPALIVE_SECS=120` in `.env` reverts to the old default.

## Vibb music library (server 0.21.0, design 21 step 1)

The Music page (Apps | Music) holds the library for the Vibb music app; each phone's page has a Music card.

- **Order**: install this server (`server-v0.21.0` or later) **before any stable `music-v*` release** is published in
  the repo. Older servers don't know the `music-v` tag prefix, and their launcher row (blank asset filter) could pick
  `vibb-music.apk` as the launcher update. Migration 0049 sets the launcher row to `launcher-v*` releases and
  `^kids-launcher-mdm\.apk$` (only where its cached release already is a `launcher-v*` one) and adds the music app's
  catalog row; anything else: the Music page's "Add the music app to the catalog" and the app's "Release tag prefix".
- **Storytel key**: the family's Storytel login is encrypted with `MUSIC_SECRET_KEY` (base64, 32 bytes) from `.env`,
  or else with the key file `/opt/kid-phone-server/data/keys/music-secret.key`, which the server writes (0600, in a
  0700 directory) at its first start - `data/` is the only place the service may write (`ProtectSystem=strict`).
  `MUSIC_SECRET_KEY_FILE` points elsewhere, but only somewhere the service can write. No backup holds the key: the
  backup zip has the database and the image stores, the live mirror and the external drive copy the database and
  `data/backups/`, `update.sh` the database files - don't copy `data/keys/` anywhere yourself. A database restored
  on another box, or a lost key file, means entering the login again on the Music page. Without a usable key the
  Storytel form is off and the phones get 503.
- **Smoke check after the update**: `journalctl -u kid-phone-server -b | grep -i storytel` shows "Storytel logins are
  sealed with ..." - never "Storytel logins are off: ..." (that line says which file and why).
- **Own files** are stored in `data/music_files/` and are **not** in the backups (too big). After a restore without
  them they are listed as missing (the phones keep their copies); upload them again to restore them in place. Their
  covers are in the backups. Files left by restoring an *older* backup show on the Music page ("Files no entry
  uses") with a delete button - nothing is deleted by itself.
- Rolling back to 0.20 means restoring the `update.sh` backup: 0.20 refuses to start on a database with migration 0049
  (and 0050, the library revision).

## Vibb music: the server sweeps the sources (server 0.22.0, design 21b)

The server now lists every NRK and RSS entry itself and sends the phones the lists; the phones only download the
audio from the source.

- **What it fetches**: psapi.nrk.no (one 5-episode page per NRK entry at each check, manifests only for new
  episodes), the RSS feeds (a conditional GET), gfx.nrk.no and feed images (covers, at most once a day). One request
  at a time, 200 ms apart, 4 s between entries. The Music page's "New episodes" card sets how often (every 1/3/6/12/24 h, default 6 =
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
  cards on a phone in light and dark, iOS Safari included.
- **Order**: this server before any stable `music-v*` release, as for 0.21.
- Rolling back to 0.21 means restoring the `update.sh` backup: 0.21 refuses to start on a database with migration
  0051.

## Server 0.23.0 (design 22): two listeners, the admin gate, Docker

- **What changes for an existing Pi**: nothing has to be done - the admin keeps answering on `127.0.0.1:3100` behind
  `tailscale serve`, and the phones keep their URLs. But the admin now refuses clients that aren't on the tailnet or
  the box itself (`ADMIN_PUBLIC`, section 2): a setup that reached the admin from the home LAN (`BIND_ADDR` on a LAN
  address) needs Tailscale, or `ADMIN_PUBLIC=on` knowingly.
- Everyone is signed out once (the cookie's new name), and open enrollment codes stop working (only their hashes are
  stored now; typed codes last 15 minutes, setup-QR codes 30). The provisioning page makes its QR code with a button
  now, and the Tailscale key is no longer shown on Settings > Provisioning.
- Limits on the phones' API: a phone's own token is never slowed by other clients; an address that keeps sending bad
  tokens or wrong typed codes gets 429 for 10 minutes. The device page's Access card shows when and how each phone last
  reached the server, and can revoke its access.
- Rolling back to 0.22 means restoring the `update.sh` backup: 0.22 refuses a database with migration 0052 (and 0053).

## Server 0.24.0 (security review, server part)

- **Everyone is signed out once**: sessions from before the update are ended, since older backups hold their ids (M2).
- `data/` and everything in it become readable by the service user only; the service runs with `UMask=0077` (the
  update adds it as a drop-in). A restore signs everyone out.
- The DNS blocklist fails closed: a feed that can't be downloaded keeps its previous domains, and until a feed has
  loaded once after a start (no internet at boot), phones that use it keep the list they have. The DNS/Filters page
  shows each feed's state.
- **Catalog apps are pinned** to their Android package and signing key, read from the APK of their first release
  (releases already cached are pinned at the first start): a later release of another package or signed with another
  key is refused, and the app's page says so. If the developer really changed keys, "Forget the package and key" on
  the app's page lets the next release set them again.
- **A new phone starts with only its clock, calculator, camera and Vibb Music allowed** (never Settings, a browser or a
  file manager); allow the rest on its page. Phones already set up keep their lists.
- **Signed releases**: from 0.24.0 on, root installs and runs only release files signed with the project's key
  ("Release signing" under "Updating"; the repo owner sets the key up once before tagging 0.24.0).
- Rolling back to 0.23 means restoring the `update.sh` backup: 0.23 refuses a database that 0.24 migrated (0061, 0062).

## Useful commands on the Pi

- Check it's running: `systemctl status kid-phone-server`
- View logs: `journalctl -u kid-phone-server -f`
- Restart it: `sudo systemctl restart kid-phone-server`

## Backups

Built in - see the **Backups** page under Settings in the admin UI. Create a backup on demand, set a schedule for automatic ones, and optionally mirror them live to an external drive plugged into the Pi. The database itself lives at `/opt/kid-phone-server/data/kidphone.db` if you ever need it directly.

- **Keep backups as safe as the admin password.** A backup (and a copy of the data volume or the live mirror) holds
  everything: every location, the PIN hashes, the password hash - and the 2FA secret, which is kept unsealed on
  purpose: sealing it would tie every sign-in to the key file in `data/keys/`, and losing that file would lock you out
  of the admin (design 22).
- Since 0.24.0 a backup (and the live mirror, and the pre-migration copy) holds **no admin sessions**, so a stolen copy
  signs no one in, and a restore signs everyone out. Everything in `data/` is readable by the service user only, and the
  copies on the external drive by root only. The drive itself isn't encrypted: keep it at home, or use an encrypted
  one.
- Restoring a backup on the Pi keeps the database it replaces in `/var/backups/kid-phone-server-prerestore/<time>/`
  (root-only, the newest 3).
- Since 0.23.0 the server also copies the database to `data/backups/pre-migrate-<from>-<to>.db` before an update
  changes it (the newest 3 are kept). Rolling back a Pi still uses the `update.sh` copy ("Rolling back the server");
  the pre-migration copy is a second one.

## Removed: MollySocket and the launcher's UnifiedPush distributor

The optional MollySocket installer (`deploy/install_mollysocket.sh`) and the launcher's built-in UnifiedPush distributor
(an ntfy.sh relay) were removed on 2026-10-06, together with the conversation journal and browser history (migration
0038 drops their tables, and the server deletes `data/journal_media` at startup; backups made before the update still
contain them until the backup schedule prunes them). An existing MollySocket install keeps running on its own until you
remove it (`sudo systemctl disable --now mollysocket`, then delete `/opt/mollysocket` and its `tailscale serve` on
port 8443). If a phone ever needs UnifiedPush again, install the ntfy app on it as the distributor.
