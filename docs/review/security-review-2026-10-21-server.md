# Security and privacy review 2026-10-21: server and parent PWA

Review of `server/` (Rust/Axum/SQLite, Askama templates, `static/` JS, `templates/sw.js`, `deploy/`, `Dockerfile`)
against `main` at `95afa2ea`. Read-only: no code was changed. The previous full review is
`security-review-2026-10-10.md` (with `qa-security-wave-server.md`); this one covers the ~340 server commits since
then and re-checks that review's open items. Line numbers are from `95afa2ea`; search for the function name if a line
has moved.

Counts: **High 0 · Medium 3 · Low 18 (10 security, 8 privacy) · Info 16**. Privacy findings are marked **(privacy)** and are listed in their
own sections.

## Summary

The core boundaries hold. The admin and phone listeners are cleanly split (`main.rs:712-717` serves only
`device_routes()` and `/healthz` on the phone listener; `device_routes.rs` holds only `/api/devices/*`). Every
state-changing admin route is a POST behind `require_full_auth` and `admin_auth::csrf_guard`. Sessions are
`__Host-`, HttpOnly, SameSite=Strict, cycled at both login steps and ended by an epoch. Nothing a phone sends reaches
a page as HTML. The music sweep's `Reach::Public` resolver closes SSRF for public sources. Push payloads are
RFC 8291-encrypted and go only to five named push hosts. Secrets are sealed or stripped from every copy of the database
that `backups::snapshot` makes. No High finding was found.

The three Medium findings are about data outliving the parent's intent:
- push subscriptions survive a password change or 2FA reset;
- the backup drive is never pruned, so the retention windows don't hold there;
- the family's Storytel password is handed to every phone.

## Medium

### M1. Push subscriptions survive a password change and a 2FA reset done in the PWA (privacy + security)

- **Evidence:**
  - `handlers/auth.rs:720` (`update_account_password`) and `:803` (`reset_totp`) bump `session_epoch` and nothing
    else.
  - Only `sign_out_everywhere` (`handlers/auth.rs:847`, `push::forget_subscriptions`) and the CLI
    `admin_auth::reset_2fa` (`admin_auth.rs:348`) delete the admin's `push_subscriptions`.
  - `push::send_due` never compares a subscription with the account's epoch.
  - `push.rs:313` and `docs/notes/server/notifications.md` say a 2FA reset removes them, but only the CLI does.
- **Attack:** someone with a stolen session turns on notifications in their own browser. The parent then follows the
  recovery path the PWA offers, a new password or "Reset two-factor login", both worded as "every other session
  signed out". The attacker's browser keeps receiving the kid's events with no session: emergency calls, phone off/on,
  battery, parent PIN used or locked, apps that can read the screen. The receipt route needs no cookie.
- **Fix:**
  - Call `push::forget_subscriptions(&state.db, admin.id)` in `update_account_password` and `reset_totp`, as
    `sign_out_everywhere` does. Better: store the `session_epoch` on each subscription at subscribe and treat a
    subscription with an older epoch as gone in `send_due`.
  - Add tests next to `tests::web_push`, and correct `push.rs:313` and the note.

### M2. The external backup drive is never pruned, so the retention windows don't hold there (privacy)

- **Evidence:** `backup_sync.sh` (heredoc in `deploy/install.sh:794-800`, the same text in `update.sh:618-624`) copies
  every top-level file in `data/backups/` to `$EXTERNAL_MOUNT/snapshots/` with `cp -au` and never deletes anything
  there. `prune_old_backups` (`handlers/backups.rs:172`, `RETENTION_COUNT` 14 at `:60`) prunes only the local copies.
- **Effect:** with "external copy" on, the drive becomes a permanent, unencrypted archive of every daily snapshot. It
  holds every location fix (even with 1-day retention chosen), DNS log, PIN events, parent events, contacts and 30-day
  status history, forever. The local 14 zips also keep each table about 14 days past its window. Neither the Backups
  page nor DEPLOY says so.
- **Fix:**
  - In `backup_sync.sh`, prune `snapshots/backup-*.zip` and `snapshots/pre-migrate-*.db` to the same count as the
    local copies (root side, names matched by pattern, `find -xdev -maxdepth 1 -type f`, no link following), or mirror
    with deletion limited to those names.
  - Say on the Backups page and in DEPLOY that a backup keeps data past the retention windows.
  - Consider encrypting the drive's copies to a key the parent holds (e.g. `age`).
  - `update.sh` already rewrites the root scripts, so existing Pis get the fix on update.

### M3. The family's Storytel e-mail and password go to every phone with Storytel on (privacy)

- **Evidence:**
  - `handlers/music_api.rs:231-291`: `GET /api/devices/music/storytel` (bearer) opens the sealed login and returns
    `{generation, email, password}` in plain JSON.
  - Storytel's `pwd=` is AES with a public key and IV (`storytel.rs:53-55`), so it is as good as the password.
  - The phone keeps the login in the Keystore and passes it over the launcher-music AIDL bridge (design 26b).
  - The PWA only warns "Use a password you use only for Storytel: each phone keeps it" (`templates/music_settings.html:97`).
- **Why it matters:** a reusable adult credential lives on a device the child holds, in two apps, and anyone with a
  device token (a rooted or lost phone) reads it. Spotify, by contrast, keeps the refresh token on the server and gives
  phones 1-hour access tokens.
- **Fix:** mint on the server, as `spotify_token` does. The server logs in and returns only the Storytel session
  (JWT, about 1 h) from a bearer endpoint; the phone never stores the password, and phones that hold one delete it at
  the next sync. This is an API change: launcher and music app in the same commit, with the contract files.
- **Decision:** this was design 26b's choice. If the owner keeps it, record it below as an accepted risk and keep the
  warning.

## Low: security

### L1. A device token can flood the security log, the inbox and the parent's urgent pushes

- **Evidence:**
  - `handlers/parent_pin.rs:288-299` inserts `parent_pin_used`/`parent_pin_lockout` straight into `security_events`,
    outside the `audit` row budget (`audit.rs:1-8`). The dedup key is the phone's own `event_id`, up to 100 events per
    POST (`parent_pin.rs:207`), with a token bucket of 600 and a refill of 2/s (`limits.rs:25-26`).
  - `PinLockout` is urgent (`parent_events.rs:79-84`) and urgent kinds are never folded (`push.rs:520-524`), so each
    event is its own push to every subscription.
  - The same pattern applies to `phone_on` (keyed by a phone-sent `boot_count`) and the emergency call
    (`parent_events.rs:729`, `new_emergency_call`: any time later than the last, with no upper bound at `now`).
- **Effect:** it needs a leaked token or a rooted phone. A burst hides real sign-in events behind tens of thousands of
  rows (kept 180 days), buries the inbox, and sends the parent a stream of "Emergency call" and "PIN locked" pushes.
- **Fix:**
  - Route phone-originated security lines through `state.audit` (or a per-device per-kind budget).
  - Cap new `parent_events` per device and kind per hour, and fold urgent kinds past about 3 an hour into one.
  - Ignore an emergency-call time later than `now + 5 min`.

### L2. A "LAN" feed is judged by one DNS lookup and kept for good; then its art is fetched with `Reach::Any`

- **Evidence:**
  - `music_sweep.rs:2407` (`feed_is_lan`) asks the unfiltered resolver and says LAN when every answer is private.
  - The answer is stored (`plan.lan` / `music_listings.lan`, `:2424-2432`) and never judged again; `music.rs:725-733`
    does the same at add.
  - For a LAN listing, the feed, cover (`:3188`) and episode art (`:3634`) use `Reach::Any`.
  - `feed_url` skips the private-host check (`:2494-2503`), so private URLs also reach the phones.
- **Effect:**
  - A podcast host that answers a private address once (DNS rebinding), or a Pi-hole that answers `0.0.0.0` for a
    blocked host (`is_private` counts it), turns a public feed into "LAN" for good.
  - The feed's `<image>` and item-art URLs then make the Pi send blind GETs to any LAN, tailnet or loopback host and
    port: the router, `100.100.100.100`, the admin listener on `127.0.0.1`. Only bytes that decode as an image come
    back, so this is a blind SSRF, not a read.
- **Fix:**
  - Pin the connection to the addresses that were checked (reqwest `resolve_to_addrs`).
  - Never count `0.0.0.0`/`::` as LAN.
  - For a LAN feed, fetch art only from the feed's own host and port, and everything else with `Reach::Public`.
  - Optionally have the parent confirm "this feed is on my home network" when `lan` first becomes true, and show it on
    the card.

### L3. `POST /api/devices/dns-events` stores its fields unchecked (M7 residual)

- **Evidence:** `handlers/device_api.rs:954-982` stores `domain`, `category` and `blocked_at` as sent
  (`models.rs:1331-1335`). Only the 200-item cap and the 512 KiB body limit apply.
- **Fix:** keep `domain` only as a lowercased hostname of at most 253 characters; take `category` only from the known
  list (else at most 64 characters); parse `blocked_at` as RFC 3339 and clamp it to `received_at` ± 1 day.

### L4. No per-device storage budget: one token can fill the SD card

- **Evidence:**
  - Every status POST appends a full `device_status` row, kept 30 days (`handlers/device_api.rs:1121-1203`,
    `retention.rs:100-112`). It carries up to 1500 apps of about 360 B each, plus about ten JSON blobs of up to
    4 KiB each.
  - Crash rows are keyed by a phone-chosen hash, 10 new rows per POST (`device_api.rs:1975-2017`).
  - At the per-device rate limit that is gigabytes a day.
- **Fix:**
  - Store a status row only when it differs from the last one or N minutes have passed; otherwise touch
    `last_seen_at`.
  - Cap distinct crash hashes per device (e.g. 50).
  - Set a daily row budget per device for the append-only tables.

### L5. The CSP still allows inline script, though one inline handler is all that is left (L4 phase 2)

- **Evidence:**
  - `admin_auth.rs:462`: `script-src 'self' 'unsafe-inline'`.
  - Every template now loads its scripts from `/static`. The only inline script left is
    `templates/schedule_rule.html:41` (`onchange=...`).
- **Effect:** an XSS anywhere (none was found) would run with full admin rights.
- **Fix:** move that handler into a static file and set `script-src 'self'` in `ADMIN_CSP` and in `page_csp`
  (`handlers/music_spotify.rs:223`). Add a test that no template has an `on*=` attribute or an inline `<script>`.

### L6. Admin sessions have no absolute lifetime

- **Evidence:** `security.rs:371` uses `Expiry::OnInactivity(30 days)` (`net.rs:206`), and every request renews it.
  `static/reconnect.js` pings `/ping` while the PWA is visible after being away. A session on a parent's phone can
  therefore live for good, and a stolen cookie stays valid as long as it is used once a month.
- **Context:** the 2026-10-10 review's M3 asked for an absolute lifetime. Step-up was declined (decision U2), but the
  absolute lifetime was not part of that decision.
- **Fix:** store `signed_in_at` in the session at the TOTP step, and refuse sessions older than e.g. 90 days in
  `load_active_admin` (configurable).

### L7. The current-password checks behind a session are not counted

- **Evidence:**
  - `update_account_password` (`handlers/auth.rs:~698-713`) answers "The current password is wrong" with no event and
    no count.
  - `reset_totp` (`:772-797`) logs `totp_reset_refused` but counts nothing.
- **Effect:** a stolen session can guess the parent's password at argon2 speed (one at a time, `PasswordGate`) with
  nothing in the security log for the password page, and with no ban.
- **Fix:** record `password_change_refused`, count both kinds toward a per-account limit (e.g. 5, then sign the
  session out), and show them in the login warning.

### L8. The server URL for the provisioning QR may be `http://` (M11, server part, still open)

- **Evidence:** `handlers/settings.rs:205` takes `server_url` trimmed, with no scheme check. `provisioning.rs` puts it
  into every QR as is.
- **Note:** the launcher still has `usesCleartextTraffic="true"` (`launcher/app/src/main/AndroidManifest.xml:112`),
  and design 22 §4.3's network security config is not done; that side is out of this review's scope.
- **Fix:** accept only `https://`, or `http://` to a 100.64.0.0/10 address (through the tailnet), and say why
  under the field.

### L9. One failing DELETE skips the whole hourly pruning pass

- **Evidence:** `retention.rs:66-182` uses `?` on every statement. A busy or failed `device_dns_events` delete (the
  first) skips locations, status, PIN, parent and push pruning for that hour, and a persistent failure (e.g. a bad
  row) skips them for good. It is logged once at `:195`.
- **Fix:** run each DELETE on its own, log per table, and return partial counts. A test can drop one table and check
  that the rest are still pruned.

### L10. `call_state` and `time_state` are stored as opaque JSON

- **Evidence:** `handlers/device_api.rs:1018-1029` keeps any JSON object up to 4 KiB for these two. Every other blob
  goes through a known-fields `sanitize_*`. `call_state.last_error` is free text.
- **Fix:** re-serialize through typed structs with only the known keys, and clip `last_error` to about 100
  characters.

## Low: privacy

### P1. Location is stored whatever the policy says, and the newest fix is kept forever

- **Evidence:**
  - `handlers/device_api.rs:1356-1377` stores any valid fix, also when the phone's `location_mode` is `off`. Only the
    launcher stops it.
  - `retention.rs:87-93` always keeps each phone's newest fix, also after location was turned off; only deleting the
    phone removes it.
  - DNS events, by contrast, are dropped at the door while the log is off (`retention.rs:66-70`).
- **Fix:**
  - Drop fixes at intake when `location_mode = 'off'`. For `on_request`, accept them only within a window after a
    `locate`.
  - When the parent turns location off, delete that phone's `device_locations`, the newest one too, or offer "Erase
    location history".

### P2. The 30-day status history is kept but nothing reads it

- **Evidence:** every reader of `device_status` takes the newest row. The exceptions need hours, not weeks: the power
  route's "newest before boot" (`device_api.rs:2052`), the calls capability check (`calls.rs:717`, which has its own
  kept row) and `devices.rs:1393`. Yet `retention.rs:100-112` keeps 30 days of every report: app lists, battery, call
  state, screen-time state, music state, roughly 50-100 rows a day per phone. The comment at `retention.rs:18-19`
  calls it a screen-time history, but nothing shows one.
- **Fix:** prune all but the newest row (and the newest `call_policy_v1` row) after about 48 hours, and correct the
  comment.

### P3. A phone's Spotify token carries the account's full scope

- **Evidence:** `spotify.rs:62-63` (`SCOPES`: `user-read-email user-read-private playlist-read-private
  playlist-read-collaborative user-modify-playback-state streaming`); `handlers/music_api.rs:355-375` gives the bound
  phone an access token from that refresh token.
- **Effect:** for an hour at a time, the kid's phone can read the bound account's e-mail, plan and private playlists,
  and control playback on all of that account's devices. If a parent binds their own account, the kid can control the
  parent's playback. A refresh can't narrow the scope.
- **Fix:** on the "Plays on" picker and in the Spotify settings, say to bind a child's own Family member account,
  never the parent's. Optionally use two consents: a server-only one with the playlist scopes, and a phone one with
  `streaming user-modify-playback-state user-read-private` only.

### P4. The VAPID `sub` tells Apple, Google and Mozilla the Pi's tailnet name

- **Evidence:** `push.rs:721-726`. Without `VAPID_SUBJECT`, the JWT `sub` is the subscription's origin, e.g.
  `https://pi.tail1234.ts.net`. The push service reads the JWT in clear and can tie the tailnet name to the parent's
  Apple ID or Google account. `deploy/env.example` doesn't mention `VAPID_SUBJECT` (DEPLOY.md:705 does).
- **Fix:** default `sub` to a neutral value such as `mailto:push@vibb.invalid` (check that Apple accepts it), or put
  `VAPID_SUBJECT` in `env.example` with that advice.

### P5. The User-Agent tells every third party that a kid phone server lives here

- **Evidence:** `music_net.rs:360` sends `"kid-phone-server (self-hosted; vibb music library)"` to every podcast host
  and CDN, NRK, image hosts, Storytel and Spotify, with the household's IP. `tracked_apps.rs:168` sends a similar UA,
  but only to GitHub.
- **Fix:** use a neutral UA such as `vibb-music/1.0`, with no "kid".

### P6. Three tables are never pruned

- **Evidence:**
  - `time_lifts`: every extra-time grant, with who gave it, is kept for good (`migrations/0025_time_rules.sql:40`;
    there is no DELETE).
  - `banned_ips`: expired bans are kept (only the manual unban deletes them, `handlers/admin.rs:282`).
  - `device_install_progress`: one small row per app, never pruned.
- **Fix:** prune ended lifts after about 90 days, expired bans after about 30, and progress rows for apps the phone no
  longer has selected.

### P7. The security log keeps the kid's activity 180 days, longer than the data it describes

- **Evidence:**
  - `app_installed` (label, package, installer), `play_install_mode`, `boot_unseen`, foreign access and
    `phone_app_blocked` (`handlers/device_api.rs:1416-1458`; `handlers/window_relock.rs:234,355,380,425`) are kept
    180 days (`retention.rs:124`). The status they come from goes after 30.
  - `music_entry_added` logs the pasted RSS URL (`handlers/music.rs:1000-1005`); a private or paid feed's URL often
    carries a token.
  - The typed login username is kept 180 days (clipped to 64 characters, `handlers/auth.rs:98`), so a password typed
    into the name field stays there.
- **Fix:**
  - Keep the kid-activity kinds 30 days, or move them to `parent_events` (90).
  - Log an RSS entry by host only.
  - Prune `login_failed` usernames after the ban window.

### P8. The Locate map tells OpenStreetMap where the kid is

- **Evidence:** `static/locate.js:93` loads tiles from `tile.openstreetmap.org` (allowed by `img-src` in
  `admin_auth.rs:462`). The tile coordinates at street zoom, together with the parent's IP and time, show the
  OpenStreetMap Foundation where the phone is, every time the parent looks.
- **Fix:** accept and document it, or proxy and cache tiles through the server so the tile host sees only the Pi.

## Info

| ID | Finding | Where | Suggestion |
|---|---|---|---|
| I1 | Push metadata gives the event away even though the payload is encrypted. `TTL 86400` is unique to `emergency_call`, urgent kinds send `Urgency: high`, and there is no padding, so the length shows. | `parent_events.rs:88-94`, `push.rs:734-735`, `webpush.rs:173-174` | Use one TTL for every urgent kind, and pad the plaintext to a fixed bucket (RFC 8188 padding). |
| I2 | A source cover URL is logged at warn level; a private feed's image URL may carry a token. | `music_sweep.rs:3199` | Log the host only. |
| I3 | `private_v4`/`private_v6` don't cover 6to4 `2002::/16`, Teredo `2001::/32`, IPv4-compatible `::a.b.c.d`, `64:ff9b:1::/48` or the documentation ranges. | `music_net.rs:195-224` | Judge the embedded IPv4 of 6to4 and compat addresses; refuse Teredo. |
| I4 | The music key (`data/keys/music-secret.key`) and the VAPID key sit on the same volume as the database. Backups leave `data/keys/` out, but a whole-volume or SD image holds both ciphertext and key. | `music_secret.rs:6-27` | Say so in DEPLOY; optionally allow `MUSIC_SECRET_KEY` from a root-owned env file. |
| I5 | Spotify "Remove" deletes the row and the token, but Spotify has no revoke API. | `handlers/music_spotify.rs` | Tell the parent to remove the app at spotify.com/account/apps. |
| I6 | An RSS entry's `target` (the feed URL, possibly with a token) goes to every ticked phone, which doesn't use it for RSS. | music library JSON | Withhold `target` for `rss`. |
| I7 | The container restore keeps an unstripped copy of the live database (sessions, push secrets, Tailscale key) in `data/backups/prerestore-*/`, against the "every copy goes through `snapshot`" rule. The comment at `startup.rs:279-281` says the pre-migration copy isn't in `backups/`, but `:278` writes it there (it is stripped). | `startup.rs:175-190`, `:278-281` | Strip the prerestore copy, or keep it outside `data/backups/`; fix the comment. |
| I8 | `deploy/env.example` pins `HANDY_VERSION=0.24.0`, a version that was skipped. The image is attested but DEPLOY shows no `gh attestation verify`, and compose pulls by tag. | `deploy/env.example`, `server-release.yml:327-333` | Pin the current version, offer a digest pin and a verify step. |
| I9 | `upload_backup` writes no security event (only the restore does); `unsubscribe` deletes any admin's subscription by endpoint. | `handlers/backups.rs:1097-1165`, `handlers/notifications.rs:881-893` | Log the upload; scope `unsubscribe` to the admin. |
| I10 | `install_progress` accepts any catalog id, not only this phone's, and doesn't clamp `percent`. | `handlers/device_api.rs:1669-1689` | Join `scoped_apps`; clamp to 0..=100. |
| I11 | `storytel_login_sent` writes one security event per phone fetch. | `handlers/music_api.rs:271-279` | Log once per device and generation per day. |
| I12 | Parent and kid PIN hashes (PBKDF2, salted) still travel in the policy; a 6-digit PIN falls quickly to a device token's holder. Accepted with design 24b's on-phone HMAC. | `handlers/device_api.rs:437-438` | None (accepted); a longer parent code helps. |
| I13 | `parent_events::local_target` accepts `"/\t/host"`; a browser strips the tab from `Location` and goes to `//host`. Every target is built by the server today, so this isn't reachable. | `parent_events.rs:663-665` | Also refuse ASCII control characters and whitespace. |
| I14 | The 2FA secret is stored unsealed (design 22 §3.2.8), and there are no recovery codes; the CLI `reset-2fa` is the recovery path. | `admin_users.totp_secret` | None (accepted); recovery codes would spare the SSH step. |
| I15 | The kid's phone is a tailnet node, so it reaches the admin `/login` unless the tailnet ACL (M12 step 1, owner) or `ADMIN_TAILSCALE_USERS` stops it. The per-IP ban and TOTP hold. | `gate.rs:67-80` | Owner: ACL `tag:kid` → device port only (DEPLOY.md). |
| I16 | Sonos room UIDs (`RINCON_<MAC>…`) and room names are kept in `device_status` (30 days) and `device_policy.music_sonos_rooms_json`. They are the speakers' MAC addresses and the home's room names. | music state | None: needed for the room picker. |

## Re-check of the 2026-10-10 review's open items

| Item | State on `95afa2ea` |
|---|---|
| H1, H2, M2, M4, M5, M6, M10 (server), L5, L9, L10, L12 | Fixed as the wave's table says. Re-checked in passing: no root script sources `data/`; locations go through `checked_location` and `textContent`; `strip_secrets_in` (`handlers/backups.rs:246-279`) now also removes push deliveries and subscriptions; minisign is pinned (`6ae54e84`). No regression found. |
| M1, M3 (part), L1-L4 (phase 1), L11 | Fixed (design 22). M3's absolute session lifetime is still open (L6 above); L4 phase 2 (no `'unsafe-inline'`) is still open (L5 above). |
| M7 | Status reports are capped. `dns-events` fields remain open (L3), and the per-device storage budget is new (L4). |
| M11 (server) | Open: the server accepts an `http://` server URL (L8). |
| M12 | Server side done (separate listener, hashed atomic enrollment). The Tailscale key stays write-only in the PWA and blanked in backups. The ACL is the owner's (I15). |
| M2 note | The external copy is still unencrypted, and now also unpruned (M2 above). |

## Areas checked and found sound

- **Listeners:** `build_device_router` (`main.rs:712`) has only `device_routes()`, `/healthz` and an empty 404, with no
  session layer and no `/static`. The admin router adds the device routes only with `ADMIN_DEVICE_API`, behind
  `gate::admin_gate`. Host-only routes exist only for `DeployKind::Systemd`. `/ping` sits behind
  `require_full_auth`, answers 204 `no-store`, and a signed-out request gets the login redirect.
- **CSRF:** `csrf_guard` runs before the session is read and refuses any `Sec-Fetch-Site` other than
  `same-origin`/`none`. Without that header an `Origin` must match `Host` (or `X-Forwarded-Host` from a trusted
  peer). It exempts only `/api/devices/*` (bearer), `/healthz` and `/notifications/receipt`; the receipt needs a
  per-delivery random token, stored hashed and compared in constant time. Every music, DNS, lock-screen and Install Now
  change is a POST. The new lock-screen select (`handlers/lock_glance.rs`, `c779739d`) validates the package name and
  `show`, writes in one transaction, logs, and redirects to a fragment built from the checked package.
- **Headers:** HSTS, `nosniff`, `Referrer-Policy: same-origin`, `X-Frame-Options: DENY` and `frame-ancestors 'none'`,
  COOP, and a Permissions-Policy. `no-store` on locations, backups, provisioning, account, auth and notifications;
  other HTML is `private, no-cache`. The phone API sets `default-src 'none'; sandbox` (`limits.rs:425-483`).
- **Redirects:** every dynamic `Redirect::to` is built from ids or fixed words. `push_marker` redirects to the same
  path. `notifications::open` goes only to a `local_target`. `sw.js`'s `localPath` keeps a same-origin path and
  refuses `//`. `reconnect.js` restarts only same-origin http(s) navigations, drops `notice=` on reload, and never
  reloads a POST's answer (`data-post-answer`).
- **Service worker:** no fetch handler, so nothing is cached. The receipt is posted with `credentials: "omit"`.
- **Phone intake:** per-device scoping on every route (receipts, results, downloads, music files, covers, contact
  photos, wallpapers). Token lookup is by SHA-256 in memory. Enrollment is one conditional `UPDATE … RETURNING`.
  Crash traces keep stack frames only. Notification cancels keep package, channel and counts, never text. The Spotify
  token route hands out only the phone's bound account's 1-hour token, at most 20 an hour, and never logs it.
- **SSRF:** `Reach::Public` resolves at connect time through `PublicResolver`. Redirects are re-checked per hop
  (at most 5, http(s) only), `no_proxy`, no referer, URLs dropped from errors, size and time caps per kind. The
  100.64/10 range covers `100.100.100.100`. Spotify and Storytel hosts are constants (loopback overrides only for
  tests). Push endpoints are allowed only to five named hosts, https on port 443, no IP literals, no redirects. The
  custom DNS server is parsed only, never contacted. Catalog sync talks to GitHub only.
- **Images:** decoded with dimension, pixel and allocation limits, one at a time, re-encoded as JPEG without
  metadata, served by validated hash as `image/jpeg`. SVG is never decoded. APK parsing is bounded (`apk.rs:17-19`).
- **Secrets:** the Spotify refresh token and the Storytel login are sealed with AES-GCM (`music_secret.rs`); Spotify
  uses PKCE with no client secret. The VAPID and music keys are 0600 in `data/keys/` and never in a backup. Tokens,
  PINs, passwords, TOTP codes, push endpoints, locations and credentials appear in neither `tracing` nor the security
  log.
- **Deploy:** no new root action since the wave. The unit keeps `NoNewPrivileges`, `ProtectSystem=strict`,
  `UMask=0077` and `ReadWritePaths` limited to `data/`. The image is distroless, uid 65532; compose sets read-only,
  `cap_drop: ALL` and `no-new-privileges`. The release-notes step passes the tag through `env`.

## Privacy inventory

"Off server" means the data leaves the Pi. Everything per phone is deleted with the phone (cascade), except
`security_events` lines, which name "device N" as text and stay their 180 days.

### From the phones

| Data | Stored in | Kept | Seen by | Needed? / flag |
|---|---|---|---|---|
| Installed apps (package, label ≤100, installer) | `device_status.installed_apps_json` | 30 days, newest kept | Admin | Newest row only (P2) |
| New installs (label, package, source) | `security_events` `app_installed` | 180 days | Admin | Yes, but kept longer than the source (P7) |
| Lock state, versions, policy state, capabilities, screen timeout, ringer, battery-optimization flag, etc. | `device_status` columns and sanitized JSON | 30 days, newest kept | Admin | Newest only (P2) |
| Call state (roles, dialer/SMS packages, last emergency call, callback window, `last_error`) | `device_status.call_state_json` (unsanitized, L10) | 30 days; newest `call_policy_v1` row kept for good | Admin; emergency call → event + push | Yes (newest) |
| Screen time (minutes used, budget, rule, lifts, zone) | `device_status.time_state_json` (unsanitized, L10) | 30 days | Admin | Newest only |
| Notification cancels (package, channel, counts; **no text**) | `device_status.notification_cancels_json` | 30 days | Admin | Yes |
| Music state (versions, cache bytes, error codes, Sonos rooms with RINCON MAC and room names; no positions, no now-playing) | `device_status.music_state_json` | 30 days | Admin | Yes (I16) |
| Battery level, charging, low | `device_status`, `devices.battery_low_since`, `parent_events` | 30 / 90 days | Admin; push | Yes |
| Location (lat, lon, accuracy, time) | `device_locations` | 1/7/14/30 days (default 7); **newest kept forever**; accepted even when off (P1) | Admin (Locate); tile coordinates to OSM (P8) | Yes, within the policy |
| Blocked domains | `device_dns_events` | Only while the phone's log is on (default off); 7 days | Admin | Yes (opt-in); fields unchecked (L3) |
| Crash reports (hash, stack frames ≤4000 characters, counts, times) | `device_crashes` | 30 days after the last report | Admin | Yes |
| Parent-PIN events (kind, entry, times, failures, level) | `device_pin_events`; `security_events`; `parent_events` | 90 / 180 / 90 days | Admin; push | Yes (L1) |
| Power off/on (times, boot count, battery, reason) | `parent_events`; `push_deliveries` | 90 / 30 days | Admin; push | Yes |
| Command receipts and results (message ≤500 characters) | `device_commands` | 30 days, except undelivered wipe/lock | Admin | Yes |
| Install progress | `device_install_progress` | Never pruned (P6) | Admin | Yes, small |
| Client IP (authenticated) | Memory only | Until restart | Admin (About › Network) | Yes |
| Client IP (failed token or enrollment) | `security_events.ip_address` | 180 days | Admin | Yes |

### Sent to the phones

| Data | Notes |
|---|---|
| The phone's contacts (names, numbers, photos) | Third parties' data. A contact no phone uses is deleted (`devices.rs:2479`, `calls.rs:1198`). |
| Parent and kid PIN hashes (PBKDF2, salted) | Accepted (I12). |
| **Storytel e-mail and password, in plaintext** | M3. |
| Spotify access token for the bound account (1 h, full scope) | P3. |
| Music library and listings, including an RSS `target` with any token in it | I6. |

### From the parent

| Data | Stored in | Kept | Seen by | Needed? / flag |
|---|---|---|---|---|
| Admin username, argon2 hash, 2FA secret (unsealed), epoch | `admin_users`; every backup | Until changed | Admin; any backup holder | Yes (I14) |
| Sessions | `tower_sessions` | 30 days idle, no absolute limit (L6) | Server | Yes; stripped from every copy |
| Push subscriptions (endpoint, p256dh, auth, origin, UA label, last opened, last receipt) | `push_subscriptions` | While active; 30 days after gone; **survive a password change or 2FA reset** (M1) | Admin (label and state) | Yes |
| Push deliveries (status, reason, token hash, receipt and click times) | `push_deliveries` | 30 days | Admin | Yes |
| Parent events (phone name, kind, times, battery, foreign-app names) | `parent_events` | 90 days | Admin | Yes |
| Contacts (names, numbers, photos) | `contacts`, `data/contact_photos` | While a phone uses them | Admin; the phone | Yes |
| Time lifts (extra time, who gave it) | `time_lifts` | Never pruned (P6) | Admin | Short-term only |
| Security log (IPs, typed usernames, Spotify display names, RSS URLs) | `security_events` | 180 days | Admin | Partly (P7) |
| Tailscale key | `provisioning_settings` | Until cleared; blanked in copies; write-only in the PWA | Server; each QR | Yes (M12) |
| Custom DNS server (may hold a client id) | `dns_filter_settings` | Until changed; kept in backups by design | Admin; phones | Yes |
| Backups (the whole database, stripped) | `data/backups` (14), drive `snapshots/` (**never pruned**, M2), live mirror | See M2 | Root; drive holder | Retention not honoured |

### From Spotify, NRK and Storytel

| Data | Stored in | Kept | Seen by | Leaves the server to |
|---|---|---|---|---|
| Storytel e-mail and password | `music_storytel.ciphertext` (sealed) | Until cleared | Not shown again in the PWA | Storytel (login query); **every Storytel-on phone in plaintext** (M3) |
| Storytel JWT, shelf | Memory (1 h; shelf 5 min) | Process lifetime | Nobody | Storytel |
| Spotify user id, display name, scopes, times | `music_spotify_accounts` | Until removed | Admin | None |
| Spotify refresh token | sealed in `music_spotify_accounts` | Until removed (I5) | Nobody | Spotify accounts service |
| Spotify access token | Memory | 1 h | Bound phones | Phones (full scope, P3) |
| Playlists, albums, series, episodes and art the parent added | `music_entries`, `music_listings`, `music_items`, art stores | While the entry exists | Admin; ticked phones | Phones; sources read with an identifying UA (P5) |
| Parent's own audio files and covers | `data/music_files`, `music_covers` | Until deleted | Admin; ticked phones | Ticked phones |

### Off the server, to third parties

- **Web Push:** the payload carries a title (≤60 characters) and summary (≤100): the phone's name (often the kid's
  first name), the event kind, local time, battery level, the PIN entry name or the number of foreign apps, plus the
  receipt token. It is end-to-end encrypted (RFC 8291 `aes128gcm`), so the push service can't read it. The push
  service does see the endpoint, the VAPID public key, the JWT's `aud` and `sub` (the tailnet name, P4), and the TTL,
  urgency, size and timing (I1).
- **Map tiles** to OpenStreetMap (P8).
- **Feed, image and audio hosts** see the Pi's IP and an identifying UA (P5). The phones fetch audio directly.
- **Storytel and Spotify** see their own logins and API calls.
- **GitHub** sees release checks.

### Never stored, as checked

Notification or message text, call logs, SMS content, browser history, the journal, listening positions or history,
and raw User-Agent strings.

## Accepted risks and decisions (unchanged unless the owner says so)

- Step-up authentication was declined (U2); the 2FA secret stays unsealed (design 22 §3.2.8).
- The PIN hashes are in the policy (L6 of 2026-10-10, mitigated on the phone).
- The external backup is unencrypted (DEPLOY advises keeping the drive at home or encrypted), but M2 above is new.
- The Storytel password on the phones (design 26b) stands until the owner decides on M3.

## Suggested order

1. **M1:** forget the push subscriptions on a password change or 2FA reset. Small, with tests.
2. **M2:** prune the drive's snapshots in `backup_sync.sh`. A root script, so the deploy-script tests apply.
3. **P1** and **P2:** location intake and status retention, both in `retention.rs` and `device_api.rs`.
4. **L1, L3, L4:** intake budgets.
5. **L5, L6, L7:** session and CSP hardening.
6. **M3:** an owner decision first, then an API change across the server, launcher and music app.
