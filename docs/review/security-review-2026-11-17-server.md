# Security and privacy review 2026-11-17: server and parent PWA

Review of `server/` (Rust/Axum/SQLite, Askama templates, `static/` JS, `templates/sw.js`, `deploy/`, `Dockerfile`)
against `main`. It started at `cb71e028`; the only commit since, `98e3229c`, changes PLAN.md alone, so the line
numbers hold for both. Read-only: no code was changed, nothing was built. The previous full review is
`security-review-2026-10-21-server.md` (against `95afa2ea`). This one covers the 640 server commits since then and
re-checks that review's items. The format, severity scale and evidence/attack/fix shape are the same.

Counts: **High 0 · Medium 2 · Low 10 (7 security, 3 privacy) · Info 8**. Privacy findings are marked **(privacy)**.
Every finding has a fix, and each fix names the file it changes. "Visible" means the parent or the kid would notice
the fix.

## Summary

The core boundaries still hold.
- The phone listener serves only `device_routes()`, the two app-push routes and `/healthz` (`main.rs:847-853`).
- Every admin POST sits behind `require_full_auth` and the global `csrf_guard` (`main.rs:1896-1942`).
- `script-src` is `'self'` now (`admin_auth.rs:866`).
- Sessions have an absolute lifetime. The current-password checks are counted.
- Push subscriptions are tied to the session epoch.
- The app-push gateway makes no outbound request. It stores only secret hashes, and every limit is taken before any
  database work.
- Every new outbound fetch (calendar links, CalDAV, MET) goes through `music_net`'s pinned resolver.
- Every new phone-intake blob is rebuilt from an allow-list.

Most of the 10-21 items are fixed (table below). No High finding was found.

The two Medium findings are about what one phone token can do.
- **M1:** the family's Storytel password is still handed to any phone that asks. Design 58's retirement step never
  landed.
- **M2:** a phone token alone can pause the time rules over and over, with no cap and no word to the parent.

## The public phone listener, route by route

The user's setup is option (c): Caddy on another box sends the phone name to the phone listener, and
`TRUSTED_PROXIES` names Caddy. The whole device API is therefore on the internet, not only the two push routes.

| Route | Auth | Body cap | Rate / other caps |
|---|---|---|---|
| `POST /api/devices/enroll` | the one-shot code | 4 KiB, read within 5 s | 8 slots; typed codes paused globally; per-IP ban (`handlers/device_api.rs:34-94`) |
| every other `/api/devices/*` (34 routes) | bearer token, looked up by SHA-256 | 512 KiB by default; smaller per route (power, time request, rule pause, contact request, SMS held, Storytel token, UP routes); logos 80 KiB; kid events 1 MB | 600-token bucket refilled at 2/s per device (`limits.rs:26-27`); per-IP ban on a bad token; daily row budgets (`retention.rs:61-127`) |
| `POST /api/devices/status` (gzip) | token checked **before** the inflate | 512 KiB on the inflated bytes | as above |
| `POST /up/{secret}` | the 160-bit secret; VAPID when keyed | 4 KiB | 4 in flight per client, 40 burst then 20/s per client, 256 body reads, 32 handlers, 3 s body, 10 s answer; ban after 30 unknown secrets (`unified_push/edge.rs:29-38`, `rate.rs:29-35`) |
| `GET`/`POST /_matrix/push/v1/notify` | the pushkey's secret | 4 KiB | the same edge |
| `/healthz` | none | none | `SELECT 1`, answers only "ok" |

## Medium

### M1. The family's Storytel password still goes to any phone token that asks (privacy + security; was 10-21 M3)

- **Evidence:**
  - `handlers/music_api.rs:233-293`: `GET /api/devices/music/storytel` (`device_routes.rs:139`) still opens the
    sealed login and answers `{generation, email, password}`.
  - Design 58 §2.4 ("the route goes in step 3") planned to retire it. Steps 1 and 4 shipped (`plan-archive.md:640-656`),
    but step 3 never did. It is not on PLAN.md's queue.
  - Phone half: the launcher still calls the route (`launcher/.../music/MusicBridgeService.kt:274`) and hands the
    password over the bridge. The music app no longer asks for it.
- **Attack:** a stolen or extracted token (a rooted phone, a phone that was lost before it was removed) reads the
  parent's Storytel e-mail and password in clear. Every phone with Storytel switched on can.
- **Fix:** do design 58 step 3 in one commit: remove the route, `StorytelLogin` and the `storytel_login_sent` line
  (`handlers/music_api.rs`, `device_routes.rs`); in the launcher, remove `MusicServer.storytel()` and answer
  "delete yours". Then the empty 404 is the answer for good. **Size S. Not visible.**

### M2. A phone token alone lifts the time rules, with no cap and no word to the parent

**Fixed 2026-11-18** (branch `sec-round-2`): bounded by telling the parent, by the user's choice - no cap. Each pause
the phone posts is the inbox's parent-PIN line, pushed under "Parent PIN used".

- **Evidence:**
  - `handlers/rule_pause.rs:63-79`: `check` validates only the shape: a 32-hex key, 15/30/60/120 minutes, times
    near now, 1-8 rules.
  - `:136-180`: every new `key` inserts one `time_lifts` row per rule. It doesn't check for a live phone pause, count
    per day, or require the phone to claim `rule_pause_v1`.
  - `:208-232`: the only trace is a security-log phone line under its hourly budget. No `parent_events` row is
    raised, so there is no inbox entry and no push.
  - `time_rules.rs:1051` (`deliverable_lifts`) sends every live row back in each policy, with no limit.
- **Attack:** the parent code is checked only on the phone. Whoever holds the token (a rooted phone, a copied
  token) posts a new key every 2 hours and keeps Leggetid and Skole lifted for good. The parent's PWA shows "paused
  with the parent code", which the parent may not question.
  - The same loop at the token rate (2/s) adds about 170 000 rows a day for one rule, kept 90 days after they end
    (`retention.rs:476-480`).
  - With 2-hour lifts, about 14 000 rows are live at once, and each policy fetch carries them all.
- **Note:** the PIN hash is in the policy (10-21 I12), so a token holder can already try PINs offline. The server is
  therefore the only place that can bound this.
- **Fix:** in `handlers/rule_pause.rs`, refuse a new key while a phone pause is live, and allow at most a few a day
  (`over`). Raise a `parent_events` entry for each phone-set pause, so the inbox shows it; a push can stay optional.
  **Size S-M. Visible:** the parent sees an inbox line, and a second pause in a row is refused on the phone.

## Low: security

### L1. A password change, 2FA reset or passkey removal keeps the session id, so a copied cookie survives (residual of 10-21 M1)

- **Evidence:**
  - `handlers/auth.rs:1416` (new password) and `:1553` (2FA reset), and `handlers/passkeys.rs:715` and `:862`
    (passkey removal, Kun passkey): each writes the new epoch into the current session and never calls
    `cycle_id`.
  - Sign-in and unlock do cycle it (`handlers/auth.rs:287, :509`; `handlers/passkeys.rs:239`; `handlers/lock.rs:290`).
  - `push::kept_row` (`push.rs:543`) picks the browser to keep from the session's endpoint note. Every page load
    rewrites that note for whichever browser loaded last (`handlers/notifications.rs:1295`).
- **Attack:** the 10-21 M1 attacker copied the parent's cookie, so both share one session record.
  - The parent changes the password ("every other session signed out"). The copier stays signed in, because the
    record now carries the new epoch.
  - If the copier's browser loaded a page last, its push subscription is the one kept.
  - Only Sign Out Everywhere removes it.
- **Fix:** call `session.cycle_id()` in these four handlers before the epoch is written. Keep the subscription named
  by the form's own endpoint field, as `logout` does (`handlers/auth.rs:546-549`), not the session note. **Size S.
  Not visible.**

### L2. A CalDAV Test sends the app password to any host that answers 401, and keeps trusting it

- **Evidence:**
  - `caldav.rs:647-652`: discovery starts with an empty `asked` set (`Hosts::Learn`).
  - `:527-532`: any host on the way (a redirect, or a principal or home-set href) that answers 401 is added to
    `asked` and gets `Authorization: Basic`.
  - `:772`: `asked` becomes the account's fixed `hosts`, so every later fetch and kid-event write sends the password
    there.
  - An e-mail address is turned into `https://<domain>/.well-known/caldav` (`:93-103`, `:639-644`).
- **Attack:** an open redirect on the provider, or the e-mail domain's web host answering `.well-known/caldav` with
  a redirect to its own 401, collects the parent's app password (an Apple ID app password can read the whole
  iCloud calendar and contacts). The account page lists the learned hosts (`device_calendar_caldav.html:30`), but
  nobody is asked.
- **Fix:** in `caldav.rs`, give the password only to the typed host, and to hosts on the same registrable domain or
  in a known provider pair (iCloud's `pNN-caldav.icloud.com`). Any other host needs the parent's yes on the Test
  result. **Size M. Visible:** a confirm on an unusual Test.

### L3. One leaked app-push endpoint starves every other app on the phone

- **Evidence:**
  - `unified_push/rate.rs:29-33`: a registration's sustained rate is 0.5/s and the phone's is 0.2/s, so one
    registration alone can spend the phone's whole bucket.
  - `charge_at` (`:96-129`) refuses without charging when the phone bucket is empty. A sender retrying faster than
    the real apps takes nearly every token as it refills.
  - The module says one flooded endpoint can't starve the phone's other apps (`rate.rs:9-10`), but the numbers
    don't do that.
  - The Matrix gateway path is never VAPID-signed, so for Matrix any holder of the pushkey can post
    (`matrix.rs:236-262`): the homeserver, its operator, anyone it leaked to.
- **Attack:** about 300 s of pushing empties the phone's 120 burst. After that, the phone's other apps (Element X,
  FluffyChat) get 429 for as long as the flood goes on. The phone is still woken every 5 s.
- **Fix:** in `rate.rs`, keep each registration's sustained rate well below the phone's (for example a third of
  it), or refuse a registration once it has taken more than half of the phone's tokens in the window. Add a test
  where one registration floods and another's push still lands. **Size S. Visible:** a very busy chat may see a
  429 sooner.

### L4. Wi-Fi sign-in "went wide" events: phone-chosen keys and no push budget

- **Evidence:** `logins.rs:394-418` takes any `opened_ms` up to the year 2100. `logins.rs:519-531` raises one event
  per wide session, keyed by that value (`parent_events.rs:517-544`). `Kind::push_budget` (`parent_events.rs:253-266`)
  covers only `SmsHeld` and `ContactRequest`.
- **Attack:** a token holder sends 10 made-up sessions with new `opened_ms` in each report: 20 pushes an hour (the
  general cap), on by default. At night they are held, then all arrive at 07:00.
- **Fix:** give `WifiSigninWide` a `PushBudget` (3 an hour) in `parent_events.rs`, and drop an `opened_ms` older
  than 24 hours or ahead of now in `logins.rs`. **Size S. Not visible.**

### L5. Time requests: the phone's reported zone resets the 3-a-day cap

- **Evidence:** `handlers/device_api.rs:2990-3010` counts today's requests from `start_of_today` in the phone's own
  zone (`time_state.zone`, any IANA name the phone reports).
- **Attack:** a token holder reports whichever zone's midnight passed most recently. "Today" then always started
  minutes ago, so the parent can get about 3 "more time?" pushes an hour (up to `EVENTS_AN_HOUR`, 20).
- **Fix:** in `handlers/device_api.rs`, also cap over a rolling 24 hours. **Size S. Not visible.**

### L6. One phone sets the catalog's logo for any package

- **Evidence:** `app_logos.rs:265-283`: a new or changed report replaces `tracked_apps.logo_hash` for the package the
  phone names. `catalog_logos` picks the logo most phones report (`:711`), so in a one-phone family that phone
  decides.
- **Attack:** a token holder reports `com.whatsapp` with any 128 px PNG. The parent's catalog and every phone's Apps
  page then show it. This is cosmetic but misleading. The PNG is re-encoded, so nothing runs.
- **Fix:** in `app_logos.rs`, take a catalog logo only from a phone that has the app through the catalog, and never
  replace a kept logo on one report alone. **Size S. Visible:** a new logo may show later.

### L7. Watchtower with `latest` runs any image pushed to the tag, outside the release-signing gate

- **Evidence:**
  - `DEPLOY.md:391-392` advises Watchtower with `HANDY_VERSION=latest`.
  - Root-run installs only run minisign-signed releases (`DEPLOY.md`, "Release signing"), and the signing key is in
    the tag-gated `release` environment.
  - A container install checks nothing: whoever can push `ghcr.io/palchrb/handy-server:latest` (a leaked token with
    `packages:write`, or a workflow added on any branch) has the server within Watchtower's next poll.
  - `deploy/env.example:6` still pins `0.24.0` and `DEPLOY.md:375` shows `0.23.0` (10-21 I8).
- **Fix:** in `DEPLOY.md`, advise a pinned `HANDY_VERSION`, updated by hand after `gh attestation verify
  oci://ghcr.io/palchrb/handy-server:X.Y.Z --repo palchrb/vibb-launcher`, or Watchtower limited to a digest you
  approved. Set `env.example` to the current release. Optionally restrict the GHCR package's Actions access to this
  repo's release workflow. **Size S. Visible to the operator:** no unattended updates.

## Low: privacy

### P1. Strangers' numbers, the kid's typed contacts and sign-in sites stay in the inbox 90 days; their own rows go after 30 (privacy)

- **Evidence:**
  - `parent_events.rs:1974-2010`: `sms_held` params hold the sender's number or name.
  - `:2040-`: `contact_request` params hold the name and number the kid typed.
  - `wifi_signin_wide` params hold the hosts visited in the sign-in window (`sites`, `:530`).
  - Their tables are pruned at 30 days (`retention.rs:536-559`), and `captive_portal_json` in `device_status` after
    about 2 days. The inbox keeps every row 90 days (`retention.rs:427-436`).
- **Fix:** in `retention.rs`, blank `sender`, `number` and `sites` in these kinds' params after 30 days, as is done
  for `phone_off.location` (`:406-420`). **Size S. Visible:** older inbox lines lose the number.

### P2. An answered MMS-address row keeps a contact's number for good (privacy)

- **Evidence:** `migrations/0137_device_mmsc_hosts.sql`: `picture_from` and `picture_at` stay on `allowed` and
  `declined` rows. Retention prunes only `state = 'open'` (`retention.rs:562-571`).
- **Fix:** null `picture_from` when the parent answers, in `handlers/calls_mmsc.rs`. **Size S. Not visible.**

### P3. A phone's Spotify token still carries the account's full scope (10-21 P3, open)

- **Evidence:** `spotify.rs:63-64` is unchanged. No PWA text says to bind a child's own Family account.
- **Fix:** one ⓘ line on the Spotify page in `templates/` and `i18n/*.json`: "Use the child's own Spotify account,
  never your own". **Size S. Visible.**

## Info

| ID | Finding | Where | Fix |
|---|---|---|---|
| I1 | A "LAN" calendar link or CalDAV account may be loopback (`is_lan` keeps 127/8 and `::1`), so the parent can point it at `127.0.0.1:3100`. Admin input only; only codes come back. | `music_net.rs:218-226`, `calendar_feeds.rs:170-181` | Refuse loopback in `is_lan`. |
| I2 | `is_private` misses `2002::/16`, `64:ff9b:1::/48`, IPv4-compatible `::a.b.c.d` and `fec0::/10` (10-21 I3, still open). Only reachable where the host routes them. | `music_net.rs:250-263` | Judge the embedded IPv4; add the ranges. |
| I3 | (privacy) MET gets a ~2 km cell of the kid's newest fix, at most every 30 min, with the household's IP and `MET_CONTACT`. Accepted as design 75 D8. | `handlers/weather.rs:48-66`, `weather.rs:86-110` | Say so in DEPLOY next to `MET_CONTACT`. |
| I4 | (privacy) A ticked CalDAV calendar's whole window (descriptions, locations) and the kid's own events are stored in clear and kept in backups. Link URLs and passwords are sealed. | `calendar_sources.feed_json`, `device_kid_events.events_json`, `handlers/backups.rs:258-268` | Say "a backup holds the family calendar" on the Backups page. |
| I5 | (privacy) The heartbeat's kept body is the phone's raw report, up to 128 KiB, for 7 hours. An expanded body is cleaned again, so it can't be abused. | `status_heartbeat.rs:176-202` | Keep the cleaned report instead. |
| I6 | A phone can learn whether a sibling's phone has an app: `wanted_logos` leaves out hashes any phone already stored. | `app_logos.rs:305-340` | Build the wanted list per phone, or accept it. |
| I7 | (privacy) App push keeps a Matrix notify's `event_id`, `room_id` and counts for up to a day, and package names and push times per registration. Nothing leaves the server. Message text is dropped (`matrix.rs:76`). | `unified_push/store.rs` | None. |
| I8 | Phone half: the phone picks the endpoint secret. The server checks only its form (27 base64url characters). Its strength is the launcher's random source. | `unified_push/store.rs:94-100`, `device.rs:66-72` | Launcher review: check the secret comes from `SecureRandom`. |

## Re-check of the 2026-10-21 items

| Item | State now | Evidence |
|---|---|---|
| M1 push survives password change / 2FA reset | **Fixed**, with a residual (L1 above) | `forget_subscriptions` in both handlers (`handlers/auth.rs:1420`, `:1556`) and on passkey changes; the epoch is stored per subscription, and `forget_stale` runs in `send_due` (`push.rs:665-694`, `:859`) |
| M2 backup drive never pruned | **Moot** | The root helpers and `backup_sync.sh` are gone since 0.67.0; DEPLOY says an old drive keeps its copies (`DEPLOY.md:454-466`). The Docker volume copies nothing out (`DEPLOY.md:409`). |
| M3 Storytel password to phones | **Open** | M1 above |
| L1 phone floods log/inbox/push | Fixed | 60 phone lines an hour, 20 events per kind an hour, 3 urgent pushes; emergency time capped at now (`67c50957`; `parent_events.rs:1181-1188`) |
| L2 LAN feed SSRF | Fixed | Pinned `resolve_to_addrs`, same-origin redirects, `0.0.0.0` not LAN (`music_net.rs:218-226`, `:400-432`) |
| L3 dns-events fields | Fixed | `handlers/device_api.rs:1239-1262` |
| L4 storage budget | Fixed | Daily row budgets, status rows replaced over 500 a day, 50 crash hashes (`retention.rs:57-160`) |
| L5 CSP inline script | Fixed | `admin_auth.rs:866`; test `no_template_has_inline_script` |
| L6 absolute session lifetime | Fixed | `SESSION_MAX_DAYS` 90 (`net.rs:252`); `security.rs:972-988` |
| L7 password checks not counted | Fixed | `admin_auth.rs:309-371` (3 wrong signs out) |
| L8 http server URL | Fixed | `config::check_server_url`, shared vectors `server_url_vectors.json` |
| L9 one failing DELETE skips pruning | Fixed | `rows()` per statement, failures listed (`retention.rs:245-610`) |
| L10 opaque call/time state | Fixed | `known_fields` (`handlers/device_api.rs:1425-1432`) |
| P1 location stored when off | Fixed | Intake drops when off (`zones.rs:458-464`); Off erases every fix (`retention.rs:277`) |
| P2 30-day status history | Fixed | 48 hours (`retention.rs:24`, `:292-310`) |
| P3 Spotify full scope | **Open** | P3 above |
| P4 VAPID `sub` names the tailnet | Fixed | `DEFAULT_SUBJECT` is the project page (`push.rs:282`) |
| P5 identifying User-Agent | Fixed | `net.rs:23` `vibb/1.0`, with a test |
| P6 tables never pruned | Fixed | Lifts, bans, install progress (`retention.rs:476-520`) |
| P7 security log keeps kid activity 180 days | Fixed | Phone lines 30 days (`retention.rs:34`); typed username blanked after 24 h (`:37`, `:370-378`); an RSS entry logged by host only (`handlers/music.rs:1215-1222`) |
| P8 OSM tiles see where the kid is | Unchanged | `admin_auth.rs:867`; record it as accepted or proxy the tiles |
| I1 push metadata (TTL, no padding) | Open | `parent_events.rs:187-193` (a day only for emergency and held SMS); no padding in `webpush.rs` |
| I2 cover URL in a warn log | Open | `music_sweep.rs:3345` - log the host |
| I3 IPv6 ranges | Open | I2 above |
| I4 keys beside the database | Partly | Docker keeps `data/keys` on its own volume (`deploy/compose.yaml`); systemd unchanged |
| I6 RSS `target` to the phones | Open | `music.rs:1189` - leave out `target` for `rss` |
| I7 prerestore copy unstripped | Partly | Now pruned to 3 (`startup.rs:114`, `:220-236`), still a full copy with sessions and push secrets |
| I8 `env.example` version, no verify step | Open | L7 above |
| I9 backup upload not logged; `unsubscribe` cross-account | Upload open; unsubscribe now intended and logged | `handlers/backups.rs:872-943` writes no event, and `settings_log.rs:28` skips it too, so an upload leaves no trace |
| I10 `install_progress` unscoped, unclamped | Open | `handlers/device_api.rs:2326-2342` - join `scoped_apps`, clamp 0-100 |
| I11 one `storytel_login_sent` line per fetch | Goes with M1 | `handlers/music_api.rs:276` |
| I12, I14, I16 | Accepted (unchanged) | - |
| I13 `local_target` control characters | Open (unreachable) | `parent_events.rs:1177-1179` - use `lock::local_next`'s byte rule |
| I15 kid's tailnet node reaches `/login` | Owner (ACL) | - |

## Areas checked and found sound

- **App push, public routes:**
  - No outbound request.
  - Secrets are stored only as SHA-256. Gone (tombstoned) and unknown secrets get the same answer, so there is no
    oracle beyond the ban count.
  - The client cap and bucket come before any database work. The body is read under its own bound before a handler
    slot is taken.
  - VAPID checks the key, then ES256 with the registered key only, then `exp` and `aud`. `kid`, `jku` and `x5u` are
    never fetched, and `crit` is refused (`vapid.rs:166-251`). A keyed registration is never reachable through the
    unsigned Matrix gateway (`matrix.rs:252-262`).
  - Matrix bodies are minimised to an allow-list. Queue caps apply per registration, per phone and for the whole
    store; the TTL is at most a day.
  - Registrations need the origin set, the phone's switch, a package that isn't stopped and an allowlisted package,
    under one `BEGIN IMMEDIATE`. There are hourly churn caps. Every allowlist writer prunes.
  - The origin must be `https://`, an origin only, never a tailnet IP. The `up` nudge goes only to its own phone's
    stream.
- **Passkeys:**
  - Origin, rpId hash, UP and UV, BE/BS and the COSE key/alg pairing are checked; the counter is enforced.
  - Challenges are 32 bytes, used once, last 5 minutes and are bound to the session, route and epoch.
  - `allowCredentials` is empty, so no usernames leak.
  - The last factor can't be deleted. Adding a passkey needs a counted re-auth and tells every parent.
- **Parents:** Reset and Remove need a counted re-auth and, in one transaction, bump the target's epoch and delete
  its passkeys and subscriptions.
- **Redirects:** `lock::local_next` allows bytes 0x21-0x7e only. The only `|safe` is the server-built QR SVG.
- **Phone intake:**
  - Every new query is scoped by `device_id`, and every new table cascades from `devices`.
  - SMS held, `sms_state`, contact requests, the MMS address, notification channels, shortcuts, airplane mode, the
    login browser and the certificate check are rebuilt from allow-lists, with lengths capped and times clamped.
  - Logo PNGs: content type and SHA-256 checked, decoded within 128×128 and 1 MiB, re-encoded, served by hash with
    `nosniff`.
  - The gzip status is inflated only after the token check, and the body limit applies to the inflated bytes.
- **Calendar and CalDAV:**
  - Every fetch goes through `Reach::Public` or a pinned `Reach::Any`. Hops are judged one by one; https never drops
    to http.
  - The ICS output is escaped and folded.
  - Phone UIDs must be `vibb-` + hex. Resource paths are built by the server.
  - XML with a DOCTYPE subset is refused.
  - A phone token can change only its own kid events, never the parent's. Writes are conditional and never follow a
    redirect.
  - Link URLs and CalDAV passwords are sealed with the music key and never go to phones.
- **Catalog:**
  - The F-Droid `entry.jar` JAR signature is verified with `ring` against a pinned repo key, then the index hash,
    then the APK hash, failing closed (`fdroid.rs:23-29`, `jar_signature.rs`).
  - APK downloads are scoped per phone (`handlers/device_api.rs:2521-2570`).
- **Backups:** sessions, the Tailscale key and push secrets are stripped. New secrets (CalDAV passwords, calendar
  link URLs, the Storytel login) are sealed with keys that are never backed up.
- **Deploy:** the image is distroless and runs as uid 65532; compose uses read-only, `cap_drop: ALL` and
  `no-new-privileges`. Distrusted roots are removed at build time. The Caddy example strips `Tailscale-User-Login`
  and buffers push bodies. DEPLOY says to skip `/up/*` in access logs.

## Privacy inventory: what is new since 10-21

| Data | Stored in | Kept | Leaves the server to |
|---|---|---|---|
| Held and blocked SMS senders (number or name, time, class; never text) | `device_sms_held`; inbox params | 30 days; **90 in the inbox** (P1) | Parent push (encrypted) |
| The kid's contact requests (name, number) | `device_contact_requests`; inbox params | 30 days; **90 in the inbox** (P1) | Parent push |
| MMS address questions (operator, host, a contact's number) | `device_mmsc_hosts` | Open: 30 days; **answered: for good** (P2) | None |
| Wi-Fi sign-in sessions (hosts visited) | `device_status.captive_portal_json`; inbox params | About 2 days; **90 in the inbox** (P1) | Parent push |
| App logos (PNG by hash, package) | `device_app_logos`, files | While reported | None |
| App push registrations (package, secret hash, VAPID key, push times) and queue (minimised Matrix or encrypted bodies) | `up_registrations`, `up_messages`, `up_delivery` | Queue at most a day; registrations until the app or phone goes, or 30 days unseen | Nothing (no outbound calls) |
| Calendar links' and CalDAV calendars' events (titles, descriptions, locations) | `calendar_sources.feed_json` | While ticked; in backups (I4) | The ICS or CalDAV host is fetched by the server |
| The kid's own events | `device_kid_events`, `kid_caldav_events` | While marked; ended rows lose text after 60 days | The parent's CalDAV server (by design) |
| Weather cell (~2 km) | `weather_cells` | 48 hours; erased when Location is off | MET, with IP and contact (I3) |
| Heartbeat base body | `device_report_base` | 7 hours (I5) | None |
| Passkeys (public key, name, counter) | `admin_passkeys` | Until removed | None |

## Fixes in priority order

1. **M1** - retire `GET /api/devices/music/storytel` (server and launcher, one commit; design 58 step 3). **S.** Not
   visible.
2. **M2** - one live phone pause at a time, a daily cap and an inbox line (`handlers/rule_pause.rs`). **S-M.**
   **Visible.**
3. **L1** - cycle the session id on a password change, 2FA reset or passkey change; keep the push row named by the
   form (`handlers/auth.rs`, `handlers/passkeys.rs`, `push.rs`). **S.** Not visible.
4. **L3** - a registration's sustained push rate below the phone's (`unified_push/rate.rs`). **S.** **Visible** (a
   busy chat may hit 429 sooner).
5. **L2** - the CalDAV password only to the typed host's domain, or the parent confirms (`caldav.rs`). **M.**
   **Visible.**
6. **L4, L5** - push budget and `opened_ms` window for Wi-Fi sign-in events; a rolling 24 h cap for time requests
   (`parent_events.rs`, `logins.rs`, `handlers/device_api.rs`). **S.** Not visible.
7. **P1, P2** - blank numbers and sites in old inbox params; null `picture_from` on answer (`retention.rs`,
   `handlers/calls_mmsc.rs`). **S.** **Visible** (old inbox lines lose the number).
8. **L7** - DEPLOY: pinned image plus `gh attestation verify` instead of Watchtower on `latest`; `env.example` to the
   current version. **S.** **Visible** to the operator.
9. **L6** - catalog logo only from a phone that has the app through the catalog (`app_logos.rs`). **S.** **Visible**
   (logos may lag).
10. **P3** - one ⓘ line on the Spotify page (`templates/`, `i18n/*.json`). **S.** **Visible.**
11. Infos and the open 10-21 Infos (I1 loopback, I2 IPv6 ranges, I2/I6/I10/I13 of 10-21, the backup upload event):
    one small batch. **S.** Not visible.
