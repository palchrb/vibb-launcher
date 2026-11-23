# Quick start

This guide sets up the server with Docker on a Linux machine that already runs [Tailscale](https://tailscale.com). It
then sets up the first phone. Everything else is in [`server/DEPLOY.md`](../../server/DEPLOY.md):
- a Raspberry Pi without Docker;
- a public HTTPS name for phones without Tailscale;
- Caddy;
- signed, verified updates.

You need:
- a Linux machine with Docker and Compose v2, on amd64 or arm64;
- Tailscale on that machine, and a tailnet you control;
- an Android phone with Android 14 or newer that you can reset to factory settings.

Vibb is an early beta. Expect breaking changes between versions.

## 1. Get the files

```
mkdir vibb && cd vibb
curl -fsSLO https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/compose.yaml
curl -fsSL -o .env https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/env.example
mkdir -p backups && sudo chown 65532:65532 backups && sudo chmod 700 backups
```

`compose.yaml` runs two containers. The first is the server. The second is Tor, which is used only for the optional
Apple Wi-Fi lookup.

## 2. Settings

Open `.env` and set these three lines:

```
TZ=Europe/Oslo
PHONE_SERVER_URL=https://<machine>.<tailnet>.ts.net:8444
LAUNCHER_SIGNATURE_CHECKSUM=rEsEJkGAQs5_NTDeuXwlDOCucbvmGXo71jozCcl5C10
```

- `TZ`: your time zone. Backups and the update check run on local time.
- `PHONE_SERVER_URL`: the address the phones use. You make it in step 4.
- `LAUNCHER_SIGNATURE_CHECKSUM`: the signing certificate of the project's Vibb One releases. The setup QR uses it to
  check the app it installs. If you build Vibb One yourself, use your own certificate's checksum instead
  ([`server/DEPLOY.md`](../../server/DEPLOY.md), "Launcher provisioning settings").

While Vibb is in beta, also point new phones at the newest release candidate. Otherwise the setup QR installs the last
stable release, which is old. Copy the newest `launcher-v…-rc.N` tag from the
[releases page](https://github.com/palchrb/vibb-launcher/releases) into this line:

```
LAUNCHER_APK_URL=https://github.com/palchrb/vibb-launcher/releases/download/launcher-vX.Y.Z-rc.N/kids-launcher-mdm.apk
```

The other lines in `.env` can stay as they are.

## 3. Start

```
docker compose up -d
docker compose cp handy:/app/data/initial-admin-password . && cat initial-admin-password && rm initial-admin-password
```

The second line prints the first password for the user `admin`.

## 4. Reach it over Tailscale

```
sudo tailscale serve --bg --https=443 http://127.0.0.1:3100
sudo tailscale serve --bg --https=8444 http://127.0.0.1:3101
```

The first line serves the parents' web app, and the second the phones' API. Both are reachable only from your tailnet.

1. Open `https://<machine>.<tailnet>.ts.net` on your own phone.
2. Sign in as `admin` and change the password.
3. Add the page to your home screen. It works as an app there.

## 5. Make a key for the phone

In the Tailscale admin console, make an auth key for the phone. It should be:
- one-time (not reusable);
- pre-approved;
- not ephemeral;
- tagged `tag:kid`.

Paste it in the web app under *Settings › Phone Setup*. Each key sets up one phone. Your tailnet policy should let
`tag:kid` reach only port 8444 on this machine ([`server/DEPLOY.md`](../../server/DEPLOY.md), "Tailscale keys for the
phones").

## 6. Add the phone

1. Reset the phone to factory settings.
2. On the first welcome screen, tap six times on an empty spot. The phone opens a QR reader.
3. In the web app, go to *Devices › Add Phone* and scan the QR code shown there.

The phone installs Vibb One, which becomes the phone's device owner.

When the phone is set up, its page in the web app shows a setup list: the Android PIN lock, location, the optional
privacy steps and the rest. Follow it, then choose the phone's apps, contacts and rules.

## Updating

- **Automatic:** [Watchtower](https://containrrr.dev/watchtower/), with `HANDY_VERSION=latest`.
- **Verified:** `./update-image.sh`, which checks each release's build attestation before it runs it. Download it the
  same way as `compose.yaml`, from `server/deploy/update-image.sh`.

Update the server before the phones: a new Vibb One can need the newer server.
[`server/DEPLOY.md`](../../server/DEPLOY.md), "Updating", explains both.

## Help

Ask in the Matrix room [`#launcher:vibb.me`](https://matrix.to/#/#launcher:vibb.me).
