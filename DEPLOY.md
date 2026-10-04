# Deploying to a Raspberry Pi / DietPi

These steps get Kids Device MDM running on a Raspberry Pi Zero 2 W. Only 64-bit (aarch64) is supported.

## 1. Install

SSH into the Pi, then run:

```
curl -sSL https://raw.githubusercontent.com/palchrb/kid-phone-server/master/deploy/install.sh | sudo bash
```

This downloads the latest release binary, sets it up as a background service that starts on boot, and prints an admin username/password at the end — **save that password**, you'll need it to log in the first time (and you'll be asked to change it immediately after).

The app only listens on the Pi itself (`127.0.0.1:3100`) by default — that's intentional for security.

## 2. Make it reachable

For plain tailnet access, visit `http://<pi-tailscale-ip>:3100` from any device on your tailnet - no further setup needed.

For an HTTPS URL (required if you want the admin site to be installable as a PWA - plain HTTP doesn't qualify), use `tailscale serve`, **not** `tailscale funnel` (funnel makes it public on the internet, which you don't want for a parental-control panel):

```
sudo tailscale serve --bg --https=443 http://127.0.0.1:3100
```

This gives you `https://<hostname>.<tailnet>.ts.net`, reachable only from your own tailnet. If this Pi already serves something else on port 443 (e.g. `board-game-tracker`), use a different port instead, e.g. `--https=8443`.

## Updating

```
curl -sSL https://raw.githubusercontent.com/palchrb/kid-phone-server/master/deploy/update.sh | sudo bash
```

Downloads the latest release and swaps the binary in place. Doesn't touch your `.env`. Before the swap it copies the
database and the old binary to `/var/backups/kid-phone-server/<timestamp>/` (the newest 3 are kept), because the new
version may migrate the database on its first start.

To install from a different fork, prefix `bash` with `KPS_REPO=owner/repo` (for both `install.sh` and `update.sh`):
`... | sudo KPS_REPO=someone/kid-phone-server bash`. The root-side updater remembers the repo it was installed from.

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

Anything the devices reported after that backup (status, locations, journal) is lost. Turn off scheduled automatic
updates on the Updates page first, or the next scheduled check installs the bad version again.

## Launcher provisioning settings

The server needs to know which launcher build to provision. These live in `/opt/kid-phone-server/.env` (restart the
service after editing: `sudo systemctl restart kid-phone-server`):

| Setting | Default |
|---|---|
| `LAUNCHER_ADMIN_COMPONENT` | `com.kidslauncher.mdm/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver` |
| `LAUNCHER_APK_URL` | `https://github.com/palchrb/kids-launcher-mdm/releases/latest/download/kids-launcher-mdm.apk` |
| `LAUNCHER_SIGNATURE_CHECKSUM` | none - required for the provisioning QR code |
| `SERVER_RELEASE_REPO` | `palchrb/kid-phone-server` (where the Updates page looks for new server versions) |

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

- source: GitHub, repo `palchrb/kids-launcher-mdm`
- asset filename filter: `kids-launcher-mdm.apk`
- "include pre-releases": **off** (release candidates are published as pre-releases and must never reach the phones)
- package name: `com.kidslauncher.mdm`
- then, on the app's page, turn on "This is the launcher app"

A release launcher can't be downgraded: Android refuses an install with a lower versionCode. If a launcher release is
broken, fix it forward by releasing the old (or fixed) code under a higher version tag.

## Useful commands on the Pi

- Check it's running: `systemctl status kid-phone-server`
- View logs: `journalctl -u kid-phone-server -f`
- Restart it: `sudo systemctl restart kid-phone-server`

## Backups

Built in - see the **Backups** page under Settings in the admin UI. Create a backup on demand, set a schedule for automatic ones, and optionally mirror them live to an external drive plugged into the Pi. The database itself lives at `/opt/kid-phone-server/data/kidphone.db` if you ever need it directly.

## Optional: Molly (Signal) push notifications via MollySocket

If a kid's phone uses [Molly](https://molly.im/) (a de-Googled Signal fork) and you want it to receive push notifications without Google/FCM, Molly needs a [MollySocket](https://github.com/mollyim/mollysocket) server to relay them over [UnifiedPush](https://unifiedpush.org/). This is a separate, independently-maintained project (AGPLv3) - not something this repo forks or embeds, just an optional sibling service you can run on the same Pi. See the chat that led to this for the reasoning: it's not published as a library, and merging its Signal-protocol code into this server's own binary would mean permanently hand-maintaining someone else's security-sensitive networking code.

**Prerequisite**: enable "Push notifications for other apps" in the kid's launcher app itself (Settings, on the phone) first - MollySocket needs a UnifiedPush distributor already running on that phone to hand a push endpoint to, and the launcher can be that distributor without installing a second app.

1. Install it the same way as the main server:

    ```
    curl -sSL https://raw.githubusercontent.com/palchrb/kid-phone-server/master/deploy/install_mollysocket.sh | sudo bash
    ```

   This sets up its own systemd service (`mollysocket`), listening on `127.0.0.1:8020` only, same "local by default" posture as the main server.

2. Give it an HTTPS URL, same pattern as step 2 above but on a different port (kid-phone-server's admin site is already on 443):

    ```
    sudo tailscale serve --bg --https=8443 http://127.0.0.1:8020
    ```

3. On the kid's phone, open Molly → Settings → Notifications → change delivery method to **UnifiedPush** → "MollySocket server" → enter `https://<hostname>.<tailnet>.ts.net:8443` → scan the QR code it shows.

**Updating**: re-run the same install command - it re-downloads the latest binary and restarts the service without touching your config or the accounts already registered.

Its own database lives at `/opt/mollysocket/data/db.sqlite` if you ever need it directly - separate from kid-phone-server's own database, and not covered by this app's built-in Backups page.
