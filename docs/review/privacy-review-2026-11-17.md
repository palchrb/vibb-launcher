# Privacy and threat-model review, whole system, 2026-11-17

Scope: `main` at `98e3229cd`, read only. Three parts: Vibb One (`launcher/`), Vibb music (`music/`), and the server with
the parent PWA (`server/`). The angle is end to end: where data goes, who sees it, and what each attacker gets.
Code-first reviews of the server and the apps run beside this one.

Deployment assumed: Docker with Watchtower on a Proxmox VM behind Caddy. The PWA is on the tailnet only; the phone
listener (`sync.*`) is public. The phone runs Android's PIN lock, Android 14-17.

Paths: `server/...`, `launcher/...` (`L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`), `music/...`
(`M/` = `music/app/src/main/java/me/vibb/music/`).

Counts: **1 High, 2 Medium, 9 Low, 12 Info.** Fixes in priority order are at the end.

---

## 1. Data inventory

"Backups" means the zips in `data/backups/` (14 by default, `handlers/backups.rs:29`), made by `backups::snapshot`
(`handlers/backups.rs:217`). The snapshot strips admin sessions, the Tailscale key and the browsers' push rows
(`:248-283`). Nothing in `data/keys/` is ever in a backup, so anything sealed with those keys is unreadable there.
Every backup keeps every row as it was, up to 14 backups past each window below (DEPLOY.md:983-986).

| Data | Born | Travels | Stored | Kept | Who can read |
|---|---|---|---|---|---|
| **Location fixes** (full precision lat/lng, accuracy, provider) | Phone (`L/server/LocateCommands.kt`, `dto/LocationReport.kt:15-21`) | Status report, power report (shutdown fix), `extra_locations`; TLS to server | Phone: last fix and zone queue (≤20) in CE prefs. Server: `device_locations` | Server: 1/7/14/30 days (default 7), newest fix kept while not Off; Off erases all at once (`retention.rs:274-296`) | Every parent; anyone with the server or a backup |
| **Zones** (centre, radius) | Parent | Policy (coordinates only, no name, only while "Regularly") | `device_zones`; arrivals/departures in `parent_events` | Zones until deleted. Events with the location window (`retention.rs:390-405`) | Parents; the phone gets the circles |
| **Shutdown position** | Phone | `POST /power` | `device_locations.shutdown_at`, `phone_off` params | Location window; Off strips it (`retention.rs:411-426`) | Parents |
| **Weather cell** (0.02°×0.04°, ~2 km) | Server, from the newest fix or a zone (`handlers/weather.rs:54-79`) | Server to MET (`weather_cache.rs:32`) | `weather_cells` | 48 h unasked, or shorter than the longest location window; Off erases all | Server; MET sees the cell, the server IP, and `MET_CONTACT` if set |
| **Roaming country** | Phone (`data_roaming`) | Status report | `device_status`, `roaming_started` event | Event with the location window (`retention.rs:401`) | Parents |
| **Calls** | Phone | Only `call_state` (roles, dialer, last emergency call time) | Phone: Android's call log; blocked entries pruned after 30 days. Allowed numbers copied to device-protected storage (`L/calls/BootCallPolicy.kt:9-19`). Server: `device_status.call_state_json` | Server: 48 h (`retention.rs:24`) | Parents |
| **SMS/MMS bodies** | Phone | Never leave the phone. MMS HTTP is the platform's (`L/sms/mms/MmsRuntime.kt:312-328`) | Phone: the launcher's own SQLite in CE, `secure_delete` (`L/sms/store/SqliteMessageStore.kt`) | Until the kid deletes them | The kid; whoever unlocks the phone |
| **Held message** (stranger's text) | Phone | Sender and time only (`POST /sms-held`) | Phone: body ≤1600 chars for 24 h. Server: `device_sms_held` (sender number), `sms_held` event params (sender) | Server table 30 days (`sms.rs:352`). **Event params 90 days** (`retention.rs:428-436`; L2) | Parents; push service sees only ciphertext |
| **MMS pictures** | Phone | Never leave the phone (`docs/notes/launcher/calls.md:516`) | Phone: `files/sms/pictures/` by SHA-256 | With their messages | The kid |
| **Contacts** (names, numbers, photos) | Parent | Policy, photo route | Server: `contacts`, photos and **originals** (`data/contact_photo_originals/`). Phone: policy cache as plain JSON in CE prefs | Until removed; originals in backups (DEPLOY.md:996-1000) | Parents; the phone |
| **Contact requests** | Kid (phone book) | `POST /contact-request`: name, number, optional Matrix ID | `device_contact_requests`; `contact_request` event params | Table 30 days (`handlers/contact_requests.rs:41`). **Event params 90 days** (L2) | Parents |
| **Lock glance notification text** | Other apps on the phone | Nowhere (`L/badges/GlanceReader.kt:161-224`, `GlanceStore.kt:19-31`, `LockGlanceGuardTest`) | Memory only | Until the listener disconnects | Anyone who sees the locked screen (senders; text where the parent switched it on) |
| **Installed apps** (package, label, installer, version, logo hash) | Phone | Status report; logos as PNG | `device_status.installed_apps_json`; `device_app_logos`, `data/app_logos/` | 48 h (newest kept); logos while a phone names them, never backed up | Parents |
| **Screen time** (minutes, rule in force) | Phone | `time_state` | `device_status` | 48 h | Parents. No per-app usage is collected |
| **Calendar** (calendar names, account type) | Phone | Status report `calendar` (`L/server/dto/StatusReportRequest.kt:183-192`) | `devices.calendar_report` | Until the next report | Parents. A Google calendar's name is often an e-mail address (Info) |
| **Kid's own events** | Kid (Min kalender) | `POST /calendar/kid-events`, then server PUTs to the parent's CalDAV | Phone: Calendar provider (ended ones 60 days). Server: `device_kid_events.events_json`, `kid_caldav_events`. CalDAV host | While the feature is on; in backups | Parents; the CalDAV host (iCloud, Fastmail, Nextcloud) |
| **School/calendar links** | Parent | Server fetches; phones get the parsed set | `calendar_sources`, link sealed with the music key | While a phone names it | Parents; the link's host sees the server |
| **Blocked-domain log** | Phone | `POST /dns-events`, only while the log is on (default off) | Phone queue ≤200; `device_dns_events` | 7 days; off deletes at once (`retention.rs:248-261`) | Parents |
| **All DNS queries** | Phone (every app) | DoT/DoH from the phone to Cloudflare by default, or Quad9, or the parent's server (`L/server/PublicResolver.kt:162-192`) | Not on our side | — | The DNS provider (Info) |
| **Wi-Fi sign-in** (SSID, first host, ≤20 visited domains, blocked hosts) | Phone (`L/portal/PortalReport.kt:29-45`) | Status report `captive_portal` | `device_status.captive_portal_json` (with SSID); `wifi_signin_wide` event params (sites) | Status 48 h (newest kept). **Event sites 90 days** (L3) | Parents |
| **Music listening** | Music app | `music_state` has no titles or positions (`M/logic/Report.kt:11`); now-playing goes to the launcher only. Cover hashes of the playing neighbourhood are fetched from the server (Info) | Phone: `music.db` (positions, favourites, queue), no expiry. Server: `device_status.music_state_json` (incl. Sonos room names and MAC-based UIDs) | Phone: until the app's data goes. Server: 48 h | Parents (room names); podcast hosts see the phone (Info) |
| **Push to parents** (event title and summary: kid's name, kind, time, sender number for held SMS) | Server | Web Push, RFC 8291 encrypted (`webpush.rs:1-4`), to Apple, Google, Mozilla, Microsoft only (`webpush.rs:93-110`) | `push_deliveries` 30 days; subscriptions stripped from backups | 30 days | Parents; push services see size, TTL, urgency, timing (prior I1) |
| **App push (UnifiedPush, Matrix)** | Matrix homeserver, other app servers | Public `/up/`, `/_matrix/push/v1/notify`; content-free `up` nudge on the stream; phone pulls | `up_messages`. Matrix bodies cut to `event_id`, `room_id`, `counts`, `prio`, `type`, devices (`unified_push/matrix.rs:68-75`) | ≤24 h; registrations of a phone unseen 30 days (`retention.rs:595-604`). **In backups** (L5) | The server; phone apps |
| **Parent accounts** | Parent | — | `admin_users`: Argon2 hash, **TOTP secret unsealed** (prior I14), passkeys (public COSE keys) | Until removed | Server; every backup holder |
| **Child PIN** | Parent | Plaintext in the `set_kid_pin` command over TLS (`L/lock/AndroidLock.kt:220-245`) | Sealed with `data/keys/kid-pin.key` (`kid_pin.rs:1-10`) | Until changed | Server process; parents after re-auth |
| **Parent code** | Parent | Policy carries the PBKDF2 hash (prior I12, accepted) | Phone keeps only an HMAC of it (`L/server/PinVault.kt:58-62`) | Until changed | — |
| **Storytel, Spotify, CalDAV secrets** | Parent | Storytel JWT and Spotify token to the phone through the launcher bridge, memory only on the phone | Sealed with `data/keys/music-secret.key` (`music_secret.rs:1-16`, `caldav.rs:8`) | Until removed | Server process |
| **Security events** (sign-ins with IP, refusals, parents' changes, phone lines) | Server, phone | — | `security_events` | 180 days; phone lines 30, PIN lines 90; a wrong typed username 24 h (`retention.rs:323-379`) | Parents |
| **Phone IPs** | Phone | — | Last 5 public IPs, memory only (`limits.rs:42`) | Until restart | Parents (device page) |
| **Crash reports** | Phone | Class names and frames only (`L/crash/CrashSignature.kt`) | `device_crashes` | 30 days, ≤50 per phone | Parents |

The server logs no request lines. Its log lines name no coordinates, numbers, bodies or URLs (checked by grep; the weather
logs a cell's row id only, `weather_cache.rs:18`). The launcher's release build keeps `Log` calls, but a grep of the
SMS, calls, zones, DNS, portal, calendar and push code found counts, verdicts and class names only.

---

## 2. Third parties

| Who | From | Carries | Verdict |
|---|---|---|---|
| Apple, Google FCM, Mozilla, Microsoft push | Server | Encrypted parent pushes; `sub` is the project page unless `VAPID_SUBJECT` | Fine. Metadata as prior I1 (open) |
| MET Norway (`api.met.no`) | Server | A ~2 km cell, server IP, `vibb-server/<v>` and `MET_CONTACT` (`config.rs:95`, DEPLOY.md:890-895) | Fine, documented |
| CalDAV hosts | Server | The kid's own events (title, time, place) PUT with the app password; password only to a host that asked (`caldav.rs:14`) | Parent's choice. Fine |
| Calendar link hosts (ICS) | Server | A GET with the link's token | Fine |
| Storytel | Server (login, bookshelf), phone (`api.storytel.net` asset call with the server's JWT) | The family account; the phone's IP and `VibbMusic/<v>` | Fine. The signed CDN URL then goes to a Sonos speaker over plain LAN HTTP (Info) |
| Spotify | Server (accounts, Web API); phone (Web API, Web Playback SDK in a WebView) | The family's account; the WebView's default UA with the phone model (`M/spotify/SpotifyWebHost.kt:138`) | Fine. Prior P3 scope note stands |
| Sonos speakers (LAN) | Phone | SSDP, SOAP over HTTP, wss pinned to Sonos root; item title, album name, audio URL | Fine (LAN) |
| Podcast/NRK/RSS enclosure hosts and redirectors | Phone (audio), server (feeds, art) | The phone's IP, `VibbMusic/<v>`, listening timing (`M/media/MediaCache.kt:136-147`) | Info: the kid's IP to every podcast CDN and tracking redirector |
| GitHub (releases, API), F-Droid, IzzyOnDroid, blocklist hosts | Server | Release checks and downloads, `vibb/1.0` UA (`net.rs:23`) | Fine. The phone never talks to GitHub |
| GHCR | Watchtower on the host | Pulls `latest` | **See H1** |
| Cloudflare / Quad9 / custom DoH | Phone | Every DNS query of every app, with the phone's IP | Info: by design; the parent can pick |
| Tailscale control plane | Phone, server | Node key, hostname `kids-launcher-<model>` (`L/server/TsnetClient.kt:246`), endpoints | L6 |
| Captive-portal probes (`gstatic.com`, `cp.cloudflare.com`) | Phone | A `generate_204` GET (`L/portal/PortalCheck.kt:12-13`) | Fine |
| Captive portals, sites opened in the sign-in window | Phone (WebView, own process) | Whatever the portal asks; cookies kept across sessions | L4 |
| Google/FCM for other apps | Phone (Play, Element X's Google build) | Not ours; the filter never blocks FCM hosts (`L/server/push/FcmHosts.kt`) | Info |
| OpenStreetMap tiles | Parent's browser | Tile coordinates at street zoom (prior P8, open) | Low, cited |
| Mobile operator MMSC | Phone (platform) | MMS fetch and send | Fine |

---

## 3. Findings

### High

#### H1. The GitHub account is a key to the kid's phone, with no human step after it

- **What:** two automatic paths turn control of the GitHub account (or a token with `packages: write`) into control of
  the phone.
  1. **Server image.** Watchtower pulls whatever `ghcr.io/palchrb/handy-server:latest` is and restarts the server
     (`server/deploy/compose.yaml:10-14`, DEPLOY.md:390-394). Nothing on the host checks the build attestation
     (`server-release.yml:485-486`). The minisign key pinned in `server/deploy/install.sh:48` and `update.sh:47`
     protects only the systemd path, which this deployment doesn't use. A pushed image is a compromised server.
  2. **Launcher and music releases.** CI signs them with the keystore in the GitHub `release` environment
     (`.github/workflows/launcher.yml:239-290`). `RELEASE_CERT_SHA256` is a repo variable, so it only checks the
     key against a value the same account sets. Vibb One's and Vibb music's releases skip the hold and the wait
     (`server/src/catalog_holds.rs:360-373`), so a new stable `launcher-v*` reaches every phone at the next hourly
     sync. Android and `L/server/SelfUpdatePlan.kt:197-224` accept it: it is signed with the real key.
- **What a compromised server can do** (path 1): allow any stranger as a contact, set its own DoH server and see every
  query, set location to Regularly, install a new app (the signer pin is trust-on-first-use, server/CLAUDE.md "A
  catalog app is pinned", `L/server/CatalogApkCheck.kt:8-64`), read the Child PIN, wipe. It can't change the server URL
  (policy can't, `L/.../SettingsFragmentLauncher.kt:231-234`) or ship a launcher not signed by our key.
  Path 2 gives Device Owner code on the phone itself.
- **Prior reviews:** I8 (2026-10-21 server) flagged the missing verify step as Info. It is High in this deployment
  because Watchtower makes it automatic.
- **Fix:**
  - Server: drop Watchtower for this container, or pin `HANDY_VERSION` to a digest and update with a small host script
    that runs `gh attestation verify oci://ghcr.io/palchrb/handy-server@<digest> --owner palchrb` before
    `docker compose up -d`. Document it in DEPLOY.md "Docker" and fix `env.example:6`. Size M.
  - Launcher and music: give "ours" a wait too (24-48 h, `catalog_holds::wait_hours`) with a push "Vibb One X.Y is
    waiting" and an Update Now (visible). Size M. Put a required reviewer on the `release` environment that is a
    second factor the same session can't pass (hardware key), and turn on immutable releases. Owner action, size S.

### Medium

#### M1. A parent session can take the whole database without a trace, and replace the log

- **What:** `GET /backups/{file}/download` (`handlers/backups.rs:586-606`) needs no re-auth, writes no security event
  and pushes nothing. The zip holds every location, the inbox, contacts with photo originals, and every parent's
  Argon2 hash and **unsealed TOTP secret** (I14). `upload_backup` (`:872`) is also unlogged (prior I9, still open).
  Upload then Restore swaps in any database; the `backup_restore_triggered` line (`:840-846`) lands in the database
  that is replaced, so the restored one carries no record of it.
- **Why it matters:** a held session lasts up to 90 days (`net.rs:252`). Revoking it doesn't take back the download.
  The restore path lets an attacker plant an account and erase the log in one step.
- **Prior:** step-up was declined (U2). This asks again for these three routes only.
- **Fix:** put Download, Upload and Restore behind `admin_auth::reauth` (as Show Child PIN is,
  `handlers/device_pages.rs:1248`), log `backup_downloaded`/`backup_uploaded`, and push a "backup downloaded/restored"
  event to every parent like `PasskeyAdded`. After a restore, write the restore line into the restored database at
  startup (`startup::apply_restore_request`). Visible (a password prompt). Size S-M.

#### M2. The changes that matter most to a kid's safety are told to no one

- **What:** a parent session can, without re-auth and without a push to the other parents:
  - add a catalog app and tick it for the phone. The first release is never held, and the pin is taken from it
    (`catalog_holds.rs:331-355`). The phone installs it silently.
  - allow a new contact for calls and SMS in and out.
  - set a custom DNS server (`dns_filter::set_custom`), which then sees every query.
  - switch location to Regularly.
  These land only in the coalesced `settings_saved` log line. By contrast, a new passkey and a certificate check
  turned off push to every parent at once (`parent_events.rs:59-71`).
- **Why it matters:** with several parent accounts (design 62), a stolen or misused session can quietly expose the
  kid to a stranger or to tracking.
- **Fix:** raise a no-switch event pushed to every parent, as `PasskeyAdded` does, for: a new app on a phone, a new
  allowed contact, a custom DNS server set, and location turned to Regularly. `parent_events::Kind`, `handlers/devices.rs`,
  `handlers/calls.rs`, `handlers/dns_filter.rs`, `handlers/locate.rs`. Visible. Size M.

### Low

#### L1. The restore's safety copy is not stripped (prior I7, still open)

`startup.rs:188-200` copies the live database with `std::fs::copy` into `data/backups/prerestore-<time>/`. It carries
the push subscriptions and the Tailscale key. DEPLOY tells the owner to copy `data/backups/` off the host
(DEPLOY.md:410, :959), which takes this along. server/CLAUDE.md:196-205 says "Every copy goes through `snapshot`".
**Fix:** run `strip_secrets_in` on the prerestore copy, or keep it in `data/` outside `backups/`. Size S.

#### L2. Strangers' numbers stay 90 days in the inbox, while the page says 30

The held sender (`parent_events.rs:1989-2008`) and a contact request's name and number (`:2073-2101`) are event params.
Events are pruned at 90 days (`retention.rs:428-436`). The tables they come from go after 30 days (`sms.rs:352`,
`handlers/contact_requests.rs:41`), and the PWA says "The last 30 days" (`i18n/en.json:1959`).
**Fix:** prune `sms_held` and `contact_request` events with `HELD_KEEP_DAYS` / `KEEP_DAYS` in `retention::prune`, as
zone events follow the location window (`retention.rs:386-405`). Size S.

#### L3. Wi-Fi sign-in keeps browsing and place data longer than the rest

- A wide sign-in's visited domains are event params (`parent_events.rs:517-532`), kept 90 days. The DNS log keeps
  7 days and is off by default (`retention.rs:12`).
- Each session's SSID (`logins.rs:408`) is stored in `device_status`. An SSID often names a place (a hotel, a café).
  Location Off erases fixes, the shutdown position and the roaming country, but not this.
**Fix:** keep `sites` for `DNS_LOG_RETENTION_DAYS` (a `json_remove` in `prune`, like `phone_off`'s location). Drop
`network` at intake unless the PWA needs it, or blank it while Location is Off. Size S.

#### L4. The sign-in window's cookies and storage live on between sessions

`L/portal/PortalWindowActivity.kt:339` accepts cookies, and nothing in `portal/` clears cookies, storage or cache. A site
or portal seen in one sign-in recognises the phone in the next. A login done there stays logged in for the next session.
**Fix:** `CookieManager.removeAllCookies`, `WebStorage.deleteAllData` and `clearCache(true)` when the window closes.
Size S.

#### L5. App pushes outlive their 24 hours in backups

`up_messages` is not stripped by `strip_secrets_in` (`handlers/backups.rs:256-270`). A backup made during the queue's
day keeps those bodies for 14 backups. The PWA promises "app pushes are held at most 24 hours" (`i18n/en.json:4734`).
Matrix bodies are minimised; other apps' bodies are kept as sent.
**Fix:** add `("up_messages", "DELETE FROM up_messages")` to `strip_secrets_in`. Size S.

#### L6. The phone tells Tailscale it is a kid's phone, and which model

`L/server/TsnetClient.kt:246` names the node `kids-launcher-<Build.MODEL>`. Tailscale's control plane (no
`ControlURL`, `launcher/mobile/tsembed/tsembed.go:121-126`) and every tailnet admin view see it.
**Fix:** a neutral name such as `vibb-<4 hex>`. Visible in the Tailscale console only. Size S.

#### L7. Safe mode is not blocked by default (2026-10-10 H4, still open)

`L/server/Hardening.kt:25-27` keeps `SAFE_BOOT(false)`. Safe mode stops the VPN, so an allowed system browser goes
unfiltered. Unseen boots are reported. **Fix:** run device check 6 and turn it on. Size S after the check.

#### L8. The Locate map tells OpenStreetMap where the kid is (2026-10-21 P8, still open)

DEPLOY.md:844-848. **Fix:** proxy and cache tiles through the server, or accept and say so on Locate. Size M.

#### L9. Backups are plain zips

A backup holds everything in §1, the TOTP secrets included, and the owner now copies them off the host by hand
(DEPLOY.md:959). The prior external-drive risk was accepted with "keep the drive at home or encrypted"; that drive sync
is gone, so the advice no longer has a place to live in the product.
**Fix:** an optional backup passphrase (age or AES-GCM over the zip) on Settings › Backups. Visible. Size M.

### Info

- **I-1.** Every DNS query of every app goes to Cloudflare by default (`L/server/PublicResolver.kt:162-181`). Quad9 or the
  parent's own server are choices. Worth one line on the Web Filter page.
- **I-2.** The certificate pin is enforced only once the server switches a phone on: after 72 h of in-set sightings
  on 3 days (`server_tls.rs:8-14`, `L/server/ServerTransport.kt:339-359`). Before that a MITM with any public CA is
  reported, not refused. By design.
- **I-3.** The pin set is Let's Encrypt's and Sectigo's roots (`L/server/ServerPins.kt:20-29`). It stops a rogue CA,
  not anyone who can get a Let's Encrypt cert for `sync.*` (DNS or Caddy takeover).
- **I-4.** The Child PIN travels in plaintext inside `set_kid_pin` and is reversible on the server
  (`kid_pin.rs:1-10`). A compromised server plus the stolen phone unlocks it. Accepted in design 59.
- **I-5.** Allowed numbers sit in device-protected storage, readable before first unlock (`L/calls/BootCallPolicy.kt:9-19`).
  Needed for calls after a reboot.
- **I-6.** A catalog APK's file hash is checked only when the server sends one (`L/server/AppDownloads.kt:745`). The
  signer pin still holds.
- **I-7.** Music: podcast and NRK hosts see the phone's IP and `VibbMusic/<v>`. Spotify's WebView sends the default UA
  with the phone model. A Storytel signed CDN URL goes to a Sonos speaker over LAN HTTP
  (`M/sonos/PlayBody.kt:107-111`).
- **I-8.** The launcher fetches covers for the playing item's neighbourhood from the server
  (`M/bridge/Bridge.kt:165-171`). With no access log nothing records it; a Caddy access log on `sync.*` would.
- **I-9.** A calendar's display name is reported and may be an e-mail address.
- **I-10.** Sonos room names and MAC-based UIDs reach the server (prior I16).
- **I-11.** Twenty failed typed enrollment codes in an hour pause typed codes server-wide (`enrollment.rs:31-33`). An
  internet attacker can stall a typed setup; the QR path is unaffected.
- **I-12.** Lock glance before the PIN shows message senders (and text where the parent switched it on), missed calls,
  the next calendar item and the weather square. A finder sees them. By design, as Android's own lock screen.

---

## 4. Threat model

| Threat | Holds? | Why |
|---|---|---|
| **Stolen or lost phone** | Yes, with the glance's exposure | Android PIN set by the server; CE storage keyed to it. Device token, UP registrations and policy body sealed in the Keystore (`L/server/SealedPrefs.kt:179-245`), so prior P-L5 is closed. No backup (`allowBackup=false`). Remote lock and wipe wait for the phone however long it is gone (`retention.rs:455-472`). A recovery wipe meets FRP through the parent's Google account (`docs/setup/google-account.md` §6). Exposed: I-12, and a VoIP ring over the lock (2026-10-21 S-M4, a decision). |
| **The kid getting around rules** | Mostly | Factory reset, users, accounts, VPN, USB, adb blocked; auto time forced and date settings locked (`L/server/AppEnforcer.kt:1431-1447`); sideloading and Private DNS locked; always-on VPN with lockdown. Open: safe mode (L7), "Use secure DNS" in an allowed browser (S-M3, accepted). |
| **Another app on the phone** | Yes | The music bridge needs a signature permission and checks the caller (`L/music/MusicBridgeService.kt:37-50`). UP takes REGISTER only from `getSentFromPackage()` and only for allowed apps, and the receiver is disabled while UP is off. Providers are not exported. Debug receivers are absent from release (`DebugHookAbsentTest`). Exported activities take only intents a dialer or share sheet would send. Glance text is memory only. Music's playback session gives other apps transport controls only (`M/playback/PlaybackService.kt:751-765`). |
| **Internet attacker on `sync.*`** | Yes | Only the device API and the UP routes are served (`device_routes.rs:1-10`). Enrollment codes: QR 130 bits, typed 40 bits for 15 min with a server-wide pause (`enrollment.rs:1-16`). Tokens stored as SHA-256. Failed requests count per IP or /56, and a valid token never does (`limits.rs:1-17`). Gzip inflated only after auth. UP and Matrix routes make no outbound request and store minimised bodies (`unified_push/mod.rs:5-7`). The PWA is not on this listener. |
| **Stolen parent session** | Partly | The PWA is tailnet only; sessions end after 30 days idle, 90 max. Re-auth guards Show Child PIN, passwords, passkeys, parents. It doesn't guard backups (M1) or the safety-critical changes (M2). |
| **Compromised server** | The phone's code holds; its policy doesn't | The policy can't move the server URL; a launcher update must carry our signer (`L/server/SelfUpdatePlan.kt:197-224`); `recovery_url` is only displayed (`L/server/ServerPins.kt:173-189`). Everything the policy steers is the server's to set: contacts, DNS upstream, location, new apps (TOFU), wipe. That is the nature of an MDM; M2's pushes would at least tell the other parents if the server still works normally. |
| **Compromised GitHub account or release key** | No | H1: two automatic paths. The minisign pin is sound but sits on the path this deployment doesn't use. |
| **The backup drive** | Partly | Sessions, Tailscale key and push rows are stripped; Storytel, Spotify, CalDAV, calendar links and the Child PIN are sealed with keys never backed up. Still in clear: every location and event in the window, contacts and photo originals, TOTP secrets and password hashes, held senders, the UP queue (L5). The prerestore copy isn't stripped (L1). No encryption (L9). |

---

## 5. Docs against code

| Doc says | Code does | Where |
|---|---|---|
| "The last 30 days" (blocked senders); retention notes: held rows 30 days | The sender stays 90 days in the inbox | `i18n/en.json:1959`; L2 |
| "app pushes are held at most 24 hours" | Up to 14 backups past that | `i18n/en.json:4734`; L5 |
| "Every copy goes through `snapshot`" | The prerestore copy doesn't | server/CLAUDE.md:196-205; L1 |
| "The weather's caches (the grid cells asked for and their place names)" | Place names went on 2026-11-04 | DEPLOY.md:994 |
| `HANDY_VERSION=0.24.0` in the example | A skipped version; nothing verifies the image | `server/deploy/env.example:6`; H1 |
| The DNS log is off and kept 7 days | Wi-Fi sign-in sites are kept 90 days whatever the log's switch | `docs/notes/server/retention-and-crashes.md`; L3 |
| Location Off: "No position kept while location is off." | An SSID per sign-in, a place name, stays | `i18n/en.json:2064`; L3 |

Checked and true: "who and when, never what" for held SMS (`sms.rs:1-7`); the lock glance sends nothing back
(`lock_glance.rs:1-7`); "No Matrix message text is kept" (`unified_push/matrix.rs:68-75`); the weather is "never the
exact position" to MET (`weather.rs:12-13`); crash reports carry no messages; the music app sends no titles or
positions; the phone never contacts MET or GitHub.

---

## 6. Fixes in priority order

1. **H1, server image:** stop Watchtower auto-pulling; digest pin plus `gh attestation verify` before `up -d`; DEPLOY
   and `env.example`. M.
2. **H1, releases:** a wait and a push for Vibb One and Vibb music releases (`catalog_holds::wait_hours`) - **visible**.
   M. Owner: a hardware-key reviewer on the `release` environment, immutable releases. S.
3. **M1:** re-auth, log and push for backup Download, Upload and Restore; the restore line written into the restored
   database - **visible**. S-M.
4. **M2:** pushed no-switch events for a new app on a phone, a new allowed contact, a custom DNS server, location set to
   Regularly - **visible**. M.
5. **L5:** strip `up_messages` in `strip_secrets_in`. S.
6. **L1:** strip the prerestore copy. S.
7. **L2:** prune `sms_held` and `contact_request` events at 30 days. S.
8. **L3:** cut Wi-Fi sign-in sites at 7 days; drop or blank the SSID under Location Off. S.
9. **L4:** clear the sign-in window's cookies and storage at close. S.
10. **L6:** a neutral Tailscale hostname. S.
11. **L7:** device check 6, then block safe mode. S.
12. **L9:** an optional backup passphrase - **visible**. M.
13. **L8:** tile proxy, or a stated acceptance - **visible** if proxied. M.
14. **Docs:** DEPLOY.md:994 place names; I-1 one line on the Web Filter page - **visible**. S.
