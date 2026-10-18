# 35 - Notifications to the parent, phase 1: an inbox in the PWA and Web Push (buildable spec)

Backlog item "Notifications to the parent, phase 1" (PLAN.md, approved 2026-10-16). Server only: no launcher or
music change, no device API change, no contract file touched.

## 0. Goal and non-goals

**Goal.** One place in the PWA where the parent sees what happened on the phones: a bell with an unread count on
every tab page and a page `/notifications`. The events live on the server, so nothing depends on a push arriving.
On top, Web Push to the installed PWA (iPhone 16.4+ from the Home Screen, macOS Safari 16+, Chrome, Firefox, Edge),
end-to-end encrypted, with a receipt from the service worker so the server knows which pushes were shown and can
say "Notifications may not be showing" per device.

**Non-goals.**
- Phase 2 (ntfy, Matrix, the second channel after ~2 min without a receipt). Phase 1 leaves it ready:
  - `push_deliveries.channel` (`webpush` only now) - an ntfy or Matrix delivery is another row of the same table;
  - every delivery has `accepted_at` and `receipt_at`, so "no receipt within 2 min" is one query;
  - `parent_events` kinds carry an `urgent` flag (§2.2) - phase 2 escalates exactly the urgent ones.
- A new blocked app and an extra-time request (the user's "later"): the kinds are reserved (§2.2), not raised.
- Wrong PIN tries as events (they stay in the PWA's Recent use); message content of any kind (there is none).
- Offline caching in the service worker: still none.
- Per-parent read state: there is one admin account (`security::bootstrap_admin` is the only insert).

## 1. Decisions

1. **The events are rows on the server** (`parent_events`, §3). The inbox reads them; Web Push is one way to hear
   about a new one. A switched-off kind is neither stored nor pushed.
2. **One raise function**: `parent_events::raise(conn, NewEvent) -> Option<i64>`, `INSERT ... ON CONFLICT DO
   NOTHING RETURNING id` on `UNIQUE (device_id, kind, dedup_key)`. A repeated report, a retried POST or two parallel
   ones give one row. It returns the new id only when it inserted; only then are deliveries queued (§4.6) and the
   sender woken (`AppState.push.wake()`, a `tokio::sync::Notify`) - after the caller's transaction commits.
3. **Web Push is built in-house on `ring`** (§4.2), no new crate.
4. **Push endpoints are an allow-list** of the four push services (§4.5), never any URL a browser hands over.
5. **One payload for every browser: Safari's declarative Web Push format with `mutable: true`** (§5.3). Safari
   18.4+ shows it even when the service worker fails; every other browser gets the same JSON as plain push data.
6. **The receipt is authenticated by a per-delivery token** in the payload, not the session cookie (§7).
7. **Health is computed when read** from three subscription columns (§6); no background job marks it.
8. **"Sign Out Everywhere" also deletes every push subscription** (a lost phone stops getting titles); a password
   change doesn't.

## 2. Events

### 2.1 Where each is raised (one place per kind)

| kind | raised in | dedup_key | default |
|---|---|---|---|
| `emergency_call` | `device_api::status`, after the `device_status` insert, from `call_state.last_emergency_call_at` (parsed as `calls.rs` does) when it is at most 24 h old by the server's clock | the instant, RFC 3339 with ms, UTC | always on |
| `pin_lockout` | `handlers::parent_pin::pin_events`, in the transaction that writes `parent_pin_lockout`, only for a newly stored row | the phone's `event_id` (32 hex) | on |
| `pin_used` | the same place, for a newly stored `unlocked` (`parent_pin_used`) | the phone's `event_id` | on |
| `foreign_access` | `handlers::window_relock::log_foreign_access`, when the set changed and the new one isn't empty | `<previous device_status id or 0>:<hex(sha256(new sanitized json))[..16]>` | on |
| `unseen_boot` | `device_api::status`, where `boot_witness::log_line` returns a line (the count rose) | `<count>:<last_at_ms>` | on |
| `offline` | `parent_events::run_offline_check` (§2.3) | `devices.last_seen_at` text | on, 6 h |
| `app_blocked`, `extra_time_request` | reserved, not raised in phase 1 | - | - |

- The emergency call's 24 h limit keeps an upgrade from announcing a call made days ago; the dedup key keeps the
  phone's 30-minute reports from raising it again.
- `pin_*`: `parent_pin::clean_event` already decides "newly stored" (`INSERT OR IGNORE` rows affected). The event's
  `happened_at` is the phone's `at_ms` unless `clock_off`, then the arrival.
- `foreign_access`: `previous_foreign_access` also returns the previous row's id (one more column in its query).
  Two parallel reports see the same previous row, so they make one key; the same set coming back after it went
  away is a new key, so a new event.
- `log_foreign_access` and the status intake pass `&AppState` (they need `push.wake()`), not only the pool.

### 2.2 Words and targets

Words are made once, at raise, from typed values (never phone-supplied text in a title), times local via
`display_time::Zone`. Stored, so the push and the inbox say the same; a renamed phone keeps old rows' words.

| kind | title (<= 60 chars) | summary (<= 100) | target | urgent |
|---|---|---|---|---|
| `emergency_call` | "Anna called an emergency number" | "At 14:02" | `/devices/{id}/calls` (the group the finding renders in) | yes |
| `pin_lockout` | "Parent PIN locked on Anna" | "Too many wrong tries · until 14:30" | `/devices/{id}/lock#parent-pin` | yes |
| `pin_used` | "Parent PIN used on Anna" | "Time lock · 14:02" (the `entry` in words) | `/devices/{id}/lock#parent-pin` | no |
| `foreign_access` | "An app may read the PIN on Anna" | "1 app with device admin" | `/devices/{id}#foreign-access` | yes |
| `unseen_boot` | "Anna started without Vibb One" | "Safe mode or a start cut short" | `/devices/{id}/about#diagnostics` | no |
| `offline` | "Anna offline for 6 h" | "Last seen 08:12" | `/devices/{id}` | no |

`detail` (optional, inbox only, never pushed): e.g. the foreign apps by the phone's name for them. Typed as
`parent_events::Kind` with `title()`, `target()`, `urgent()`, `ttl()`, `coalesce()`; the table above is
`parent_events::tests`' table.

### 2.3 The offline check

`run_offline_check` (spawned from `main` only, every 5 min; a pure `due(...)` it calls is tested): a phone that is
enrolled with a token, whose last contact (`devices::connection_of`'s newest of `last_seen_at`, the in-memory last
access and an open stream) is older than `offline_hours`, raises `offline` keyed by `last_seen_at`. One event per
silent stretch; the next report moves `last_seen_at`, so the next stretch is a new key. Guard: the check waits until
the server has run `offline_hours` (`AppState.started_at`), so a server that was down itself announces nothing.
A phone not set up, Disconnected or never seen raises nothing.

### 2.4 Switches and retention

- `notification_settings` (singleton `id = 1`): `pin_used`, `pin_lockout`, `foreign_access`, `unseen_boot`
  (0/1, default 1), `offline_hours` (0 = off, 2/6/12/24, default 6). No column for `emergency_call`. `raise`
  reads it (one row) and returns `None` for an off kind.
- `retention::prune`: `parent_events` older than 90 days by `created_at`; `push_deliveries` older than 30 days;
  `push_subscriptions` in state `gone` for 30 days. Deleting a phone cascades its events.

## 3. Data model

`0078_parent_events.sql` (step 1):
- `parent_events` - `id`, `device_id` (NOT NULL, FK `devices` ON DELETE CASCADE), `kind`, `dedup_key`, `title`,
  `summary`, `target` (a path starting `/`, checked at raise), `detail` (NULL), `happened_at` (RFC 3339),
  `created_at` (`datetime('now')`), `read_at` (NULL); `UNIQUE (device_id, kind, dedup_key)`; index
  `(created_at DESC)`, partial index `ON parent_events(id) WHERE read_at IS NULL` (the bell's count).
- `notification_settings` - as §2.4, its row inserted by the migration.

`0079_web_push.sql` (step 2):
- `push_subscriptions` - `id`, `admin_id` (FK `admin_users` ON DELETE CASCADE), `endpoint` (UNIQUE, <= 1024),
  `p256dh` (65 bytes, BLOB), `auth` (16 bytes, BLOB), `origin` (the page's origin at subscribe, from `Origin`),
  `label` ("iPhone · Safari", from the User-Agent at subscribe; the raw UA is never stored), `vapid_fp` (the key it
  was made with), `created_at`, `last_accepted_at`, `last_receipt_at`, `unacked` (INTEGER, accepted since the last
  receipt), `first_unacked_at`, `state` (`active` | `gone`), `gone_reason`, `gone_at`.
- `push_deliveries` - `id`, `channel` (`webpush`), `subscription_id` (FK, CASCADE), `event_id` (NULL for a test or
  a summary; FK, SET NULL), `kind` (`event` | `summary` | `test`), `token_hash` (SHA-256 of the receipt token),
  `status` (`queued` | `accepted` | `failed` | `dropped`), `attempts`, `next_try_at`, `http_status`, `reason`,
  `created_at`, `accepted_at`, `receipt_at`, `clicked_at`.

## 4. Web Push on the server

### 4.1 The VAPID key

`data/keys/vapid-p256.pk8` (PKCS#8 from `ring::signature::EcdsaKeyPair::generate_pkcs8`), made at first need -
the first render of the inbox's This Device group, which hands the browser the public key - 0600 in the 0700
directory, with `music_secret`'s file helpers (moved to a shared `keys.rs` if they're private). Loaded into
`AppState.push` (a `OnceCell`). Backups never copy `data/keys/` (as the music key). `vapid_fp` = first 16 hex of
SHA-256 of the public key. A lost key: at startup every `active` subscription with another `vapid_fp` becomes
`gone` ("Turned off - turn it on again on that device"); a 403 `VapidPkHashMismatch` does the same.

### 4.2 Crypto: in-house on `ring`, not the `web-push` crate

- `ring` 0.17 is already compiled in (the rustls provider, `main.rs`). It has all four pieces: P-256 ECDH
  (`agreement::ECDH_P256`, ephemeral keys - exactly RFC 8291's per-message sender key), ES256 with the fixed r||s
  signature JWS needs (`ECDSA_P256_SHA256_FIXED_SIGNING`), HKDF-SHA256 (`ring::hkdf`, any info and length) and
  AES-128-GCM (`aead::AES_128_GCM`).
- The `web-push` crate would bring `ece` (whose default backend is OpenSSL, absent from the distroless static
  image), a JWT crate and its own HTTP client features - a new subtree in `Cargo.lock`, each crate to review and
  pin, for a few hundred lines of well-specified code. No.
- `src/webpush.rs`, pure (no I/O): `encrypt(rng, ua_public, auth, plaintext) -> Vec<u8>` (RFC 8291 §3.4 key
  derivation with `"WebPush: info\0" || ua_public || as_public`, RFC 8188 `aes128gcm` single record: salt (16) ||
  rs = 4096 (u32 BE) || idlen 65 || as_public, then the ciphertext of `plaintext || 0x02`), `vapid_jwt(key, aud,
  sub, now) -> String`, `aud_of(endpoint)`, `topic(secret, device_id, kind)`, `allowed(endpoint)`. `rng` is a
  `&dyn ring::rand::SecureRandom` - `SystemRandom` in production, `ring::test::rand::FixedSliceSequenceRandom` in
  tests, which makes RFC 8291 Appendix A reproducible end to end (its fixed sender key and salt).
- The derivation is split as `derive(ecdh_secret, auth, ua_public, as_public, salt) -> (cek, nonce)` so each
  Appendix A intermediate (PRK_key, IKM, PRK, CEK, NONCE) is asserted too.

### 4.3 The request (Apple's rules, which every service accepts)

- `POST <endpoint>`, HTTP/1.1 (reqwest is built without `http2`; Apple accepts 1.1), TLS with SNI and ALPN by
  rustls. One `reqwest::Client` for push: `redirect::Policy::none()`, connect 5 s, total 15 s, at most 4 requests
  in flight (far under Apple's 100 unacknowledged).
- `Content-Encoding: aes128gcm`, `Content-Type: application/octet-stream`.
- `Authorization: vapid t=<jwt>, k=<public key, base64url, uncompressed 65 bytes>`. JWT `{"typ":"JWT",
  "alg":"ES256"}`, claims `aud` = the endpoint's origin (scheme://host, `:port` only when not 443), `exp` = now +
  12 h, `sub` = `VAPID_SUBJECT` from `.env` when set (`mailto:` or `https:`), else the subscription's `origin`.
  Cached per `aud` and reused for an hour (never minted more often; `exp` stays <= 24 h).
- `TTL`, `Urgency`, `Topic` per kind:

  | kind | TTL | Urgency | Topic |
  |---|---|---|---|
  | `emergency_call` | 86400 | high | none (never coalesced away) |
  | `pin_lockout`, `foreign_access` | 43200 | high | per phone and kind |
  | `pin_used`, `unseen_boot`, `offline` | 43200 | normal | per phone and kind |
  | summary (§4.6) | 43200 | normal | one fixed |
  | test | 600 | high | one fixed |

- `Topic` = base64url (no padding) of the first 16 bytes of HMAC-SHA256(key = SHA-256 of the VAPID key
  file's bytes, `"topic:<device_id>:<kind>"`) - 22 characters, stable, says nothing about the phone or the kind to the push
  service.
- Payload plaintext capped at 3072 bytes before encryption (Apple's 4 KB is the encrypted body); `title` cut at 60,
  `body` at 100 characters on a char boundary. Over the cap is a bug: the delivery fails with `reason = too_large`.

### 4.4 Responses

| answer | action |
|---|---|
| 201 (any 2xx) | `accepted`, `accepted_at`; subscription `last_accepted_at`, `unacked += 1`, `first_unacked_at` if NULL |
| 404, 410 | `dropped`; subscription `gone`, `gone_reason = expired`; security event `push_subscription_gone` |
| 403 with `VapidPkHashMismatch` or `BadJwtToken` 3 times in a row | subscription `gone`, `gone_reason = refused` |
| 429, 500, 502, 503, 504, a network error | retry: 1 min, 5 min, 30 min, 2 h (`Retry-After` honoured up to 2 h), then `failed`; never after the TTL ran out |
| other 4xx | `failed`; log the JSON `reason`, `apns-id`, status and the endpoint's host - never the path |

Nothing secret is logged: no endpoint path, key, auth secret, JWT or token.

### 4.5 Endpoints: an allow-list

`webpush::allowed(endpoint)`: `https`, no userinfo, port 443 or none, a host name (no IP literal) that is
`fcm.googleapis.com`, `updates.push.services.mozilla.com`, or ends with `.push.apple.com` or `.notify.windows.com`
(a label boundary: `push.apple.com.example.net` fails). Checked at subscribe (refused with "This browser's push
service isn't supported") and again before every send. Why not any https URL: the endpoint arrives from a browser
session, and the server would POST wherever it says - a stolen session or an XSS could aim it at the Pi's LAN or
loopback. Chrome, Edge, Firefox, Safari, Brave and Samsung Internet use these four. Redirects are never followed.

### 4.6 Queueing, bursts, the sender

- `raise` (in the same transaction) inserts one `queued` delivery per `active` subscription.
- `push::run_sender` (spawned from `main` only): waits on `wake()` or the earliest `next_try_at`; takes due rows
  oldest first.
- Per subscription a token bucket of 6 pushes per 10 min. `emergency_call` and tests skip it. Past it, waiting
  event deliveries are folded into one `summary` push ("3 new notifications", target `/notifications`) when a token
  frees; the folded rows become `dropped` with `reason = folded`, the summary row carries no event.
- Tests drive `push::send_due(state, now)` directly; the task only loops over it.

## 5. The PWA

### 5.1 The bell and the inbox

- `partials/bell.html` in the `.title-bar` of the five tab pages (Devices, Apps, Locate, Music, Settings; Locate and
  Settings get the bar they lack). On the right, before the page's own action ("+", Edit). A link to
  `/notifications?from=<tab>`, `aria-label="Notifications, 3 unread"`, a red count pill when > 0 ("99+" past 99).
  The count is `parent_events::unread_count` (the partial index), passed as `bell: Bell` by each tab handler.
- `/notifications` (`handlers::notifications::page`, `notifications.html`): back link to the tab it came from
  (`from`, one of the five, else Devices), large title "Notifications", the tab bar with no tab current. Groups:
  1. **Warnings** (only when one applies): an orange attention row per unhealthy subscription (§6), to `#devices`.
  2. **This Device** (`#this-device`, §5.2).
  3. **Inbox** (`#inbox`): a picker "All phones / Anna / ..." (`?device=`, a GET filter, `<noscript>` Show), then
     the newest 100 events, newest first, a "Show older" link (`?before=<id>`). A row: the phone's avatar, the title
     (bold with a blue dot while unread), the summary and the relative time (exact in `title=`), a chevron. Each row
     is a `<form method="post" action="/notifications/{id}/open">` with the row as its button: it marks the event
     read and 303s to its target (no GET changes state; `leaves_the_page` gets `["notifications", _, "open"]`).
     Footer action "Mark All as Read" (`POST /notifications/read-all`, back to `/notifications#inbox`, honours
     `device`). Empty: "Nothing yet. Events are kept 90 days."
  4. **Getting Notifications** (`#devices`): one row per subscription - label, a badge (On / May not be showing /
     Turned off), "Last shown 2 h ago"; an ⓘ with the counts and what to check; actions Send a Test
     (`POST /notifications/subscriptions/{id}/test`) and Remove… (a `<details>` confirm,
     `POST /notifications/subscriptions/{id}/delete`). Both back to `/notifications?notice=…#devices`, the flash in
     the group.
  5. **Notify Me About** (`#events`): one form, the handler writes every field (`POST /notifications/settings`,
     back to `#events`): "Emergency call" (a checked, disabled switch, "Always on"), "Parent PIN used", "Parent PIN
     locked", "An app may read the PIN", "Start without Vibb One", picker "Offline for" (Off, 2, 6, 12, 24 h).
- Settings hub: a row "Notifications · 3 unread" to `/notifications?from=settings`.
- Every form carries its group's fragment in its action and its redirect (`check_forms("/notifications")`,
  `assert_lands`); scroll-restore keeps the parent's place.

### 5.2 This Device: subscribe from a tap

- `static/notifications.js` (deferred, ES5 apart from what the Push API forces; no inline JS). States, each a row
  value and one action row:
  - no `PushManager`, or not a secure context: "Not available in this browser";
  - iPhone/iPad not standalone (`navigator.standalone === false`): "Add Vibb Phone to the Home Screen first" with
    the steps (Share › Add to Home Screen, then open it from there);
  - `Notification.permission === "denied"`: "Blocked - allow notifications for Vibb Phone in Settings";
  - subscribed here and known to the server: "On" + "Turn Off on This Device";
  - else "Off" + "Turn On Notifications".
  Without JS the footer says notifications need JavaScript.
- "Turn On": the click handler calls `Notification.requestPermission()` first, synchronously in the handler (Safari
  requires the user gesture), then `pushManager.subscribe({userVisibleOnly: true, applicationServerKey})` with the
  form's `data-key`, fills the form's hidden `endpoint`, `p256dh`, `auth` and submits
  `POST /notifications/subscriptions` (303 to `/notifications?notice=push_on#this-device`).
- "Known to the server": the page renders `data-subscribed` = the hex SHA-256 prefixes (16) of the admin's active
  endpoints; the script hashes its own endpoint (`crypto.subtle`) and compares. A local subscription the server
  doesn't know (removed, or `gone`) shows "Off" and Turn On posts it again (upsert on `endpoint`, state reset).
- "Turn Off": `subscription.unsubscribe()`, then `POST /notifications/subscriptions/unsubscribe` with the endpoint.
- The subscribe handler: full auth; `endpoint` allowed (§4.5) and <= 1024; `p256dh` base64url of 65 bytes
  beginning 0x04 and a valid point (one throwaway `ring` agreement); `auth` 16 bytes; at most 10 active per admin;
  `origin` from the `Origin` header (the CSRF guard already matched it to `Host`); security event `push_subscribed`
  with the label. A refusal flashes in `#this-device`.

### 5.3 The service worker (`templates/sw.js`)

The payload (plaintext JSON, every browser):

```json
{"web_push": 8030,
 "notification": {"title": "Anna called an emergency number", "body": "At 14:02", "lang": "en",
   "navigate": "https://kidphone.example.ts.net/devices/3/calls", "tag": "e812",
   "data": {"d": 4411, "t": "<receipt token, 22 chars base64url>"}},
 "app_badge": 3, "mutable": true}
```

- `navigate` is absolute (the subscription's `origin` + target): declarative push requires it. `app_badge` is the
  unread count when sent. Safari 18.4+ shows this itself if the worker doesn't; `mutable: true` makes it dispatch
  the `push` event to our worker first, so the worker shows it (and posts the receipt). Older Safari, Chrome and
  Firefox see plain push data and the worker does the same. Field placement follows the WHATWG declarative push
  message; step 2's device check confirms it on iOS 18.4.
- `push`: `event.waitUntil(...)` parse `event.data.json()`; on any failure show "Vibb Phone" / "Something new -
  open Notifications" with target `/notifications` (never an invisible push). `showNotification(title, {body, tag,
  lang, data: {navigate, d, t}, icon: "/static/icons/icon-192.png"})`, then `self.navigator.setAppBadge(app_badge)`
  where it exists, then the receipt: `fetch("/notifications/receipt", {method: "POST", credentials: "omit",
  headers: {"Content-Type": "application/json"}, body: {"d", "t", "a": "shown", "at": Date.now()}})` - after
  `showNotification` resolves. A failed receipt goes into IndexedDB (at most 20, oldest dropped) and is retried at
  the next `push` and `notificationclick`; the server takes the receipt's `at` (clamped to accepted..now) as
  `receipt_at`, so a late receipt still counts as shown in time.
- `notificationclick`: close it; post `"a": "clicked"` (marks the event read); focus an open client on our origin and
  `navigate` it to the target, else `clients.openWindow(target)`. Only same-origin targets (the worker checks).
- No `fetch` handler, still. The CSP needs no change: the worker is `'self'`, the push channel is the browser's,
  the receipt is same-origin (`connect-src 'self'`).
- `static/register-sw.js`: registers as today; adds the app badge on load - the bell's `data-unread` (the five tab
  pages and `/notifications`): `setAppBadge(n)` or `clearAppBadge()` where supported.

## 6. Delivery health

- Per subscription: `last_accepted_at`, `last_receipt_at`, `unacked`, `first_unacked_at`. A receipt (any of its
  deliveries) sets `unacked = 0`, `first_unacked_at = NULL`.
- `push::health(sub, now)`, typed: `Gone(reason)` | `MayNotShow` when `unacked >= 3` or `first_unacked_at` is older
  than 24 h | `Ok` | `NeverSent`.
- `MayNotShow`: on `/notifications` the warning row ("Notifications may not be showing on iPhone · Safari") and the
  subscription's badge; on the Devices list a short orange row at the top (as the sign-in warnings,
  `notifications::warning_row`) to `/notifications#devices`. Its ⓘ: added to the Home Screen and opened from there?
  Notifications allowed for Vibb Phone in Settings? A Focus mode on? The phone reaching the server (the receipt
  needs the tailnet)? Then Send a Test.
- `Gone`: badge "Turned off", row text "Turned off on that device - turn it on again there"; no warning row (it is
  known, not suspected); kept 30 days, then pruned.
- Send a Test: a visible push ("Test from Vibb Phone" / "If you see this, notifications work on <label>"), at most
  one per subscription per 30 s (else the flash says wait). Its acceptance and receipt count like any other.

## 7. Security

- **Admin gate and CSRF** on every route; `/notifications/*` are admin routes with full auth, except the receipt.
- **The receipt** (`POST /notifications/receipt`): a service worker's same-origin fetch would carry the
  `SameSite=Strict` cookie, but the session may have expired (30 days idle) while the subscription lives on, and a
  receipt must not depend on it. So: a random 128-bit token per delivery, in the encrypted payload, stored as
  SHA-256 in `push_deliveries.token_hash`; the route takes `{d, t, a, at}`, JSON <= 1 KiB, compares in constant time,
  answers 204 on a match and 404 otherwise, and changes only that delivery's `receipt_at`/`clicked_at`, its
  subscription's counters and (clicked) its event's `read_at`. Repeating it is harmless. Behind the admin gate (a
  tailnet client) and `csrf_guard` (the worker's fetch is `Sec-Fetch-Site: same-origin`); its own limit bucket
  (60 per minute per client key). `credentials: "omit"`: the cookie isn't needed.
- **Subscriptions** belong to the admin (`admin_id`); Sign Out Everywhere deletes them (Decision 8); deleting the
  admin cascades.
- **Nothing secret in pages or logs**: pages carry the VAPID public key and endpoint hash prefixes only - never an
  endpoint, `p256dh`, `auth`, token or the private key (a test greps the page). The key file 0600; not in backups.
- **What a push service sees**: the endpoint, size, TTL, Urgency and an opaque Topic; the payload is RFC 8291
  ciphertext. **A stolen subscription** (endpoint + `p256dh` + `auth`, e.g. from a database copy) decrypts nothing
  without the browser's private key, and pushing to it needs our VAPID private key. A stolen *subscribed device*
  receives titles until Remove or Sign Out Everywhere. Titles name a phone and a kind, never content; `detail`
  never leaves the server.
- **SSRF**: the allow-list (§4.5), no redirects, no IP literals.

## 8. Tests (required)

- `webpush::tests`: RFC 8291 Appendix A end to end (fixed sender key and salt) and each intermediate; the header
  layout and the 0x02 delimiter; the 3072-byte cap; the JWT's header, `aud` for each service and a port, `exp`,
  `sub` fallback, the signature verified with `ring`; the JWT reused within the hour, renewed after; `topic` is 22
  base64url characters and differs per phone and kind; `allowed` - each service, `push.apple.com.example.net`,
  `http:`, an IP, a port, userinfo.
- `parent_events::tests`: the words/targets table; `due` for the offline check (the started-at guard).
- `src/tests/notifications.rs` (TestApp): a fake push service - an axum server on 127.0.0.1 that records requests,
  decrypts each body with the fake browser's fixed key and answers a scripted status; the allow-list admits it only
  through a `#[cfg(test)]` constructor of `PushConfig`. Cases:
  - each kind from its real input (a status report, `pin-events`, the offline check), posted twice → one event;
  - a switched-off kind → nothing; the emergency call can't be switched off (a forged field is ignored);
  - the bell's count on all five tab pages; `/notifications` per phone, read/unread, `open` marks read and 303s
    to the target, Mark All as Read; `check_forms("/notifications")` and `assert_lands` for every redirect;
  - subscribe: refusals (host, key length, 11th), upsert; unsubscribe; Remove; Sign Out Everywhere clears them;
  - send: headers (TTL, Urgency, Topic, `Authorization`, `Content-Encoding`), the decrypted payload's fields, no
    `detail`; 201 → counters; 410 → gone and its text; 429 with `Retry-After` → retried; 400 `reason` stored;
    20 `pin_used` → 6 pushes and one summary; an emergency call passes a full bucket;
  - the receipt: wrong token 404 and nothing changed, a repeat 204, a cross-site `Sec-Fetch-Site` 403, no session
    needed, `clicked` marks read; health: 3 accepted → the warning on `/notifications` and Devices, a receipt clears
    it; a day-old unacked one; a changed VAPID key at startup → gone;
  - no endpoint, `p256dh`, `auth` or token in any page; the key file 0600 and absent from a backup zip;
  - retention: 90 / 30 / 30 days.
- `jstest/sw.test.js` (templates/sw.js with `{{ app_version }}` replaced, a fake `self`): `push` always shows (bad
  JSON, no data), the receipt is posted only after `showNotification` resolves, a failed receipt is queued and
  retried, the badge set; `notificationclick` focuses or opens the same-origin target, refuses another origin,
  posts `clicked`; no `fetch` listener. `jstest/notifications.test.js`: `requestPermission` is called before any
  await in the click, denied shows Blocked, success fills the fields and submits, the hash comparison.

## 9. Steps

1. **Events and the inbox** (server, migration `0078_parent_events.sql`): §2, §3's first half, §5.1 without This
   Device and Getting Notifications, the bell on the five tab pages, the Settings hub row, retention. Shippable alone: the inbox
   works with no push.
2. **Web Push** (server, `0079_web_push.sql`): `webpush.rs`, `push.rs`, §4, §5.2, §5.3, §6, §7; register-sw.js;
   DEPLOY.md; `server/CLAUDE.md`. Device checks before it's called done: iPhone on iOS 18.4+ and on 16.4-18.3 if
   one is at hand (Home Screen, subscribe, each kind, the receipt, Focus on, the worker killed → Safari's declarative
   fallback), macOS Safari, Android Chrome, desktop Firefox; a 410 by removing the app from the Home Screen.

`server/DEPLOY.md` ("Notifications"): the server needs outbound HTTPS (443) to `*.push.apple.com`,
`fcm.googleapis.com`, `*.notify.windows.com` and `updates.push.services.mozilla.com` - from the Pi's firewall and
from the Docker network (no egress rule blocks it by default); `VAPID_SUBJECT` optional; the VAPID key lives in
`data/keys/` and isn't backed up - a restore onto a new machine means turning notifications on again on each device;
the parent's device must reach the server (tailnet) for receipts.

## 10. Open questions (with a recommendation)

1. **"Parent PIN used" on by default?** It fires when the parent uses it too. Recommend on: the point is to catch
   a use the parent didn't make; the parent can switch it off.
2. **Offline default 6 h** (choices Off/2/6/12/24 h)? Recommend 6 h: the backstop sync is 30 min, a phone left off
   overnight is normal and 6 h avoids a nightly alert for a phone switched off at 21:00 - unless the parent wants
   12 h.
3. **No escape hatch in the allow-list** (no `PUSH_EXTRA_HOSTS`)? Recommend none in phase 1; add one if a browser
   the family uses turns out to need another host.
