# Deploying to a Raspberry Pi / DietPi

These steps get Kids Device MDM running on a Raspberry Pi Zero 2 W. Only 64-bit (aarch64) is supported.

## 1. Install

SSH into the Pi, then run:

```
curl -fsSL https://raw.githubusercontent.com/palchrb/vibb-launcher/master/server/deploy/install.sh | sudo bash
```

This downloads the newest server release (the newest `server-vX.Y.Z` release of the
[`palchrb/vibb-launcher`](https://github.com/palchrb/vibb-launcher) monorepo), sets it up as a background service that starts on boot, and prints an admin username/password at the end — **save that password**, you'll need it to log in the first time (and you'll be asked to change it immediately after).

The app only listens on the Pi itself (`127.0.0.1:3100`) by default — that's intentional for security.

## 2. Make it reachable

For plain tailnet access, visit `http://<pi-tailscale-ip>:3100` from any device on your tailnet - no further setup needed.

For an HTTPS URL (required if you want the admin site to be installable as a PWA - plain HTTP doesn't qualify), use `tailscale serve`, **not** `tailscale funnel` (funnel makes it public on the internet, which you don't want for a parental-control panel):

```
sudo tailscale serve --bg --https=443 http://127.0.0.1:3100
```

This gives you `https://<hostname>.<tailnet>.ts.net`, reachable only from your own tailnet. If this Pi already serves something else on port 443 (e.g. `board-game-tracker`), use a different port instead, e.g. `--https=8443`.

The phones hold a long-lived connection to `/api/devices/commands/stream` (server-sent events, the only way the server
nudges a phone) that is quiet except for a keepalive every `SSE_KEEPALIVE_SECS` (240 s by default). `tailscale serve`
passes it through unbuffered. **Any other proxy in front of the server needs a read timeout of at least 300 s** and no
response buffering on that path - nginx's default 60 s `proxy_read_timeout` cuts every stream, so changes would only
reach the phones at their 30-minute check-in.

## Updating

```
curl -fsSL https://raw.githubusercontent.com/palchrb/vibb-launcher/master/server/deploy/update.sh | sudo bash
```

Downloads the newest stable `server-vX.Y.Z` release (prereleases and drafts are skipped), checks it against the
release's published `.sha256`, and swaps the binary in place. The in-app "Update now" runs `update.sh` as it is in that
release (not `master`), from a file, never piped. Doesn't touch your `.env`. The download is unpacked and
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

- **What it fetches**: psapi.nrk.no (one page per NRK entry at each check, manifests only for new episodes), the RSS
  feeds (a conditional GET), gfx.nrk.no and feed images (covers, at most once a day). One request at a time, 200 ms
  apart, 4 s between entries. The Music page's "New episodes" card sets how often (every 1/3/6/12/24 h, default 6 =
  the Vibb Pi; NRK every 12 h at 6). Check now on an entry checks it at once (15 min per entry, 12 an hour, 48 a day).
- **Addresses**: a feed on the home LAN or the tailnet works (its first check fixes it as LAN). A public feed is never
  followed to a private address (redirects, DNS answers and media URLs are checked).
- **The first start** lists everything within about 20-30 minutes for a 40-entry library (a 100-episode NRK podcast
  is about 103 requests the first time). The log says what each check did:
  `journalctl -u kid-phone-server | grep "music sweep"` (`entry 12 "Abels tårn": 1 request, 0 new`).
- **Checks on the Pi after the update**: an NRK podcast, an NRK series of more than 100 episodes, a
  `serie/<slug>/<programId>` link, two RSS feeds (one oldest first, one with a rolling window) and a LAN feed: the
  first fill, the logged request counts and the 304s (`curl -H "If-None-Match: ..."` of
  `/api/devices/music/entries/{id}/items` with a phone's token); a restart and a `kill -9` mid-fill (neither loops nor
  starts over); a setting change and the Check now limits; a 21a import filled in the background; a 404 feed keeping
  its list; a public feed that redirects to a LAN address refused; yesterday's NRK series HLS URL still playing; the
  cards on a phone in light and dark, iOS Safari included.
- **Order**: this server before any stable `music-v*` release, as for 0.21.
- Rolling back to 0.21 means restoring the `update.sh` backup: 0.21 refuses to start on a database with migration
  0051.

## Useful commands on the Pi

- Check it's running: `systemctl status kid-phone-server`
- View logs: `journalctl -u kid-phone-server -f`
- Restart it: `sudo systemctl restart kid-phone-server`

## Backups

Built in - see the **Backups** page under Settings in the admin UI. Create a backup on demand, set a schedule for automatic ones, and optionally mirror them live to an external drive plugged into the Pi. The database itself lives at `/opt/kid-phone-server/data/kidphone.db` if you ever need it directly.

## Removed: MollySocket and the launcher's UnifiedPush distributor

The optional MollySocket installer (`deploy/install_mollysocket.sh`) and the launcher's built-in UnifiedPush distributor
(an ntfy.sh relay) were removed on 2026-10-06, together with the conversation journal and browser history (migration
0038 drops their tables, and the server deletes `data/journal_media` at startup; backups made before the update still
contain them until the backup schedule prunes them). An existing MollySocket install keeps running on its own until you
remove it (`sudo systemctl disable --now mollysocket`, then delete `/opt/mollysocket` and its `tailscale serve` on
port 8443). If a phone ever needs UnifiedPush again, install the ntfy app on it as the distributor.
