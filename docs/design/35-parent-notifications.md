# 35 - Notifications to the parent, phase 1: an inbox in the PWA and Web Push (buildable spec)

Backlog item "Notifications to the parent, phase 1" (PLAN.md, approved 2026-10-16). Step 1 is the server plus one
optional status-report field in both directories (`battery`, §2.5); step 2 is server only; step 3 is the launcher's
part (the battery reading and its receiver, a sync after an emergency call). Revised after design QA
(`docs/design/qa-35-design.md`, §11) and after the owner's decisions of 2026-10-18 (§10).

## 0. Goal and non-goals

**Goal.** One place in the PWA for what happened on the phones: a bell with an unread count on every tab page and
`/notifications`, from events stored on the server. On top, end-to-end encrypted Web Push to the installed PWA
(iPhone 16.4+ from the Home Screen, macOS Safari 16+, Chrome, Firefox, Edge), with a receipt from the service worker
so the server can say "Notifications may not be showing" per device.

**Non-goals.**
- Phase 2 (ntfy, Matrix, a second channel after ~2 min without a receipt). Phase 1 leaves it ready:
  `push_deliveries.channel` (`webpush` only now; an ntfy or Matrix delivery is another row), every delivery's
  `accepted_at`/`receipt_at` ("no receipt within 2 min" is one query), and `Kind::urgent()` (§2.2).
- A new blocked app, an extra-time request (reserved kinds); wrong PIN tries as events; message content; `Topic`
  coalescing; a service-worker cache; per-parent read state (one admin account, `security::bootstrap_admin`).

## 1. Decisions

1. **The events are rows on the server** (`parent_events`, §3), and the inbox is complete: every kind is stored.
   The switches govern push only (orchestrator's call, decided 2026-10-18).
2. **One raise function**: `parent_events::raise(conn, NewEvent) -> Option<i64>`, `INSERT ... ON CONFLICT DO
   NOTHING RETURNING id` on `UNIQUE (device_id, kind, dedup_key)`. A repeated report, a retried POST or two parallel
   ones give one row. Only a new row queues deliveries (for a kind whose push is on, §4.6); the sender is woken
   (`AppState.push.wake()`, a `tokio::sync::Notify`) after the caller's transaction commits.
3. **Web Push in-house on `ring`** (§4.2); **an endpoint allow-list** (§4.5); **one declarative payload with
   `mutable: true`** (§5.3); **a per-delivery receipt token**, not the cookie (§7); **health derived when read**
   from the delivery rows (§6).
4. **Only 404/410 turn a subscription off.** A 401/403 is the server's signature (usually the Pi's clock): a fresh
   JWT, one retry, then `failed` and a server-level warning (orchestrator's call, H1).
5. **"Sign Out Everywhere" and `reset-2fa` delete every push subscription** (a lost phone stops getting titles); a
   password change doesn't. Backups never carry subscriptions.

## 2. Events

### 2.1 Where each is raised (one place per kind)

| kind | raised in | dedup_key |
|---|---|---|
| `emergency_call` | `device_api::status`, after the `device_status` insert: `call_state.last_emergency_call_at` (parsed as `calls.rs` does) is newer than the previous report's for this phone; with no previous report, only when at most 24 h old by the server's clock | the instant, RFC 3339 with ms, UTC |
| `pin_lockout` | `handlers::parent_pin::pin_events`, in the transaction that writes `parent_pin_lockout`, when the `INSERT OR IGNORE` stored the row (`rows_affected() == 1`) | the phone's `event_id` |
| `pin_used` | the same place, for a newly stored `unlocked` | the phone's `event_id` |
| `foreign_access` | `handlers::window_relock::log_foreign_access`, when the set changed and the new one isn't empty | `<previous device_status id or 0>:<hex(sha256(new sanitized json))[..16]>` |
| `unseen_boot` | `device_api::status`, where `boot_witness::log_line` returns a line (the count rose) | `<count>:<last_at_ms>` |
| `low_battery` | `device_api::status`, when the report's `battery` latches the phone (§2.5) | the latch instant (`devices.battery_low_since`), RFC 3339 with ms, UTC |
| `app_blocked`, `extra_time_request` | reserved, not raised in phase 1 | - |

- **Latency.** An emergency call and a PIN event reach the server only with the phone's next status report /
  `pin-events` POST: today the 30-min backstop (up to ~52 min with inexact alarms) or another trigger. The phone
  reports only its last emergency call, so two calls between reports make one event. Step 3 (§9) makes the call's
  and a low battery's report immediate; the Notify Me About footer says "as soon as the phone next reports".
- The previous-report reads (`last_emergency_call_at`, the previous row's id for foreign access) join the queries
  the intake already runs before its insert. An upgrade announces no old call: the previous row has the same
  instant. No phone-vs-server clock comparison except the first-report case.
- `happened_at` of a PIN event is the phone's `at_ms` unless `clock_off`, then the arrival.
- `log_foreign_access` and the status intake take `&AppState` (they need `push.wake()`), not only the pool.

### 2.2 Words and targets

Made once, at raise, from typed values (never phone-supplied text in a title), times local via
`display_time::Zone`. Stored, so the push and the inbox say the same; a renamed phone keeps old rows' words.
`target` must start with `/` and not `//` (checked at raise, and again in the worker).

| kind | title (<= 60 chars) | summary (<= 100) | target | urgent |
|---|---|---|---|---|
| `emergency_call` | "Anna called an emergency number" | "At 14:02" | `/devices/{id}/calls` (the group the finding renders in) | yes |
| `pin_lockout` | "Parent PIN locked on Anna" | "Too many wrong tries · until 14:30" | `/devices/{id}/lock#parent-pin` | yes |
| `pin_used` | "Parent PIN used on Anna" | "Time lock · 14:02" | `/devices/{id}/lock#parent-pin` | no |
| `foreign_access` | "An app may read the PIN on Anna" | "1 app with device admin" | `/devices/{id}#foreign-access` | yes |
| `unseen_boot` | "Anna started without Vibb One" | "Safe mode or a start cut short" | `/devices/{id}/about#diagnostics` | no |
| `low_battery` | "Battery low on Anna (14 %)" | "Not charging · 14:02" | `/devices/{id}` | no |

`detail` (optional, inbox only, never pushed): e.g. the foreign apps by the phone's name for them. Typed as
`parent_events::Kind` with `title()`, `target()`, `urgent()`, `ttl()`, `urgency()`; this table is
`parent_events::tests`' table.

### 2.3 (void)

The connection check was dropped on 2026-10-18, with its kind and its setting; the number stays because §11 and the
QA cite it. The Devices page's connection state is unchanged; nothing is notified on it.

### 2.4 Switches and retention

- `notification_settings` (singleton `id = 1`): `push_pin_used`, `push_pin_lockout`, `push_foreign_access`,
  `push_unseen_boot`, `push_low_battery` (0/1, default 1). No column for the emergency call (always pushed).
  Footer: "Off: still listed here, no notification."
- `retention::prune`: `parent_events` after 90 days by `created_at`; `push_deliveries` after 30 days;
  `push_subscriptions` in state `gone` after 30 days. Deleting a phone cascades its events.

### 2.5 The battery (`low_battery`)

- **Wire** (step 1's commit, both directories): the status report gains `battery: {"level": 0-100, "charging": bool,
  "low": bool}`, optional. Kotlin `BatteryReport`, `battery: BatteryReport? = null` in `StatusReportRequest`:
  `ServerJson` leaves it out while null, so `time_status_report.json` (both copies) and `TimeStateZoneTest` stay as
  they are. Rust `Option<BatteryReport>` with `level: i64`; a level outside 0..=100 drops the `battery`, never the
  report. The server keys off the field, not the capability; the launcher lists `battery_v1` in
  `STATUS_CAPABILITIES` (step 3) like its other features.
- **The reading** (launcher, step 3), at every report, from the sticky `ACTION_BATTERY_CHANGED`
  (`registerReceiver(null, filter)`): `level` = `EXTRA_LEVEL * 100 / EXTRA_SCALE`, rounded; `charging` =
  `EXTRA_PLUGGED != 0` or `EXTRA_STATUS` is `CHARGING`/`FULL`; `low` = `EXTRA_BATTERY_LOW` (API 28; minSdk 34). A
  pure `BatteryReport.of(level, scale, status, plugged, low)`, null when the level or scale is missing (-1, scale
  <= 0).
- **The trigger** (launcher, step 3): `Application.initRest`, where the process keeps its long-lived receivers and
  its package receiver already calls `SyncRunner.request`, registers one receiver (`RECEIVER_NOT_EXPORTED`; system
  broadcasts reach it) for `ACTION_BATTERY_LOW` and `ACTION_BATTERY_OKAY` (neither reaches a manifest receiver since
  Android 8) that calls `SyncRunner.request(app, "battery")`: the report with `low=true` arrives within seconds. The
  process is long-lived (home app, the anchor foreground service). When it isn't running (killed, or before the
  first unlock: `initRest` runs after it) the broadcast is lost and the next report (the 30-min backstop, a nudge,
  process start) carries `low=true` from the sticky intent.
- **The rule** (server, pure, tested): `parent_events::battery_edge(latched, &BatteryReport) -> Edge`: not latched
  and `low` → `Raise`; latched and `!low && !charging` → `Rearm`; else `None`. In `device_api::status`, in the
  intake's transaction: `Raise` is `UPDATE devices SET battery_low_since = <now> WHERE id = ? AND battery_low_since
  IS NULL` and raises only when that changed a row (two parallel reports latch once); `Rearm` sets it NULL. A report
  without `battery` (an old launcher) touches nothing.
- Why `!charging` too: Android clears `low` the moment the phone is plugged in and sets it again (with another
  `BATTERY_LOW`) when it is unplugged below the warning level; re-arming on `!low` alone would push at every plug and
  unplug at 12 %. Re-armed once the phone runs on battery above Android's close level (`BATTERY_OKAY`, ~20 %); the
  next low is a new event.
- **Shown**: `device_status` gains `battery_level`, `battery_charging` (NULL without `battery`), read from the newest
  row like the ringer mode: one line "Battery 14 %, charging" in the Devices row's subtitle and the device page's
  status group; nothing when NULL.
- **Versions**: a new server with an old launcher (or one before step 3): no line, no event. A new launcher with an
  old server: the field is ignored (serde, no `deny_unknown_fields`).

## 3. Data model: one migration, `0078_parent_events.sql`, in step 1

All of design 35 in one file (orchestrator's call, M1; decided 2026-10-18): empty push tables in step 1 cost
nothing and step 2 adds no migration. Design 36 takes `0079`. The migrations-in-order test gains 0078.
- `parent_events` - `id`, `device_id` (NOT NULL, FK `devices` ON DELETE CASCADE), `kind`, `dedup_key`, `title`,
  `summary`, `target`, `detail` (NULL), `happened_at` (RFC 3339), `created_at` (`datetime('now')`), `read_at`
  (NULL); `UNIQUE (device_id, kind, dedup_key)`; index `(created_at DESC)`; partial index `ON parent_events(id)
  WHERE read_at IS NULL` (the bell's count).
- `notification_settings` - as §2.4, its row inserted by the migration.
- `devices.battery_low_since` (TEXT, NULL = armed); `device_status.battery_level`, `battery_charging` (INTEGER,
  NULL) - §2.5.
- `push_subscriptions` - `id`, `admin_id` (FK `admin_users` ON DELETE CASCADE), `endpoint` (UNIQUE, <= 1024),
  `p256dh` (BLOB, 65), `auth` (BLOB, 16), `origin` (from `Origin` at subscribe), `label` ("iPhone · Safari", from
  the User-Agent at subscribe; the raw UA is never stored), `vapid_fp`, `created_at`, `last_receipt_at` (survives
  delivery pruning), `state` (`active` | `gone`), `gone_at`.
- `push_deliveries` - `id`, `channel` (`webpush`), `subscription_id` (FK, CASCADE), `event_id` (NULL for a test or
  summary; FK, SET NULL), `kind` (`event` | `summary` | `test`), `token_hash` (SHA-256 of the receipt token),
  `status` (`queued` | `accepted` | `failed` | `dropped`), `attempts`, `next_try_at`, `http_status`, `reason`,
  `created_at`, `accepted_at`, `receipt_at` (arrival), `clicked_at`; index `(subscription_id, accepted_at)`, index
  `(event_id)`.

## 4. Web Push on the server

### 4.1 The VAPID key

`push::load_key(file)` in `main`, next to `music_secret::load`, the same semantics (orchestrator's call, M3): file
`data/keys/vapid-p256.pk8` (PKCS#8 DER from `EcdsaKeyPair::generate_pkcs8`); a missing or empty file is created
through `.tmp` and a rename, 0600, in the 0700 directory; a non-empty file that doesn't parse is refused, never
replaced - push stays off and the log line names the file. `music_secret`'s directory/tmp handling moves to a shared
`keys.rs` taking bytes. `AppState.push_key: Option<Arc<VapidKey>>`. `vapid_fp` = first 16 hex of SHA-256 of the
public key. Right after the load, every `active` subscription with another `vapid_fp` becomes `gone` (a lost key,
e.g. a restore onto a new machine: backups never copy `data/keys/`).

### 4.2 Crypto: in-house on `ring`, not the `web-push` crate

- `ring` 0.17.14 is already compiled in through `rustls`'s `ring` feature. Step 2 adds the direct line
  `ring = { version = "0.17", default-features = false, features = ["alloc"] }`; `Cargo.lock` and the
  verification metadata don't change. It has every piece: P-256 ECDH (`agreement::ECDH_P256`, ephemeral keys -
  RFC 8291's per-message sender key), ES256 with the fixed r||s JWS needs (`ECDSA_P256_SHA256_FIXED_SIGNING`),
  HKDF-SHA256 (`hkdf`, `Len(usize)` as `KeyType`), AES-128-GCM (`aead::AES_128_GCM`). All of `webpush.rs` uses
  `ring`, not the tree's `aes-gcm`/`hkdf`.
- The `web-push` crate brings `ece` (OpenSSL by default, absent from the distroless image), a JWT crate and its own
  HTTP features: a new subtree to review and pin, for ~300 lines checked against a published vector. No.
- `src/webpush.rs`, pure (keys passed in, no I/O): `encrypt(rng, ua_public, auth, plaintext) -> Vec<u8>` (RFC 8291
  §3.4 with `"WebPush: info\0" || ua_public || as_public`; RFC 8188 `aes128gcm`, one record: salt (16) || rs = 4096
  (u32 BE) || idlen 65 || as_public, then the ciphertext of `plaintext || 0x02`), `vapid_jwt(key, aud, sub, now)`,
  `aud_of(endpoint)`, `allowed(endpoint)`. `encrypt`'s doc comment pins the draw order: the ephemeral key's 32 bytes
  first, then the 16-byte salt.
- **Tests and the deprecated helper.** `ring`'s `SecureRandom` is sealed, and `ring` has no ECDH with an imported
  static key. Appendix A's fixed keys therefore go through `ring::test::rand::FixedSliceSequenceRandom` (public,
  `#[doc(hidden)]`, deprecated "internal API that will be removed"; its `Drop` asserts each slice is drawn once).
  One `#[cfg(test)]` module in `webpush.rs` holds `fixed_rng(&[&[u8]])` and `fake_browser_decrypt` (a fresh
  `EphemeralPrivateKey` over the fixed `ua_private` per decryption) - the only `#[allow(deprecated)]`. If a `ring`
  bump removes the type, only tests break and they move to `p256` as a dev-dependency (test-only supply chain).
- Asserted from Appendix A: the ECDH secret, IKM, CEK, NONCE, the header, and the whole body end to end. PRK_key
  and PRK are opaque `hkdf::Prk`s and aren't asserted.

### 4.3 The request (Apple's rules, which every service accepts)

- `push::Transport` trait (`send(PushRequest) -> PushAnswer`: headers and body in, status and headers out) in
  `AppState`, as `music_net::Source`. The real one: one `reqwest::Client`, HTTP/1.1 (built without `http2`; Apple
  accepts it), TLS with SNI and ALPN by rustls, `redirect::Policy::none()`, connect 5 s, total 15 s, at most 4 in
  flight (far under Apple's 100 unacknowledged).
- `POST <endpoint>`, `Content-Encoding: aes128gcm`, `Content-Type: application/octet-stream`, `TTL`, `Urgency`
  (reqwest's `gzip` adds `Accept-Encoding`: tests don't pin the exact header set).
- `Authorization: vapid t=<jwt>, k=<public key, base64url, 65 bytes>`. JWT `{"typ":"JWT","alg":"ES256"}`, claims
  `aud` = the endpoint's `https://host` (the allow-list admits port 443 only), `exp` = `iat` + 12 h, `sub` =
  `VAPID_SUBJECT` from `.env` when set (`mailto:` or `https:`), else the subscription's `origin`. Cached per
  `(aud, sub)`; re-minted when `now` is outside `[iat, iat + 1 h]` (a clock jump re-mints, never keeps a stale one)
  or after a 401/403 (§4.4). Never minted more often than that.
- TTL / Urgency: `emergency_call` 86400 / high; `pin_lockout`, `foreign_access` 43200 / high; `pin_used`,
  `unseen_boot`, summary 43200 / normal; `low_battery` 3600 / normal (an hour later the phone is charging or flat:
  a late push says nothing the device page doesn't); test 600 / high. No `Topic` (orchestrator's call, M4.6,
  decided 2026-10-18): coalescing would drop pushes that then never get a receipt.
- Plaintext capped at 3072 bytes (Apple's 4 KB is the encrypted body); `title` cut at 60, `body` at 100 chars on a
  char boundary. Over the cap is a bug: the delivery fails with `reason = too_large`.

### 4.4 Responses

| answer | action |
|---|---|
| 2xx | `accepted`, `accepted_at` |
| 404, 410 | `dropped`; subscription `gone`; security event `push_subscription_gone` |
| 401, 403 | drop the cached JWT for that `(aud, sub)`, retry once at once with a fresh one; a second 401/403 → `failed`, the reason kept |
| 429, 500, 502, 503, 504, a network error | retry: 1 min, 5 min, 30 min, 2 h (`Retry-After` honoured up to 2 h), then `failed`; never after the TTL ran out |
| other 4xx | `failed` |

Every non-2xx logs the status, the JSON `reason`, `apns-id` and the endpoint's host - never the path, a key, the
auth secret, the JWT or a token. **Server-level warning**: while a delivery `failed` on 401/403 in the last 24 h
and none was accepted since, `/notifications` shows one red row "The push service refused the server's signature -
check the server's clock" (the Pi has no RTC; fake-hwclock or 1970 until NTP syncs).

### 4.5 Endpoints: an allow-list

`webpush::allowed(endpoint)`: `https`, no userinfo, port 443 or none, a host name (no IP literal) that is
`fcm.googleapis.com`, `updates.push.services.mozilla.com`, or ends with `.push.apple.com` or `.notify.windows.com`
(a label boundary: `push.apple.com.example.net` fails). Checked at subscribe ("This browser's push service isn't
supported") and again before every send, in every build (tests use the `Transport` seam, not a local server).
Why: a stolen session or an XSS could otherwise aim the server at the LAN. The four cover Chrome, Edge, Samsung
Internet, Brave, Opera, Firefox and Safari. Redirects are never followed.

### 4.6 Queueing, bursts, the sender

- `raise` (same transaction) inserts one `queued` delivery per `active` subscription, when the kind's push is on.
- `push::run_sender` (spawned from `main` only) waits on `wake()` or the earliest `next_try_at` and calls
  `push::send_due(state, now)`, which tests drive directly.
- **A cap per pass**: when `send_due` finds more than 3 due event deliveries for one subscription, it sends the
  newest 2 and one `summary` ("5 new notifications", target `/notifications`); the rest become `dropped`, `reason =
  folded`. An `emergency_call` always goes on its own. The bursts that matter come from one intake (a phone back
  after a week posting 20 PIN events); the dedup keys stop repeats.

## 5. The PWA

### 5.1 The bell and the inbox

- `partials/bell.html` in the `.title-bar` of the five tab pages (`devices_list.html`, `apps_list.html`,
  `music.html`, and `device_locate.html` (`/devices/locate`) and `settings_hub.html`, which gain the bar). On the
  right, before the page's own action ("+", Edit). A link to `/notifications?from=<tab>`,
  `aria-label="Notifications, 3 unread"`, a red count pill when > 0 ("99+"), `data-unread` for the app badge.
  `Bell::load(&db)` is one partial-index `COUNT`, called by seven handlers (five tabs, the Settings hub row,
  `/notifications`); no cache.
- `/notifications` (`handlers::notifications::page`, `notifications.html`): back link to the tab it came from
  (`from`, one of the five, else Devices), large title "Notifications", the tab bar with no tab current. Groups:
  1. **Warnings** (only when one applies): the server-signature row (§4.4); an orange row per subscription that may
     not be showing (§6), to `#devices`.
  2. **This Device** (`#this-device`, §5.2).
  3. **Inbox** (`#inbox`): a picker "All phones / Anna / ..." (`?device=`, a GET filter, `<noscript>` Show), the
     newest 100 events, newest first, "Show older" (`?before=<id>`). A row: the phone's avatar, the title (bold
     with a blue dot while unread), the summary and relative time (exact in `title=`), a delivery line (below), a
     chevron. Each row is a `<form method="post" action="/notifications/{id}/open">` with the row as its button: it
     marks the event read and 303s to its target (no GET changes state; `leaves_the_page` gets
     `["notifications", _, "open"]`). Footer action "Mark All as Read" (`POST /notifications/read-all`, back to
     `/notifications#inbox`, honours `device`). Empty: "Nothing yet. Events are kept 90 days."
  4. **Getting Notifications** (`#devices`): one row per subscription - label, a badge (On / May not be showing /
     Turned off), "Last shown 2 h ago"; an ⓘ with what to check (§6); Send a Test
     (`POST /notifications/subscriptions/{id}/test`) and Remove… (a `<details>` confirm,
     `POST /notifications/subscriptions/{id}/delete`), both back to `/notifications?notice=…#devices`.
  5. **Notify Me About** (`#events`): one form, the handler writes every field (`POST /notifications/settings`,
     back to `#events`): "Emergency call" (checked, disabled, "Always on"), "Parent PIN used", "Parent PIN locked",
     "An app may read the PIN", "Start without Vibb One", "Battery low"; the footer of §2.4 and "as soon as the phone
     next reports".
- **The delivery line** (step 2, M6), from one grouped query over the page's events' deliveries by `event_id`:
  "Pushed to 2 devices · shown on 1" (accepted / with `receipt_at`; one device: "Shown on iPhone · Safari" or
  "Pushed to iPhone · Safari, not confirmed"), "Not sent" (all failed), "In a summary" (folded), nothing when no
  push was made.
- Settings hub: a row "Notifications · 3 unread". Every form carries its group's fragment in its action and its
  redirect (`check_forms`, `assert_lands`); scroll-restore keeps the parent's place.

### 5.2 This Device: subscribe from a tap

- `static/notifications.js` (deferred, no inline JS). At load it takes `navigator.serviceWorker.ready` into a
  variable; Turn On stays disabled until it resolved. States, checked in this order (H2), each a row value and one
  action row:
  1. `navigator.standalone === false` (exists only on iOS/iPadOS WebKit): "Add Vibb Phone to the Home Screen
     first", with the steps (Share › Add to Home Screen, then open it from there);
  2. no `PushManager` or not a secure context: "Not available in this browser";
  3. `Notification.permission === "denied"`: "Blocked - allow notifications for Vibb Phone in Settings";
  4. subscribed here and known to the server: "On" + "Turn Off on This Device";
  5. else "Off" + "Turn On Notifications".
  Without JS the footer says notifications need JavaScript.
- **Turn On**: the click handler's first call is `reg.pushManager.subscribe({userVisibleOnly: true,
  applicationServerKey})`, synchronously (Apple: "at once" in the tap's handler); `subscribe` asks for the
  permission itself, so no separate `requestPermission()`. `applicationServerKey` is a `Uint8Array` decoded from
  the form's `data-key` (older Safari takes no string). Then it fills the hidden `endpoint`, `p256dh`, `auth` and
  submits `POST /notifications/subscriptions` (303 to `/notifications?notice=push_on#this-device`).
- "Known to the server": the page renders `data-subscribed` = 16-hex SHA-256 prefixes of the admin's active
  endpoints; the script hashes its own (`crypto.subtle`). A local subscription the server doesn't know shows "Off";
  Turn On posts it again (upsert on `endpoint`, state reset).
- **Turn Off**: `subscription.unsubscribe()`, then `POST /notifications/subscriptions/unsubscribe` with the
  endpoint.
- The subscribe handler: full auth; `endpoint` allowed (§4.5) and <= 1024; `p256dh` base64url of 65 bytes, a valid
  point (one throwaway `ring` agreement); `auth` 16 bytes; at most 10 active per admin; `origin` from the `Origin`
  header, which must be present ("This browser didn't say where the page came from" otherwise: `csrf_check` passes
  `Sec-Fetch-Site: same-origin` without it); security event `push_subscribed` with the label. A refusal flashes in
  `#this-device`.

### 5.3 The service worker (`templates/sw.js`)

The payload (plaintext JSON, every browser):

```json
{"web_push": 8030, "mutable": true,
 "notification": {"title": "Anna called an emergency number", "body": "At 14:02", "lang": "en",
   "navigate": "https://kidphone.example.ts.net/devices/3/calls", "tag": "e812",
   "data": {"d": 4411, "t": "<receipt token, 22 chars base64url>", "b": 3}}}
```

- `navigate` is absolute (the subscription's `origin` + target; declarative push requires it). `tag` is `e<event
  id>`, a summary's `s<delivery id>`, a test's `t<delivery id>` - unique, so nothing silently replaces another.
  `b` is the unread count. No top-level `app_badge` until the device check shows where Safari wants it (L1).
- Chrome and Firefox read it as plain data (`event.data.json()`). A Safari with declarative push dispatches `push`
  to our worker first (`mutable: true`) and shows the declarative notification only when the worker shows nothing:
  never a double, as long as the worker always calls `showNotification` inside `waitUntil`. A declarative fallback
  display leaves no receipt - an honest "not confirmed". Field placement is checked on iOS 18.4 in step 2.
- `push`: inside `event.waitUntil`: parse; on any failure show "Vibb Phone" / "Something new - open Notifications"
  with target `/notifications` (never an invisible push). `showNotification(title, {body, tag, lang, data:
  {navigate, d, t}, icon: "/static/icons/icon-192.png"})`, then `self.navigator.setAppBadge(b)` where it exists,
  then - after `showNotification` resolved - `fetch("/notifications/receipt", {method: "POST", credentials: "omit",
  headers: {"Content-Type": "application/json"}, body: {"d", "t", "a": "shown"}})`. A failed receipt is not kept.
- `notificationclick`: close it; post `"a": "clicked"` (marks the event read); only a same-origin `navigate` whose
  path starts with `/` and not `//` (else `/notifications`); focus an open client and navigate it, else
  `clients.openWindow`.
- No `fetch` handler, still; no CSP change. `static/register-sw.js` also sets the app badge on load from the
  bell's `data-unread` (`setAppBadge(n)` / `clearAppBadge()` where supported).

## 6. Delivery health

- `push::health(sub, deliveries, now)`, typed, from `push_deliveries` (index `(subscription_id, accepted_at)`):
  the unconfirmed = `status = 'accepted' AND receipt_at IS NULL AND accepted_at > COALESCE(sub.last_receipt_at,
  '')`. `Gone` | `MayNotShow` when 3 or more unconfirmed, or the oldest is older than 24 h (the user's rule) | `Ok`
  | `NeverSent`. A receipt for any delivery sets `last_receipt_at`, which clears it.
- `MayNotShow`: the warning row on `/notifications`, the subscription's badge, and a short orange row at the top of
  the Devices list (as the sign-in warnings, `notifications::warning_row`) to `/notifications#devices`. Its ⓘ, in
  this order: Is Tailscale on on that device (the receipt needs the tailnet)? Added to the Home Screen and opened
  from there? Notifications allowed for Vibb Phone in Settings? A Focus mode on? Then Send a Test.
- `Gone`: "Turned off on that device - turn it on again there"; no warning row; pruned after 30 days.
- Send a Test: a visible push ("Test from Vibb Phone" / "If you see this, notifications work on <label>"), one per
  subscription per 30 s; it counts like any other.

## 7. Security

- **Admin gate and CSRF** on every route; `/notifications/*` are under `require_full_auth`, except the receipt.
- **The receipt** (`POST /notifications/receipt`) sits in `public_routes`, with its own `DefaultBodyLimit` of 1 KiB:
  the session may have expired (30 days idle) while the subscription lives. A random 128-bit token per delivery, in
  the encrypted payload, stored as SHA-256; `{d, t, a}` compared in constant time; 204 on a match, 404 otherwise; it
  changes only that delivery's `receipt_at`/`clicked_at`, its subscription's `last_receipt_at` and (clicked) the
  event's `read_at`. Repeating it is harmless. The admin gate still applies (tailnet/loopback), `csrf_guard` passes
  the worker's `Sec-Fetch-Site: same-origin`; a 404 counts against `limits::ip_failed`/`ip_blocked`.
- **Subscriptions** belong to the admin; Sign Out Everywhere and `reset-2fa` delete them; deleting the admin
  cascades. `backups::strip_secrets_in` deletes `push_deliveries` then `push_subscriptions` from the zip's copy
  (the `auth` secrets; a restore would make them `gone` anyway).
- **Nothing secret in pages or logs**: pages carry the VAPID public key and endpoint hash prefixes only - never an
  endpoint, `p256dh`, `auth`, token or the private key (a test greps the page). The key file 0600, never backed up.
- **What a push service sees**: the endpoint, size, TTL, Urgency; the payload is RFC 8291 ciphertext. A stolen
  subscription (endpoint + `p256dh` + `auth`) decrypts nothing without the browser's private key, and pushing to it
  needs our VAPID key. A stolen *subscribed device* gets titles until Remove or Sign Out Everywhere. Titles name a
  phone and a kind, never content; `detail` never leaves the server.
- **SSRF**: the allow-list (§4.5), no redirects, no IP literals. **Open redirect**: targets refuse `//` (§2.2).

## 8. Tests (required)

- `webpush::tests`: Appendix A (ECDH secret, IKM, CEK, NONCE, header, the whole body); the 0x02 delimiter; the
  3072-byte cap; the JWT's header, `aud` per service, `exp`, the `sub` fallback, the signature verified with
  `ring`; re-mint outside `[iat, iat + 1 h]` and per `(aud, sub)`; `allowed` - each service,
  `push.apple.com.example.net`, `http:`, an IP, a port, userinfo.
- `parent_events::tests`: the words/targets table; `battery_edge` (latched or not × low × charging).
- `src/tests/notifications.rs` (TestApp with a scripted `Transport` that records requests, decrypts with
  `fake_browser_decrypt` and answers from a script):
  - each kind from its real input (a status report, `pin-events`), posted twice → one event; an emergency call
    already in the previous report → none; a first report with a 2-day-old call → none;
  - the battery: `low` → one event; then `low` + charging, then unplugged at 12 % (`low`) → still one; `!low &&
    !charging`, then `low` → a second; a report without `battery` → nothing, no line; level 150 → the battery
    dropped, the report stored; the line on the Devices row and the device page;
  - a switched-off kind → stored, no delivery; the emergency call can't be switched off;
  - the bell's count on all five tab pages; `/notifications` per phone, read/unread, `open` marks read and 303s,
    Mark All as Read, the delivery line's states; `check_forms("/notifications")`, `assert_lands` per redirect;
  - subscribe: refusals (host, key, 11th, no `Origin`), upsert; unsubscribe; Remove; Sign Out Everywhere and
    `reset-2fa` clear them; a backup zip holds none;
  - send: headers (`low_battery`'s TTL 3600), the decrypted payload's fields, no `detail`; 2xx; 410 → gone; 403
    `BadJwtToken` → still `active`, the retry carries a new JWT, the clock warning; 429 with `Retry-After` →
    retried; 20 `pin_used` → 2 pushes and one summary with a unique tag; an emergency call alone;
  - the receipt: wrong token 404 and nothing changed, a repeat 204, cross-site `Sec-Fetch-Site` 403, no session
    needed, over 1 KiB 413, `clicked` marks read; health: 3 unconfirmed → the warnings, a receipt clears them, a
    day-old one; a changed VAPID key at startup → gone; a damaged key file → push off, the file untouched;
  - no endpoint, `p256dh`, `auth` or token in any page; the key file 0600; a target `//x` refused; retention.
- `jstest/sw.test.js` (templates/sw.js with `{{ app_version }}` replaced, a fake `self`): `push` always shows (bad
  JSON, no data), the receipt only after `showNotification` resolves, the badge; `notificationclick` focuses or
  opens a same-origin target, refuses another origin and `//`, posts `clicked`; no `fetch` listener.
  `jstest/notifications.test.js`: the state order (an iOS-like fake without `PushManager`, `standalone: false` →
  the Home Screen steps), `subscribe` is the click's first call with a `Uint8Array`, success fills and submits, the
  hash comparison.
- Launcher (step 3): `BatteryReport.of` (rounding, unreadable → null, plugged or `FULL` → charging); a report with a
  battery encodes `battery.level`/`charging`/`low`, one without stays `time_status_report.json`; `battery_v1` in
  `STATUS_CAPABILITIES` (`SyncScheduleTest`); the emergency-call record requests a sync.

## 9. Steps

1. **Events and the inbox** (server, `0078_parent_events.sql` with all four tables and the battery columns): §2,
   §3, §5.1 without This Device, Getting Notifications and the delivery line; the bell on the five tab pages, the
   Settings hub row, retention. The `battery` field on both sides in this commit (Rust and Kotlin `BatteryReport`,
   the Kotlin one never set yet; root CLAUDE.md: an API change goes into both directories at once). Shippable alone:
   the inbox works with no push.
2. **Web Push** (server, no migration): the `ring` line, `keys.rs`, `webpush.rs`, `push.rs`, §4, §5.2, §5.3, §6,
   §7, the delivery line; register-sw.js; DEPLOY.md; `server/CLAUDE.md`. Device checks before done: iPhone on iOS
   18.4+ and 16.4-18.3 if at hand (Safari tab → the Home Screen steps; the prompt from `subscribe` alone; each
   kind; the receipt; Focus on; the worker failing → Safari's declarative fallback; where `app_badge` goes), macOS
   Safari, Android Chrome, desktop Firefox; a 410 by removing the app from the Home Screen.
3. **Launcher** (decided 2026-10-18): the battery reading in `MdmSyncWorker`'s status report, `battery_v1`, the
   receiver in `Application.initRest` (§2.5); and right after an emergency call connects (`KidInCallService` →
   `CallPrefs.recordEmergencyConnected`), `SyncRunner.request(context, "emergency_call")`, so the report with
   `last_emergency_call_at` arrives within seconds. No API change beyond step 1's. Emulator check
   (`docs/testing/emulator.md`): console `power ac off`, `power capacity 10` → the push within seconds; `power ac
   on`, `power ac off` → no second one; `power capacity 30`, then 10 → a second.

`server/DEPLOY.md` ("Notifications"): outbound HTTPS (443) to `*.push.apple.com`, `fcm.googleapis.com`,
`*.notify.windows.com`, `updates.push.services.mozilla.com`, from the Pi's firewall and the Docker network; the
clock must be right (NTP) or the push services refuse the signature; `VAPID_SUBJECT` optional; the VAPID key in
`data/keys/` isn't backed up - after a restore onto a new machine, turn notifications on again on each device; the
parent's device needs Tailscale on for receipts.

## 10. Decisions 2026-10-18 (the owner)

1. Step 3 is in, right after step 1: the sync after an emergency call and the battery receiver.
2. Every kind is stored; the switches govern push only.
3. "Parent PIN used" is on by default.
4. No event for a silent phone (the former question 4 is void, §2.3); `low_battery` instead (§2.5), on by default.
5. All of 35 in `0078`, design 36 in `0079`.
6. No `Topic` coalescing in phase 1.

## 11. Design QA (2026-10-18, `docs/design/qa-35-design.md`)

Every finding taken (none "not taken"); the orchestrator's calls are marked in the text. H1 Decision 4, §4.3-4.4;
H2 §5.2; M1 §3; M2 §4.2; M3 §4.1; M4 (no IndexedDB queue, derived counters, a cap per pass, the per-IP limiter, an
`Instant` (void with §2.3), no `Topic`) §4.3, §4.6, §6, §7; M5 §2.1, step 3; M6 §5.1. L1 §5.3; L2 §2.1; L3 §2.3
(void since 2026-10-18); L4 §4.3; L5 §2.2, §5.2; L6 §7; L7 §4.3, §8; L8 §1 (changed by the orchestrator: push only); L9 §6, DEPLOY.md; N1-N5 in place.

## Step 1 implementation status (2026-10-18, branch `notif-s1`)

Built as §9 step 1: `0078_parent_events.sql` (all four tables, the switches' row, the battery columns),
`src/parent_events.rs` (`Kind`, `Phone` - the words -, `NewEvent`, `raise`, `new_emergency_call`, `battery_edge`,
`battery_report`, `battery_line`, `Bell`), every kind raised from its real input, the battery wire on both sides
(Rust `Option<BatteryReport>`, Kotlin `BatteryReport` never set yet - `TimeStateZoneTest` and both copies of
`time_status_report.json` unchanged), the battery line on the Devices row and the summary's header, the bell on the
five tab pages, `/notifications` (Inbox, Notify Me About), the Settings hub row, retention. No Web Push, no launcher
behaviour.

Where the code differs from or fills in the text above:
- `Kind` has no `urgent()`, `ttl()`, `urgency()` and no variants for the reserved kinds yet: nothing in step 1 reads
  them (no dead code); they come with step 2's sender. `parent_events::tests`' table pins title, summary and target.
- The status intake has no transaction of its own (it never had): the emergency call, the start without Vibb One and
  the foreign app are each one `raise` on a pool connection (the dedup key makes repeats and parallel reports one
  row); the battery's latch and its event share one transaction (`battery_report`). `raise` takes a
  `&mut SqliteConnection`, so step 2 adds the delivery rows inside it. `pin_events` raises in its own transaction.
- `battery` is read leniently: anything that isn't an object with a numeric level in 0..=100 and two booleans drops
  the reading (logged), never the report.
- Words not in §2.2's table: an emergency call on another day "Yesterday 21:40" / "12 Oct 08:12"; any other time in a
  summary "14:02" today, "yesterday 21:40", else "12 Oct 08:12"; a lockout whose end has passed "Too many wrong
  tries · <when it happened>"; foreign apps "N apps with accessibility on" / "with device admin or accessibility";
  the use's place "Time lock", "Lock screen", "Vibb One's Settings", "Pause", "Play install mode". A long phone name
  is cut with "…" so the title stays within 60 characters with the rest of its words.
- The foreign apps' detail names them by the same report's app list (else the package).
- The phone picker shows only with two or more phones; Mark All as Read only while something under the filter is
  unread; the Settings hub's row says "3 unread" only above 0, and sits in its own group at the top of the hub. The
  forms carry `from` (and `device`) so every redirect lands on the same view of the page.
- Until step 2 the switches change nothing visible (every event is listed; nothing is pushed yet).

Code QA fix round (`docs/review/qa-35-s1-code.md`, 2026-10-18):
- L1: the intake reads the previous report once (`PreviousReport`: its id, apps, install mode, call state, foreign
  apps and unseen-boot count), so the foreign apps' dedup key and the "did the set change" comparison come from the same
  row and two parallel reports agree on the key (`previous_foreign_access` is gone).
- L4: the inbox is no-store (`admin_auth::no_store_path`); the tab pages keep their cache (back-forward cache and
  scroll restore), the count may lag one navigation after Back.
- The emergency call's 24-hour bound now applies whenever the previous report named no call (none, or no
  `call_state`), not only to a first report: only a report with a newer `last_emergency_call_at` than the previous
  one's raises otherwise.
- The battery line carries the report's age ("Battery 14 %, charging · 3 min ago"); an inbox row's form carries
  `from`/`device` like the others; `Zone::at` gives a time with its clock on any day (no more guessing from the text);
  `push_subscriptions.state` has its CHECK.

Open for step 2: the delivery line, This Device, Getting Notifications, the warnings, the push tables' writers,
`Kind`'s push properties, `log_foreign_access`'s and the intake's `push.wake()` after the raise.

## Step 2 implementation status (2026-10-18, branch `notif-s2`)

Built as §9 step 2, server only, no migration: the `ring` line (`Cargo.lock` gained only the dependency line),
`src/keys.rs` (the key-file helper, shared with `music_secret`), `src/webpush.rs` (VAPID JWT, RFC 8291 encryption,
the allow-list; Appendix A asserted end to end), `src/push.rs` (the key at startup, the queue, the sender, the answer
table, health, the receipt), the routes and the page (§5.1's warnings, This Device, the delivery line, Getting
Notifications), the Devices list's warning row, `templates/sw.js` (`push`, `notificationclick`),
`static/notifications.js`, `static/register-sw.js` (the badge), `strip_secrets_in`, Sign Out Everywhere and
`reset-2fa`, DEPLOY.md's "Notifications", `server/CLAUDE.md`.

Where the code differs from or fills in the text above:
- **The summary has no count**: "New notifications" / "More than 3 at once - open Notifications to see them". A
  summary that has to be retried (after a 5xx or a restart) is a delivery row with no place for its words; fixed words
  need no new column. The inbox lists every event.
- **One push at a time**, not up to 4 in flight: the emergency call first, then the `Urgency: high` ones. A dead push
  service costs at most 15 s per push; for one family's handful of devices that is simpler than a pool.
- **The receipt token is made at each send**, its hash stored before the request: a retry carries a fresh token. A
  push a service accepted although the request looked failed (a network error after the body went) loses its
  receipt and shows "not confirmed" - honest, and rare.
- **A tap** posts `clicked` (which marks the event read, §5.3) and opens the target directly; it doesn't go through
  the inbox's `POST /notifications/{id}/open` (a worker can't post a form and follow its 303 into a window).
- **Endings**: a TTL that ran out is `failed` with `reason = expired`; a delivery to a subscription that turned `gone`
  is `dropped`/`gone`; one whose event was pruned is `dropped`/`event_gone`. The delivery line adds "Sending" while
  a push waits to be retried.
- **The clock warning** looks at the failed delivery's `created_at` (there is no `failed_at`): the 401/403 retry
  happens in the same pass as the first try, so it is the failure's time give or take a retry.
- **`reset-2fa`** is the command line one (§1 decision 5, qa-35 L6); the PWA's own Reset Two-Factor keeps the subscriptions
  (the parent is signed in on the device that resets it).
- **This Device drops a local subscription the server doesn't know** at load (removed there, or made for an older
  server key after a restore): Turn On then makes a fresh one, since a `subscribe` with another key over an old
  subscription fails in the browsers.
- The subscription is the form POST of §5.2 and the public key rides in the form's `data-key`: no JSON endpoint and
  no separate public-key route.
- `raise_logged` now runs `raise` (and so the queue) in a transaction of its own, then wakes the sender.
- With no usable key file the page says notifications are off on this server (no Turn On); nothing is sent, queued
  rows age out with the 30-day retention.

Device checks owed before step 2 is done (§9):
- iPhone on iOS 18.4+: a Safari tab shows the Home Screen steps; from the Home Screen app the prompt comes from
  `subscribe` alone; each kind arrives with its words and opens its page; the receipt arrives (shown, then clicked);
  Focus on; the worker failing (e.g. a thrown error) -> Safari's declarative fallback shows once, never twice; where
  `app_badge` goes (L1).
- iPhone on 16.4-18.3 if at hand (no declarative push: the worker path only).
- macOS Safari, Android Chrome, desktop Firefox: subscribe, a push, the receipt, the click.
- A 410: remove the app from the iPhone's Home Screen, raise an event -> the device shows "Turned off".
- The clock: a Pi booted with a wrong clock shows the red row on Notifications and recovers once NTP synced.

Code QA fix round (`docs/review/qa-35-s2-code.md`, 2026-10-18):
- M1: a fourth health state, "Not getting through": the subscription's newest finished delivery (accepted or failed,
  by id) is `failed` and none was accepted after it. The badge says so, the row's line gives the reason
  (`push::failure_text`: "The push service couldn't be reached" for a network error, 429 or 5xx - also when the
  retries ran past the TTL -, "refused the server's signature" for 401/403, "refused it (400)" otherwise, "The server
  couldn't send it" for its own refusals) and "· trying again" while one waits; the same orange rows as "may not be
  showing" on Notifications ("Notifications aren't reaching …") and the Devices list. A device with a push waiting
  and none finished says "Sending…", so Send a Test's answer shows right after its redirect.
- L1: `data-subscribed` lists every admin's active endpoints (`push::active_endpoint_hashes`), so This Device never
  drops another parent's subscription on a shared device; Turn Off deletes the browser's row whichever account made it.
- L2: the fold never takes an urgent kind (`Kind::urgent`: the emergency call, a PIN lockout, a foreign app).
- L3: a `queued` delivery that already has a receipt is marked `accepted` at the next pass and never sent again; a
  receipt with an older token for a delivery sent more than once is a 404 that doesn't count against the client.
- N1: the fresh salt and key per message have their own test; N2: a retry past the TTL ends the delivery at once
  (tested with a test push and three network errors); N3: the JWT's `sub` falls back only to an `https://` origin,
  else none; N4: "In a summary" only when that summary was accepted (a folded delivery takes its summary's status).
