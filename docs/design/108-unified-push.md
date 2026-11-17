# Design 108: Vibb as the UnifiedPush distributor

Status: design, **revision 2**, 2026-11-16 (branch `design-108`, from main a4fbf3cac). **Not built.** Revision 1 was
8a95489bb. The build needs the user's own go; the user has answered Q1-Q11 (§14) and reversed the 2026-10-06 rule
(§10). No code, no migration and no contract file exist yet; every name below is a proposal.

## Amendment to revision 2 (2026-11-16, the user: Q4 changed)

**Element X keeps its Play build** (the kid's phone has Google). Its push provider is switched to Vibb One once per
session in Element X itself (Settings › Notifications › Advanced › Push provider); Google stays a manual fallback
through the same setting. So §6.6's F-Droid runbook and any "Element X F-Droid as a tracked catalog app" work are out;
§6.6, R1-E (§12.1), §3.10's sheets and warning, §14's Q4 and §15's risk 7 now say so. Everything else stands as
revision 2 has it (variant A, the switch on by default, the Matrix gateway in R1, Q8 minimised bodies). Where this
amendment and an older line below disagree, the amendment wins.

## Built: R1-C and R1-S (branch `up`, 2026-11-17)

The server half is built; the notes are `docs/notes/server/unified-push.md` and `server/src/unified_push/README.md`.
Where the build differs from the sections below:

- i18n keys `app_push.*` (the parents' web push owns `push.*`); `AppState.unified_push` holds `up_notify`, the
  coalescing, the buckets and the slots.
- Migration 0152 also has `unified_push_settings.last_discovery_at` ("last seen from outside"), `up_previous_origins`
  (every replaced origin with its own 30-day end, in place of `previous_origin`/`origin_changed_at`, R1-SR L7) and a
  trigger that tombstones every deleted registration, a cascade included.
- The 30-day-unseen prune is in R1 (its test is §12.2's); `outside_gateway_at` is recorded (R2 shows it).
- A TTL 0 push is kept 30 s while the stream is open; Matrix `prio: high` is the urgency; a Matrix body arriving at
  `/up/` is minimised too; the off switch is a confirm sheet, turning on an auto-saving switch.
- After the security review (R1-SR, §16's second table): the edge takes the client, then the body, then a slot; the
  phone's sustained rate is ntfy's one per 5 s with a "noisy app" line; a per-client request bucket; registration in
  one `BEGIN IMMEDIATE`; a per-phone hourly registration cap; the Matrix body by an allow-list (the pushkey dropped);
  every replaced origin kept its 30 days.
- Not in R1-S: `scripts/fake-homeserver.py` (R1-G), VAPID (R3), the launcher note's rule paragraph (R1-L/merge).

## Built: R1-L (branch `up`, 2026-11-17)

The launcher half is built; the note is `docs/notes/launcher/unified-push.md`. Code in `push/up/`: `UpReceiver`
(`UpRequests.read`), `UpLinkActivity`, `UpPackageReceiver`, `UpStore`, `UpRules`, `UpDelivery`, `UpEngine` (the logic,
JVM-tested with fakes), `UpRuntime` (the Android wiring: one single-thread scheduled executor that also carries the
raise's binding callbacks and timers) and `vendor/RaiseToForeground.kt` with H1's fixes. Where the build differs from
the sections below:

- **MESSAGE_ACK is taken on its token alone.** The connector sends it with a plain `sendBroadcast`, without
  `FLAG_SHARE_IDENTITY` (`MessagingReceiver.mayAcknowledgeMessage`), so §5.1's "we check anyway" can't hold: a sender
  that is present must own the token, a missing one is accepted on the token (the spec's unguessable-token way). It
  only feeds `lastAppAckMs` (written at most hourly), never the server's ack.
- **`message` is cut to 100 bytes, not refused** (§5.3's wording; an MXID may be longer and it is only shown). A
  `vapid` that isn't 87 base64url ignores the request (the spec's rule; the reference distributor treats it as absent).
- **A REGISTER without a shared identity** is noted for the diagnostics row by its `application` extra, an untrusted
  hint shown only there; it is never answered.
- **The 30-day ping and unregister (§5.6)** are R2, as §12.1 has it: the rows keep `lastAppAckMs`, `pingId` and
  `pingSentMs` for it. `up_rules.json` has no liveness rows yet.
- **The reconcile also** drops a pending row a day old (its `DELETE` queued: the server may hold it behind a lost
  answer), sends a `DELETE` for every server `reg` no local row knows, and drops an app no longer installed (the
  backstop for a missed `PACKAGE_FULLY_REMOVED`; a hidden app is still installed). Off tells every app `UNREGISTERED`
  without `DELETE`s (the server deleted its rows). A store that doesn't open changes nothing and deletes nothing.
- **A `conflict`** answer drops the pending row and tries once with a fresh `reg` and secret (a secret tombstoned
  behind a lost answer would otherwise refuse forever).
- **Every stream reopen pulls** (not only when the sync's 10-minute reopen rule syncs); every accepted fresh policy
  pulls after its reconcile. The acks of sends that happen after a pull ride a pull 6 s later.
- **The store's side keys** (`up_deletes_v1`, `up_churn_v1`, `up_notes_v1`) are plain JSON in the same CE prefs file;
  only `up_regs_v1` is sealed.
- **The Link Activity** has `configChanges` with `uiMode` (every activity does, `PageManifestTest`).
- Element X stays the Play build (the amendment): nothing in the launcher depends on the F-Droid build.
- Not in R1-L: the emulator gate (R1-G, §12.3), VAPID (R3).

**R1-L's code QA (1 H, 3 M, 6 L), fixed on `up`:**

| Finding | The fix |
|---|---|
| **H1** `goAsync` held across server calls | no `goAsync()` in either receiver: the request is read, queued, and `onReceive` returns; answers are broadcasts of their own, the anchor's FGS keeps the process. The raise runs on an executor of its own; every server call has a budget (a REGISTER one call plus a conflict retry only after a fast answer, the DELETEs 20 s / 20 a run, a pull step one call) |
| **M1** the pull spun while the raise bound, and after a failed send | `more` loops only when the step handled a new message; a failed send is reported (`onFailed`, the vendored class's change list says so) and delivered again by a later pull |
| **M2** a fault left `pullRunning` set | `pullStep` ends the pull on any exception (`UpEngineTest`) |
| **M3** a fallback or bare policy disabled the components | switched only with `parentChoice` (fresh, or a cache that decodes); the reconcile takes the fresh policy's own view, never the cache read back |
| **L1** the unverified old-connector hint | noted only for an installed package that may register, at most 3, worded "an app calling itself X ... not verified" |
| **L2** the wake lock outlived the pull | released when the pull is over and no raise is bound |
| **L3** the sealed store read for any request | a REGISTER that may not register is refused from the cached policy first; MESSAGE_ACK ignored while off and rate-limited (a token within the hour skipped, 30 reads a minute) |
| **L4** an unreadable store kept the server's rows for good | after a day's grace the blob is dropped and the reconcile runs from empty (its `DELETE`s); no new UI |
| **L5** an allowed app hidden for another reason lost its messages | a hidden app's message is dropped and acknowledged **and its registration removed** (as §2.3's "leaves the allowlist" row): holding would fill the server's 50-message batch for every other app; the app registers anew once visible and started |
| **L6** delivery to a suspended app during a time rule gives it an FGS start window | **not changed: an open question for the user.** Q5 chose "deliver" during a time rule; the raise lets the suspended app start its foreground service (it syncs in the background during bedtime). Whether that is wanted, or the raise should be skipped for a suspended app (the broadcast still delivered), is the user's call |


The sections a finding changes are rewritten in place, so where revision 1 and a section disagree, the section as it
now reads is the design. §16 maps every finding to its fix. What changed, in short:

- **The user's answers (§14):**
  - Q1 = A, the port of ntfy's logic. Variant B stays in §4 as the fallback that was not chosen.
  - Q2 = reverse the rule.
  - Q3 = the per-phone switch is **on by default**, and the switch stays.
  - Q4 = **Element X's F-Droid build**, from its GitHub releases as a catalog app
    (`app-fdroid-arm64-v8a-release-signed.apk`), so UP is chosen by itself and the Matrix gateway is in R1.
  - Q5-Q11 as recommended.
  - Q8 = **minimise**: the gateway stores the Matrix JSON without `notification.content`, `sender_display_name`,
    `room_name` and `room_alias`. Decided by the QA and the coordinator; the user can overrule.
- **What the answers change:**
  - The receiver is enabled on every managed phone once a public origin is saved, and existing phones flip on too
    (A1, §10.2).
  - The switch to the F-Droid build is a parent runbook, because the signers differ and the catalog refuses to pin
    over the Play build (A2, §6.6).
  - With the F-Droid build there is no Google path at all: off, Stop or no origin means no push, and the "no push"
    warning moves into R1 (A3, §6.3).
- **High:** the vendored raise-to-foreground class lost messages. Now nothing is cleared: a failed bind sends at once,
  every unbind path flushes by sending, and an id is acknowledged only after its `sendBroadcast` ran (H1, §5.4).
- **Medium:**
  - the sender's identity and the extras are read before `goAsync()` (M1);
  - `up` events coalesce on the trailing edge at the sender (M2);
  - a per-registration sub-bucket under the phone's bucket (M3);
  - a changed public origin (M4);
  - 30-day tombstones of deleted secrets, and only never-seen secrets count toward a ban (M5);
  - a per-IP in-flight cap and a short body-read timeout (M6).
- **Low:** L1-L11, in §16.

## 0. Summary

The goal is push for Element X (and other UnifiedPush apps) on the kid's phone with no Google, no ntfy app, no third
party and no second connection. It should also make a phone without Google Play services workable (the user: "da kan
man rive ut gms etc om man ønsker det").

- **The phone:** the launcher becomes the UnifiedPush (UP) **distributor**. Apps register with it, and it hands each
  one an endpoint URL.
- **The server:** the server becomes the **push server** behind those URLs, and a **Matrix push gateway**.
- **Delivery:** a message for the phone waits in the server's queue. A content-free `up` event on today's SSE stream
  wakes the phone, which fetches the queue and broadcasts each message to its app. It then raises the app to the
  foreground for 5 s and acknowledges the message to the server.

Two backends were designed. The user chose A (Q1, 2026-11-16):

- **Variant A (chosen):** the Vibb server *is* the push server. Its public routes are a **port of ntfy's proven
  logic** into our Rust server: the Matrix gateway, the "charge the subscriber" rate limiting, the gone/abandoned
  answers and the RFC 8030 publish answers. It is about 400 lines of ported Rust under ntfy's Apache-2.0 licence, and
  it rides on the already-public phone listener (§3).
- **Variant B (not chosen; the fallback):** Vibb's own self-hosted ntfy is the push server, and the Vibb server subscribes to it as a client over
  the tailnet. B adds no new public route on the Vibb server, but it adds a second service and a long-lived stream
  between two servers (§4).

The launcher side (§5) is identical in both variants. So is the server's queue and device API, which is the "core"
(§3.5-§3.9). Round 1 builds the core with A's front. The A-or-B choice changes only one server file set.

- **Size (A):** server M, launcher M, a security review S, then two S rounds. The smallest useful first step is
  Element X only (R1).
- **Size (B):** server M- in R1, a lighter review, and the same launcher.
- Details in §12.

---

## 1. Scope

**In scope:**

- the distributor role in the launcher (Android spec AND_3.1.0, connector 3.3.5);
- the push server, with RFC 8030 capability URLs;
- the Matrix push gateway (`/_matrix/push/v1/notify`, GET discovery and POST);
- the queue, the ACK and the PWA view;
- Element X first, as its F-Droid build from GitHub, a catalog app (Q4, §6.6). Every other UP app needs no
  adaptation beyond the generic endpoint, which R1 has (§7); the optional VAPID check comes in a later round.

**Out of scope:**

- **Encrypting Matrix pushes.** We can't: the gateway never sees the app's Web Push keys (§6.4).
- **A UP connector in Vibb's own apps.** Vibb has its own SSE.
- **The D-Bus (Linux) side of UP.**
- **iOS.**
- **Removing GMS from the Jelly Star.** §8 explains why it is not recommended there; it is Q9.

**Facts this rests on, checked in the sources** (the scratchpad clones of 2026-11-16):

| Source | Commit | Date |
|---|---|---|
| `specifications` | 0c5301f | 2025-10-16 |
| `android-connector` | 33d59cb | 2026-11-11 |
| `android-distributor` | 6daaf14 | 2026-08-26 |
| `ntfy` | a098f52 | 2026-11-16 |
| `common-proxies` | 6546f8d | 2026-10-05 |
| element-x-android | 39bbc24 | 2026-11-12 |

The last prior research pass (`up/research-summary.md`) was re-read against these. Its claims hold, with one
correction in §6.1: the provider picker's condition.

---

## 2. Architecture and message flow (variant A)

```
 Element X ──REGISTER(token, message=@emma:example.org)──▶ launcher (UpReceiver, exported)
    ▲                                                          │ POST /api/devices/up/registrations
    │ NEW_ENDPOINT(token, endpoint, id)                        │   {reg, secret, package, vapid?}
    │◀──────────────── endpoint = https://phones.example.com/up/<secret> ◀── Vibb server (stores SHA-256(secret))
    │
    │ Element X: GET https://phones.example.com/_matrix/push/v1/notify ──▶ {"unifiedpush":{"gateway":"matrix"}}
    │ Element X: sets the pusher on its homeserver (pushkey = endpoint, gateway = that URL)
    │
 homeserver ──POST /_matrix/push/v1/notify {notification:{…, devices:[{pushkey}]}}──▶ Vibb server (phone listener)
                                                         queue row (≤ 4096 B), 200 {"rejected":[]}
 Vibb server ──SSE "event: up"──▶ launcher anchor ──POST /api/devices/up/messages {ack:[…]}──▶ messages
 launcher ──bind RAISE_TO_FOREGROUND (5 s) + MESSAGE(token, bytesMessage, id)──▶ Element X ──MESSAGE_ACK──▶ launcher
 launcher ──next POST /api/devices/up/messages {ack:[ids]}──▶ server deletes the rows
```

### 2.1 Registration

1. An allowed app sends `org.unifiedpush.android.distributor.REGISTER`, with `FLAG_SHARE_IDENTITY` on Android 14 and
   later, and the extras `token`, optionally `message` and `vapid`. The launcher's exported `UpReceiver` reads the
   sender with `getSentFromPackage()` (§5.1).
2. The launcher checks the request offline against the cached policy:
   - the phone's UP switch;
   - the app's allowlist;
   - the parent's "stopped" list;
   - the caps, and at most 20 new registrations an hour per app (L10).
   It then looks up `(package, token)`.
   - **Known and confirmed:** it answers `NEW_ENDPOINT` from its own store at once, with no network. The spec says a
     distributor "SHOULD NOT create a new endpoint if a valid registration exist[s]".
   - **New:** it makes a registration key `reg` (16 random bytes, 32 hex) and an endpoint secret (20 random bytes,
     base64url, 27 characters: the spec's recommended 160 bits). It writes both to its store as `pending` and posts
     `{reg, secret, package, vapid?}` to the server. The connection token itself never leaves the phone.
3. The server checks its side of the same rules (§3.8), stores `SHA-256(secret)` (never the secret) and answers
   `{endpoint}`. Because the phone chose the secret, a retry after a lost answer is idempotent: the same `reg` and
   secret give the same row.
4. The launcher stores the endpoint, marks the registration `confirmed`, and sends `NEW_ENDPOINT(token, endpoint, id)`.
   The app answers `MESSAGE_ACK(token, id)`, which the connector does by itself.
5. Element X then runs its gateway discovery against the endpoint's origin (§6.2) and sets its pusher on the
   homeserver. A Web Push app (Mastodon) instead sends the endpoint and its keys to its server.

### 2.2 Delivery

1. A sender posts to the endpoint (`POST /up/{secret}`) or to the gateway (`POST /_matrix/push/v1/notify`). The server
   resolves the secret's hash to a registration. It applies the rate limit charged to the **phone** (ntfy's "rate
   visitor", §3.3) and the queue caps, stores one queue row and answers (§3.4). Then it sends the phone's stream one
   `up` event, via a new `up_notify` broadcast (§3.9).
2. The anchor (`CommandListenerService`) sees `type == "up"`. It asks `UpRuntime` for a pull, under a short wake lock
   and coalesced like `SyncRunner`: one pull in flight plus one trailing. A pull is
   `POST /api/devices/up/messages {ack: [...]}`, which acknowledges what was delivered last time and returns up to 50
   pending messages.
3. For each message, the launcher checks the target. If it is installed, not hidden and its registration is
   confirmed, the launcher binds the app's `RAISE_TO_FOREGROUND` service for 5 s. It then broadcasts
   `org.unifiedpush.android.connector.MESSAGE` with `setPackage(app)`, `FLAG_INCLUDE_STOPPED_PACKAGES` and the extras
   `token`, `bytesMessage` and `id`. The `id` is the message's random key (L10). The raise follows the reference
   distributor's order: bind, queue while binding, send on connect. Unlike the reference, it never drops a queued
   message (H1, §5.4).
4. A message's key goes into the next pull's `ack` **only after its `sendBroadcast` has run** (H1). The server deletes
   acknowledged rows. The app's own `MESSAGE_ACK` only feeds the 30-day liveness rule on the phone (§5.6).
5. **Catch-up:**
   - every SSE reopen pulls once (today's `sse_open` path);
   - every sync pulls while UP is on (the backstop alarm, 30 min), so a lost `up` event costs at most the backstop;
   - before the first unlock nothing runs, because the launcher is never direct-boot-aware. Messages wait on the
     server for their TTL (at most 24 h, §3.6).

**Why a nudge and a fetch, not the message inside the SSE event.** Design 19's rule stays: the stream carries no
content. A lagged broadcast receiver (`BroadcastStreamRecvError::Lagged`, which today already forces a nudge) can then
never lose a message, because the queue is the truth. One more HTTPS request costs about 100-300 ms on a radio that is
already awake, which is irrelevant for a ring.

### 2.3 Unregistration, uninstall and expiry

| Event | Phone | Server | The sender then |
|---|---|---|---|
| App sends `UNREGISTER(token)` | identity checked, local row removed, `DELETE /api/devices/up/registrations/{reg}` (kept in a retry list if offline), `UNREGISTERED(token)` sent | row and its queue deleted; its `secret_hash` kept 30 days as a tombstone (M5) | `410` from `/up`, or the pushkey in `rejected` from the gateway; the homeserver drops the pusher |
| App uninstalled (`PACKAGE_FULLY_REMOVED`) or its data cleared (`PACKAGE_DATA_CLEARED`, so its tokens are gone) | local rows removed, `DELETE` each | deleted, tombstoned | 410 / rejected |
| App leaves the allowlist (hidden) | nothing to send: a hidden app gets no broadcast | every allowlist writer prunes its rows and nudges (L10) | 410 / rejected; once re-allowed, the app re-registers at its next start and gets a new endpoint |
| Parent's "Stop" in the PWA (§3.10) | the policy's `regs` no longer lists the rows: `UNREGISTERED` to the app, local rows removed | rows deleted and tombstoned, package on the phone's `stopped` list | 410 / rejected. **Element X re-registers by itself** and gets `REGISTRATION_FAILED(ACTION_REQUIRED)`; it may show the kid a notification that push failed (A3). With the F-Droid build it then has no push at all |
| Phone's UP switch off, or the public origin cleared | `UNREGISTERED` to every app, receiver and Link Activity disabled | all the phone's rows deleted and tombstoned | 410 / rejected; Element X (F-Droid) has no push at all until it is on again (A3) |
| The public origin changed (M4) | the policy's `origin` differs: every endpoint is rewritten to the new origin and `NEW_ENDPOINT` sent | the previous origin is kept 30 days | the gateway accepts both origins meanwhile; Element X rediscovers its gateway and re-sets its pusher |
| No app ACK for 23 days | `NEW_ENDPOINT` with a fresh id (the spec's ping) | - | - |
| Ping unanswered 7 more days (30 in all) | `UNREGISTERED`, `DELETE` | deleted, tombstoned | 410 / rejected |
| Phone unseen 30 days (no stream, pull or report) | at its return, the policy's `regs` is empty: `UNREGISTERED` to each app, which re-registers | retention deletes its rows and tombstones them: ntfy's "abandoned topic" rule, with our knowledge (§3.3) | 410 / rejected |
| Phone deleted in the PWA | - | `ON DELETE CASCADE`; the secrets tombstoned first | 410 / rejected |

The policy's `regs` is reconciled race-free. A local registration missing from `regs` is dropped only if the server
confirmed it before the policy request was sent. The phone stores `confirmedAtMs` and compares it with the request's
start, so a registration made during a policy fetch survives.

### 2.4 tsnet or the public URL

Both kinds of phone work, and nothing changes in how a phone reaches its server.

- **The device routes:** register, unregister and messages are ordinary bearer routes. They go over whatever path the
  phone uses today, the embedded tsnet or the public URL (`serverPathNow`).
- **The endpoint URLs always carry the public origin** (§3.1). Element X's discovery and the homeserver run on their
  own networks, never through our tsnet. Synapse's default `ip_range_blacklist` also refuses 100.64.0.0/10 for pushes.
- So UP needs a public HTTPS name for the phone listener: DEPLOY.md option (c), Funnel on the phone listener, or
  option (b) **only with `DEVICE_BIND_ADDR` set** (the UP routes live on the phone listener alone; L7). A
  tailnet-only install gets no UP. The PWA says so, and the receiver stays disabled (Q11).

---

## 3. The server (variant A: ntfy's logic ported)

### 3.1 The public origin

There is one server-wide setting: **the phones' public address for app push**, for example
`https://phones.example.com`.

- **Where it lives:** a single-row table `unified_push_settings(id = 1, public_origin, previous_origin,
  origin_changed_at)`. The parent edits it on a new Settings hub row, "Push for apper" / "App push", at
  `/settings/push`.
- **Saving it turns app push on for every phone** whose own switch is on, and the switch is on by default (Q3, A1).
  The page says so above the Save button:
  - nb: "Når du lagrer, får alle telefonene push for apper (du kan slå det av per telefon)";
  - en: "Saving turns on app push for every phone (you can turn it off per phone)".
- **Prefill (L7):** the field is prefilled from Phone Setup's saved address when that is `https://` and its host is
  not a Tailscale IP. A `*.ts.net` name stays a candidate: Funnel names are `*.ts.net` too. The port must be one Funnel
  serves (443, 8443 or 10000) when the name is `*.ts.net`. **The prefill never saves by itself:** only the parent's
  Save sets the origin, and with it turns UP on.
- **The check:**
  - `https://` only;
  - an **origin**: no path, query or fragment. Element X probes `scheme://host[:port]/_matrix/push/v1/notify`
    (`UnifiedPushGatewayResolver.kt`), so the phone listener must sit at the root of that name;
  - not an IP literal in 100.64.0.0/10 or fd7a:115c:a1e0::/48.
- **A changed origin (M4):**
  - the old one moves to `previous_origin` with the time, and is accepted for 30 days (§3.3, the gateway's prefix rule);
  - the policy carries the new `origin` (§3.8);
  - the phone rewrites every endpoint (it holds each secret) and sends `NEW_ENDPOINT`, so every app hands its app
    server the new URL;
  - `/up/` resolves by the secret whatever host the request named.
- **Unset (cleared):** UP is off for every phone. The policy then says `on: false`, `/up` answers 410 (the rows are
  tombstoned) and discovery answers 404.
- **The page shows** "last seen from outside": the newest discovery GET and the newest accepted push, server-wide,
  times only. This is the only reachability signal; the server never probes itself, so no host action is involved.

### 3.2 Routes, on the phone listener only

A new `unified_push::routes(state)` is merged in `build_device_router` (`main.rs`), next to `device_routes`. The
admin listener never serves these routes, so the admin gate's surface is unchanged (a test,
`tests::listeners::up_routes_only_on_the_phone_listener`).

| Route | Auth | What |
|---|---|---|
| `POST /up/{secret}` | the secret (a capability URL, RFC 8030) | publish one message |
| `GET /_matrix/push/v1/notify` | none | gateway discovery: `{"unifiedpush":{"gateway":"matrix"}}` |
| `POST /_matrix/push/v1/notify` | the pushkey's secret | the Matrix push gateway |

They sit behind their own `unified_push::edge` layer, not `limits::device_edge`. The edge provides:

- **its own in-flight semaphore** (32). The device API's `UNAUTHENTICATED_IN_FLIGHT` (64) stays for enrol and bad
  tokens, so a push flood can't block an enrolment and vice versa;
- **a per-IP in-flight cap of 4** (`net::limit_key`; M6), so one client can't hold the 32 slots;
- **a 3 s body-read timeout** (M6), then the 10 s answer timeout;
- **a body limit:** 4096 + 1 bytes for `/up` (413 above); 4096 for the gateway, ntfy's `MessageSizeLimit` applied to
  the whole Matrix JSON;
- **the same response headers** as the device API (`nosniff`, `default-src 'none'; sandbox`, `no-store`).

Other methods and paths fall to the listener's bare 404.

### 3.3 The port from ntfy

This is the user's preference (2026-11-16): lift what we need of ntfy's proven logic into our server. ntfy is
dual-licensed Apache-2.0 / GPLv2. The port is taken under Apache-2.0 (§11).

| ntfy (a098f52) | Lines | Ported to | Rust, about | What is kept |
|---|---|---|---|---|
| `server/server_matrix.go`: `matrixRequest`, `newRequestFromMatrixJSON`, `writeMatrixDiscoveryResponse`, `writeMatrixResponse`, `errMatrixPushkeyRejected`; `server.go`'s `transformMatrixJSON`, `handleMatrixDiscovery`, `handlePublishMatrix` | 165 + ~40 | `server/src/unified_push/matrix.rs` | 130 | the whole request rule: the body peeked to the size limit, otherwise **413**; invalid JSON or no `devices[0].pushkey` gives **400**; **only `devices[0]`** (ntfy #316: Synapse sends one device per request); the pushkey must start with our base (`<origin>/up/`, the current origin or for 30 days the previous one, the host compared case-insensitively; M4), otherwise it is **rejected** (200 `{"rejected":[pushkey]}`, ntfy's no-open-relay rule); the registration is then found by the secret alone; a gone registration is also rejected, as ntfy's abandoned topic is; every other error passes through as its status (a 429 or 507 is transient to the homeserver). **Changed from ntfy (Q8):** the stored body is the notify JSON **minimised**, without `notification.content`, `sender_display_name`, `room_name` and `room_alias`; Element X reads only `event_id`, `room_id`, `counts` and `prio` |
| `server.go`'s `handlePublishInternal` (the UP branch), `parsePublishParams` (the UP detection: `Content-Encoding: aes128gcm` or `up=1`) | ~60 | `server/src/unified_push/publish.rs` | 120 | a body of 1-4096 bytes, stored as raw bytes (ntfy's base64 envelope is not needed: our device API carries base64 in JSON anyway); **a transient refusal is a 5xx, never a 4xx**, because Mastodon's `push_notification_worker.rb` deletes the subscription on any 4xx except 408/429 (ntfy's comment, `server.go:1129-1134`); **gone is 410**, because Misskey and its forks delete only on 410 |
| `server/server_rate_visitor.go`: `rateVisitor`, `unifiedPushTopicAbandoned`, `topicAbandoned` | 99 | `server/src/unified_push/rate.rs` | 80 | **charge the subscriber, not the publisher**: every push to a registration is charged to its phone's bucket, so one homeserver IP pushing for many people is never the one limited; **abandoned means 410**: ntfy can't know whether a topic is still wanted, so it says gone after 4 days without a subscriber, but we know, so gone means the registration was deleted (§2.3, including the 30-day unseen phone) |
| `server/visitor.go`'s `RequestAllowed`/`MessageAllowed` and `config.go`'s defaults (`VisitorRequestLimitBurst` 60, a 5 s replenish) | ~30 | `rate.rs`, on `limits.rs`' existing token bucket | 40 | the model, not ntfy's numbers: ntfy's 60-burst, one-per-5 s is per anonymous visitor; a phone in a busy group chat needs more, so **120 burst, 1 per second per phone**, and under it **a sub-bucket per registration, 30 burst, one per 2 s** (M3), so one flooded endpoint can't starve the phone's other apps; a 429 with `Retry-After` |
| `server/errors.go`: `errHTTPBadRequestMatrixMessageInvalid` (40019), `errHTTPGoneUnifiedPushTopicAbandoned` (41002), `errHTTPEntityTooLargeMatrixRequest` (41302), `errHTTPInsufficientStorageUnifiedPush` (50701) | ~10 | `unified_push/mod.rs` | 20 | the four answers' statuses, with empty bodies: app servers don't read ntfy's JSON error bodies |
| `server/server_matrix_test.go` (9 tests), `server_rate_visitor_test.go` (14) | ~400 | `matrix.rs`/`rate.rs` tests | 250 | the Matrix tests one for one (success, too large, invalid JSON, not Matrix, mismatching pushkey, invalid secret, discovery, error, success writer) and the abandoned and charge tests that still apply |

**Left out, with reasons:**

- **ntfy's topic model** (`topicRegex`, the `up` prefix, the 14-character length). ntfy uses these to mark "this
  anonymous topic gets subscriber-based limits". Every one of our endpoints is a registration with an owner, so
  every one gets them. Our secret follows the spec's 160 bits, where ntfy's 14 characters give about 71.
- **The cluster topic service** (`tp.Service`), Firebase, Web Push to browsers, attachments, e-mail and calls.
- **The message cache and `since=`.** Our queue and ACK replace them.
- **Auth and ACLs.** The secret is the capability.
- **`?up=1`**, and the GET `{"unifiedpush":{"version":1}}` on a topic, which is ntfy's own distributor's server
  discovery.
- **The rate-limit headers.**
- **`X-Forwarded-For` handling.** We have `net::Client`.

**About 400 lines of ported Rust and about 250 of ported tests. Our own code is around it:**

- the store, the device API, the SSE event, the policy key, the PWA and the retention: about 900 lines, plus tests.

**`unifiedpush-rs`** (the scratchpad's clone, Apache-2.0) has nothing for the server side. It is the Linux D-Bus
connector, plus the client's Web Push key handling (`webpush_keys.rs`) for decrypting. Our server never decrypts. At
most it could make RFC 8291 test vectors, and the UP example app's own "send a test" (§12.3) does that live.

### 3.4 The answers

**`POST /up/{secret}`** (RFC 8030 §5, with ntfy's rules for app servers):

| Case | Answer |
|---|---|
| accepted (queued) | **201**, `Location: <origin>/up/m/<key>` (the message's random key, 16 bytes hex; L10; GET and DELETE on it are 404), `TTL: <stored ttl>` |
| accepted, but the registration's queue was full | 201; the registration's oldest pending row is dropped. A Matrix push is "sync now", so the newest is the one that matters |
| empty body | 400 |
| body > 4096 | **413** |
| a tombstoned secret (deleted within 30 days: unregistered, Stop, switch off, origin cleared, phone unseen or deleted) | **410**, not counted toward the IP's ban (M5); **404** when the body is Matrix-shaped JSON (L1, below) |
| a never-seen secret | **410**, counted toward the IP's ban (M5) |
| the registration's sub-bucket or the phone's bucket is empty | **429**, `Retry-After` |
| server-wide queue store full (64 MiB) | **507** (transient: Mastodon retries a 5xx) |
| a VAPID key was registered and the request's VAPID doesn't verify (R3) | 401 (`WWW-Authenticate: vapid`; RFC 8292 §4.2's 403 is for a mismatching key, also a delete for Mastodon - acceptable, since a wrong key means a wrong sender) |

**Why 404 for a Matrix-shaped body (L1).** Such a body came through an outside Matrix gateway (unifiedpush.org's,
§6.2). common-proxies' gateway puts a pushkey in `rejected` only for an answer of 401-404 (`gateway/matrix.go`,
`Resp`). A 410 would leave the homeserver pushing to a gone endpoint forever. Every other sender gets 410 (Misskey
deletes only on 410).

The headers we honour:

- **`TTL`** is clamped to 0-86400 s. With no header it defaults to 86400 (RFC 8030 requires the header; ntfy and
  common-proxies tolerate its absence). A TTL of 0 is queued only while the phone's stream is open; otherwise it is
  answered 201 and dropped.
- **`Urgency`** (`very-low`/`low`/`normal`/`high`, otherwise `normal`) is stored and passed to the phone.
- **`Topic`** (RFC 8030 §5.4) replaces the registration's pending row with the same topic.

**`POST /_matrix/push/v1/notify`:**

- 200 `{"rejected": []}` when queued.
- 200 `{"rejected": [pushkey]}` when the pushkey isn't ours (neither origin) or its secret is tombstoned or never
  seen. Synapse then deletes that pusher. Only the never-seen case counts toward the ban.
- 400, 413, 429 and 507 as above. The GET answers `{"unifiedpush":{"gateway":"matrix"}}` with 200 while a public
  origin is set, otherwise 404 (Element X then falls back to the public gateway, which then gets 404 from `/up/`).

**The ban (M5).** Only never-seen secrets count as failures for the client IP, in UP's own map (`net::limit_key`, the
device API's numbers: 30 in 10 min, then 429 for 10 min). **A ban never blocks a request whose secret resolves.** The
lookup of the secret's hash comes first, and the ban applies only to an unresolved one, so a homeserver that shares
an IP with a misbehaving sender still delivers. The cost of a banned client stays bounded by the per-IP in-flight cap
(M6). Guessing 160 bits is hopeless anyway; the map keeps the database and the queue quiet, and it can never touch the
phones' own device API.

### 3.5 Storage: migration `NNNN_unified_push.sql` (the next free number at build time; L9: 0151 is taken by `blocked-channels`)

```sql
CREATE TABLE unified_push_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  public_origin TEXT, previous_origin TEXT, origin_changed_at TEXT   -- M4
);
ALTER TABLE devices ADD COLUMN unified_push INTEGER NOT NULL DEFAULT 1;  -- the phone's switch, on by default (Q3, A1)
CREATE TABLE up_registrations (
  id INTEGER PRIMARY KEY,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  reg TEXT NOT NULL,                       -- the phone's key, 32 lowercase hex
  secret_hash BLOB NOT NULL UNIQUE,        -- SHA-256 of the endpoint secret; the secret is never stored
  package TEXT NOT NULL,
  vapid TEXT,                              -- the app's 87-char VAPID key, or NULL
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_push_at TEXT, last_delivered_at TEXT,
  outside_gateway_at TEXT,                 -- a Matrix body that came to /up directly (§3.10, R2)
  pushes INTEGER NOT NULL DEFAULT 0, dropped INTEGER NOT NULL DEFAULT 0,
  UNIQUE (device_id, reg)
);
CREATE TABLE up_messages (
  id INTEGER PRIMARY KEY,
  key TEXT NOT NULL UNIQUE,                -- 16 random bytes, hex: Location and the MESSAGE id (L10)
  registration_id INTEGER NOT NULL REFERENCES up_registrations(id) ON DELETE CASCADE,
  device_id INTEGER NOT NULL,
  body BLOB NOT NULL,                      -- 1..4096 bytes (a Matrix body minimised, Q8); never logged
  urgency TEXT NOT NULL, topic TEXT,
  created_at_ms INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL
);
CREATE INDEX up_messages_device ON up_messages (device_id, id);
CREATE INDEX up_messages_expiry ON up_messages (expires_at_ms);
CREATE TABLE up_gone (                     -- M5: tombstones, pruned after 30 days
  secret_hash BLOB PRIMARY KEY, gone_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE up_stopped (
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package TEXT NOT NULL, stopped_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (device_id, package)
);
```

- **Existing phones flip on too (A1):** the column's default applies to every row the migration adds it to. Nothing
  changes on a phone until a public origin is saved: no origin means no UP.
- **Every deletion of a registration** (the device API, Stop, the switch, the origin cleared, the allowlist prune,
  retention, a phone deleted) goes through one `store::delete_registrations`, which writes the tombstones first.
- **The secret** is looked up by its SHA-256. A plain hash is enough for a 160-bit random value; argon2 would only
  add CPU per push.
- **Nothing per message is logged.** There is no secret, no body and no path: the server has no access-log layer, and
  DEPLOY.md tells the proxy to skip `/up/*` (§3.12). `secure_delete` is already on, so an acknowledged row is
  overwritten.
- **The security log never stores an `/up/` path (L5).** The paths it keeps today are redacted to `/up/…` before they
  are stored: `admin_refused`, `device_auth_failed`, and every caller of `audit::clip` that records a path. The UP ban
  itself records nothing per request; it is an in-memory map with no path.
- **Tracing** names a device id and a registration id at debug level only.

### 3.6 The queue

- **One row per message.** A row leaves when it is acknowledged, or when it expires (retention's hourly
  `retention::prune`, and a pull never returns an expired row).
- **TTL:** at most 24 h (Q10). A phone off for longer loses those pushes; Element X and Mastodon apps sync on their
  next start.
- **Caps:**
  - 100 pending per registration (the oldest is dropped, §3.4);
  - 500 rows or 1 MiB per phone (the oldest of the phone's largest registration is dropped);
  - 64 MiB server-wide (507).
- **Counters:** `pushes` and `dropped` per registration, for the PWA.

### 3.7 The device API (bearer, in `device_routes.rs`)

| Route | Body | Answers |
|---|---|---|
| `POST /api/devices/up/registrations` | `{reg, secret, package, vapid?}`: `reg` 32 hex; `secret` 27 base64url characters; `package` a valid package name; `vapid` 87 base64url characters | 200 `{"endpoint": "https://phones.example.com/up/<secret>"}` (a retry of the same `reg`+`secret` returns it again); 409 `{"refused": "off"|"stopped"|"not_allowed"|"limit"|"busy"|"conflict"}`: `not_allowed` = not on the phone's allowlist (managed phones), `limit` = 100 per app or 300 per phone, `busy` = more than 20 new registrations of this app in the last hour (L10; an in-memory count per phone and package), `conflict` = the `reg` exists with another secret or package, or the secret's hash is tombstoned; 400 for a malformed body; 413 above 1 KiB |
| `DELETE /api/devices/up/registrations/{reg}` | - | 204, also for an unknown `reg` |
| `POST /api/devices/up/messages` | `{ack: [key, ...]}` (at most 200) | 200 `{"messages": [{key, reg, urgency, body}], "more": bool}`: `key` the message's random key, `body` standard base64, at most 50 messages or 256 KiB; acknowledged keys are deleted first (only this phone's); 413 above 16 KiB |

- **Route class:** General. The phone's token bucket (600 burst, 2/s) is enough, because pulls coalesce on the phone
  and every pull drains up to 50.
- **The capability:** `unified_push_v1` in the status report's `capabilities`. The server sends the policy key only to
  a phone whose last report claims it, the pattern of `server_tls`. The `up` events need no such gate (L2, §3.9).

### 3.8 The policy key

The policy carries `unified_push`, only to a launcher claiming `unified_push_v1`:

```json
"unified_push": {"on": true, "origin": "https://phones.example.com", "regs": ["3f1c…", "a09e…"],
                 "stopped": ["org.joinmastodon.android"]}
```

- `on`: the phone's switch (on by default) **and** a public origin set.
- `origin`: the current public origin, `null` when unset (M4).
- `regs`: the phone's confirmed registration keys.
- `stopped`: the packages the parent stopped.
- The server-side checks of §3.7 repeat these, so an older or a broken phone can't bypass them.

**Every allowlist writer** prunes the phone's registrations of packages that are no longer allowed, through one
`unified_push::prune_disallowed(db, device_id)`, and nudges the phone (L10). Today's writers are:

- `devices::edit_allowlist`, the PWA's save;
- `device_api::status_intake`'s allowlist write;
- the policy row's creation in `devices.rs`.

A source-scan test (`tests::unified_push::every_allowlist_writer_prunes`) finds every non-test
`SET allowlist_json`/`INSERT INTO device_policy` and fails unless its function calls the prune.

**The pause** ("Pause all restrictions") changes no allowlist, so it changes nothing here. A not-allowed app that runs
during a pause can't register: both sides check the allowlist, not Android's hidden state. That is not a new
restriction on the kid: UP is the distributor's own scope, and the parent lifts it in the PWA (allow the app, or
un-stop it).

Pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`, and by the shared contract (§9).

### 3.9 The SSE event

- **A new `AppState.up_notify: broadcast::Sender<i64>`**, a second channel. It touches none of today's dozens of
  `command_notify.send(device)` call sites.
- **`commands_stream` merges it:** `Event::default().event("up").data("up")`.
- **No capability gate (L2).** Only a phone with registrations gets pushes, and only a UP-capable launcher makes
  registrations, so an older launcher never receives an `up`.
- **Lagged:** a `Lagged` on the `up` channel sends only `up`; one on the command channel sends today's `command`
  (M2).
- **Coalescing at the sender, on the trailing edge (M2).** It runs per device, in the enqueue path, not in the
  stream:
  - the first push in a quiet phone's window sends `up` at once;
  - a push within 250 ms of the last send arms one trailing send at the window's end;
  - so the last push of every burst is always followed by an `up`. A pull that ran before the second message was
    queued can't leave it waiting for the backstop.
- **Design 91's `/locked/stream`** (Find My Device before the first unlock) must **not** merge `up_notify` (L2).
  Before the first unlock nothing of UP may run on the phone (§5.3).

### 3.10 The PWA (nb and en, i18n keys `push.*`)

**Settings hub › "Push for apper" / "App push"** (`/settings/push`):

- the public address field, with the check's reason when refused:
  - nb: "Adressen må være https:// og bare et vertsnavn";
  - en: "The address must be https:// and a host name only";
- the explanation: UP needs the phones' address reachable from the internet (DEPLOY.md option (c));
- "Sist sett utenfra" / "Last seen from outside" (§3.1).

**Device › Apps**, a group "Push for apper" / "App push" under the app list:

- **the phone's switch**, on by default (Q3), with its sub-line:
  - nb: "Apper på listen kan få varsler gjennom Vibb, uten Google";
  - en: "Allowed apps can get notifications through Vibb, without Google";
- **its off sheet (A3; worded for the Play build since the amendment):**
  - nb: "Apper som bruker Vibb for varsler, får ingen mens dette er av, til hver av dem settes tilbake til Google i
    sine egne innstillinger (Element X: Innstillinger › Varsler › Avansert › Push-leverandør).";
  - en: "Apps that use Vibb for notifications get none while this is off, until each is switched back to Google in its
    own settings (Element X: Settings › Notifications › Advanced › Push provider).";
- **one row per app with registrations:**
  - its name and logo (design 87's tiles);
  - nb "2 kontoer · sist levert 14:02", en "2 accounts · last delivered 14:02";
  - a "Stopp" / "Stop" button with a confirm sheet (A3):
    - nb: "Element X får ingen varsler gjennom Vibb før du tillater det igjen. Appen prøver å koble til på nytt og kan
      vise barnet en melding om at varsler ikke virker; i appens egne innstillinger kan den settes tilbake til
      Google.";
    - en: "Element X gets no notifications through Vibb until you allow it again. The app will try to reconnect and
      may show your child a message that notifications don't work; in its own settings it can be switched back to
      Google.";
- **"Stoppet" / "Stopped":** the stopped apps, each with "Tillat igjen" / "Allow again";
- **R1's warning (A3), "no push":** an allowed app known to use UP (Element X's package, from a short list in
  `unified_push::KNOWN_UP_APPS`), with no registration on a phone whose UP is on:
  - since the amendment it names the per-session switch: nb "Element X bruker ikke Vibb for varsler. I Element X:
    Innstillinger › Varsler › Avansert › Push-leverandør › Vibb One, én gang for hver konto.", en "Element X isn't
    using Vibb for notifications. In Element X: Settings › Notifications › Advanced › Push provider › Vibb One, once
    for each account.".
  The row also names "Ingen levering på 7 dager" / "No delivery in 7 days" for an app with registrations;
- **R2's warning:** "Element X sender gjennom unifiedpush.org" (`outside_gateway_at` within 7 days). The phones'
  address didn't answer Element X's discovery, so the public gateway relays.

**Every save uses design 103's saves-in-place.** The page never jumps to the top: the switch autosaves, and Stop and
Allow again morph the group in place. Every new word is in `en.json` and `nb.json` (`i18n::tests`,
`nb_pages_have_no_english`).

**The security log:** the switch and the address are `settings_saved` rows (who and when, never a secret). There is
no parent event for pushes.

### 3.11 Security

**The new surface:** three unauthenticated public routes on a listener that is already public. Variant A's whole
security case:

1. **No open relay.**
   - The server makes **no outbound request** for UP at all. The gateway only queues for our own registrations: the
     pushkey must start with `<origin>/up/` and its secret must resolve.
   - It is therefore not an SSRF vector, and not a relay to any other push server.
   - ntfy's rule, ported (`errMatrixPushkeyRejected`).
2. **Capability URLs.**
   - 160-bit secrets, stored only as hashes.
   - The endpoint lives in the app and its app server (the homeserver's pusher table, Mastodon's subscriptions).
     Anyone holding it can only make the app *wake*, never read anything.
   - Abuse is bounded by the phone's bucket and the queue caps.
3. **Bounded cost.** Every limit is set before the body is read or a database row is written:
   - the body limits, the in-flight semaphore and the per-IP in-flight cap;
   - the 3 s body-read timeout and the answer timeout;
   - per-phone buckets with per-registration sub-buckets;
   - per-IP failure counts (never-seen secrets only) in a separate map, which never blocks a resolving secret;
   - per-registration, per-phone and server-wide queue caps;
   - a TTL ≤ 24 h.
4. **Nothing logged per message.**
5. **The admin gate is untouched.** The routes exist only on the phone listener, and the listener's fallback 404
   stays.
6. **Data held:** encrypted Web Push bytes, or Matrix JSON minimised (Q8): event and room ids, an unread count, the
   pushkey and Element X's `cs` client secret in `devices[0].data`, never a message's content or a room's or sender's
   name. It is held until acknowledged (seconds), or 24 h at most. Deleted secrets are kept 30 days as hashes
   (tombstones).
   - The kid's MXID is never sent: the REGISTER `message` stays on the phone (§6.5).
   - Element X's `cs` is already in the homeserver's pusher row. It only lets Element X recognise its own pushes.
7. **A security review** of R1's server part is a step of its own (§12). Its focus is the edge layer, the gateway
   parse, the bucket keys and the logs.

### 3.12 DEPLOY.md (a new section "App push (UnifiedPush)")

- **Caddy:** nothing changes for a site that proxies the whole phone host (`reverse_proxy 127.0.0.1:3101`, as the
  example). If the site has access logging on, add `log_skip /up/*`: the path is a capability.
- **nginx:** `location /up/ { proxy_pass http://127.0.0.1:3101; access_log off; client_max_body_size 8k; }` and
  `location = /_matrix/push/v1/notify { proxy_pass http://127.0.0.1:3101; client_max_body_size 8k; }`.
- **Checks from outside:**
  - `curl -s https://phones.example.com/_matrix/push/v1/notify` should print `{"unifiedpush":{"gateway":"matrix"}}`;
  - `curl -s -o /dev/null -w '%{http_code}' -X POST --data x https://phones.example.com/up/AAAAAAAAAAAAAAAAAAAAAAAAAAA`
    should print `410`.
- **A homeserver on your own LAN** that resolves `phones.example.com` to a private address needs it in Synapse's
  `ip_range_whitelist` (Synapse refuses private ranges for pushes by default).
- **Option (b)** (everything public) serves UP only with `DEVICE_BIND_ADDR` set: the routes live on the phone listener
  alone (L7).
- **Changing the address:** the old one keeps working 30 days while the apps move to the new one (M4).
- **"Removed: MollySocket and the launcher's UnifiedPush distributor"** gets a line pointing to the new section.

---

## 4. Variant B: our own ntfy as the backend

**Not chosen** (Q1 = A, the user, 2026-11-16). Kept as the fallback and as the record of why A won. Revision 2's
fixes are A's; none are carried into B.

The user's alternative (2026-11-16). The ntfy is Vibb's own and self-hosted, so it is no third party. ntfy does the
public part, and the Vibb server is its client.

### 4.1 Design

- **ntfy, as the user runs it publicly** (`https://ntfy.example.com`), with these settings:
  - `base-url` set: its Matrix gateway and discovery need it;
  - a message cache (`cache-file`; the default `cache-duration` is 12 h);
  - `visitor-subscriber-rate-limiting: true`;
  - an ACL:
    - `ntfy access '*' 'upv*' write-only` (anyone may push, nobody anonymous may read);
    - `ntfy user add vibb`, then `ntfy access vibb 'upv*' rw`. Write access is what makes our subscription ntfy's
      "rate visitor" (`maybeSetRateVisitors`);
  - and `ntfy token add vibb`.
- **Topics are made by the server.** The server knows the ntfy's rules: a UP topic is exactly 14 characters starting
  with `up` (`isUnifiedPushTopic`), otherwise subscriber-based limiting doesn't apply. So a topic is `upv` plus 11
  random alphanumerics (about 65 bits, below the spec's 160), and the ACL can scope `upv*` away from the user's own
  ntfy-app topics. The server must also subscribe *before* the endpoint is handed out (otherwise the first push gets
  507), and it stores the topic in clear to subscribe by name, so a phone retry simply gets the same topic back. A
  topic made by the launcher would gain nothing: the server must learn it and subscribe anyway.
- **The endpoint handed to apps:** `https://ntfy.example.com/upvXXXXXXXXXXX?up=1`. Element X's discovery finds ntfy's
  own gateway at that origin.
- **The subscriber, `unified_push/ntfy.rs`:**
  - one task holding streams of at most 100 topics each (ntfy refuses over 150: `subscribeTopicsLimit`, a 2026
    addition);
  - each stream is `GET <ntfy>/<t1>,<t2>,…/json?since=<last id>` with `Authorization: Bearer tk_…`, preferably to
    ntfy's tailnet address;
  - a topic set change opens the new stream with `since=` the last id first, then drops the old one. Ids are deduped,
    and `since=<id>` resolves across topics (`ResolveMarker`, `MessagesAfterMarker`);
  - on a reconnect: backoff, and `since=` from the last id. After a gap longer than the cache, pushes are lost (a log
    line, a PWA warning);
  - `encoding: base64` bodies are decoded;
  - every message goes into the same queue as in A (§3.6), and from there on everything is identical.
- **Config:** a new page in place of §3.1, with:
  - the public ntfy address, which goes into the endpoints;
  - an optional address for Vibb (the tailnet URL);
  - the token, sealed with the server's own key like the Storytel login and the Spotify tokens (`music_secret.rs`'s
    `MusicKey`) and never shown back;
  - a "Test" button: publish to a fresh topic and see it arrive on our stream. This is outbound HTTP only, so it is
    no host action.
- **Lines:** about 450 (`ntfy.rs` 300, the config page 150). There are no public routes and no gateway code; the
  store, the device API, the SSE event and the PWA list are the same as A.

### 4.2 ntfy's behaviour that B depends on (read in ntfy's source at a098f52)

- **The rate visitor and "abandoned":** with `visitor-subscriber-rate-limiting` on,
  - a push to a `up…` topic whose subscriber hasn't been active for more than a day (`rateVisitorLiveFor`) gets
    **507**;
  - after 4 days (`unifiedPushAbandonedAfter`) it gets **410**, and the gateway rejects the pushkey. The app servers
    then **delete the subscriptions**.
  - So a Vibb server, or a Vibb-to-ntfy stream, that is down for more than 4 days silently ends every app's push.
  - It comes back only as each app re-registers at its next start (Element X does: it re-sets its pusher in
    `onNewEndpoint`).
- **With the setting off** (self-hosted ntfy's default), pushes are charged to the publisher's IP: 60 burst, then one
  per 5 s, per homeserver. The homeserver then needs `visitor-request-limit-exempt-hosts`.
- **The cache and `since=`:** a 12-hour default. An in-memory cache (no `cache-file`) loses everything at a ntfy
  restart.
- **The subscription limit:** 30 per visitor (`DefaultVisitorSubscriptionLimit`). One multi-topic stream takes one
  slot.
- **What ntfy sees:** the same as our server in A (Matrix `event_id_only` JSON, or Web Push ciphertext), kept 12 h in
  its cache. At debug level its log names topics. It is ours, so no third party.
- **What our database holds:** the topic names in clear. These are live capability URLs, and they would be in every
  backup. A holds only hashes.

### 4.3 A and B compared

| | A: ntfy's logic ported | B: our ntfy as the backend |
|---|---|---|
| Server R1 | M: the port (~400 lines + ~250 test lines) + the core | M-: the ntfy client (~300) + the config page + the core |
| Launcher | M (identical) | M (identical) |
| Security review | S: three new public routes on a public listener | S-: outbound only, plus the ntfy ACL check |
| Moving parts | one service | two services, a long-lived stream between them, an ACL, a token |
| Failure modes | the Vibb server down: pushes 5xx or time out, the senders retry, nothing is deleted | the same, **plus**: the stream down for more than a day gives 507s, more than 4 days gives 410s and every app's subscription is deleted silently; an in-memory cache loses the queue at a ntfy restart |
| Secrets at rest | SHA-256 of the secrets | topic names in clear (in Vibb's DB and backups, and ntfy's cache) |
| Endpoint entropy | 160 bits (the spec's recommendation) | about 65 bits (`upv` + 11; needed for ntfy's UP limits) |
| Upkeep | the UP spec; re-read ntfy's UP changes yearly and re-port what matters | the UP spec; **ntfy's version and API** (its UP rules changed in 2026: the 410, the 150-topic cap, the cluster topic service); the user's ntfy config |
| Other Vibb installs | work with the phone listener public | also need a ntfy, configured as above |
| New public ingress | none (the phone listener is public already) | none (the user's ntfy is public already) |

**Recommendation: A** (chosen by the user, 2026-11-16).

- The user prefers lifting ntfy's logic in.
- The port is small and keeps ntfy's tested answers. That covers the parts that took ntfy years (4xx versus 5xx for
  Mastodon, 410 for Misskey, rejected pushkeys, charging the subscriber).
- It removes B's worst failure mode, silent loss after 4 days, because the push server and the queue are one process
  with one truth.
- It stores no live capabilities.

B is the right fallback if the security review of A's public routes finds the surface unacceptable. The core is
shared, so switching then costs only R1's front.

---

## 5. The launcher

The package is `com.kidslauncher.mdm.push.up` (GPL-3.0-or-later; never in the MIT `ui/list/` or `apps/`).

### 5.1 The exported receiver: `UpReceiver`

- **The manifest:** `exported="true"`, **`enabled="false"`** as installed, with an `<intent-filter>` for exactly
  `REGISTER`, `UNREGISTER` and `MESSAGE_ACK` (`org.unifiedpush.android.distributor.*`).
  - The receiver and the Link Activity are switched on and off with `setComponentEnabledSetting(…, DONT_KILL_APP)` from
    the policy's `unified_push.on`, inside `apply()`.
  - **Since the switch is on by default (Q3, A1),** they are enabled on every managed phone once the server has a
    public origin. They are disabled when the parent turns the phone's switch off, when the origin is cleared, and on
    an older server (no key).
  - While they are disabled, `queryBroadcastReceivers` doesn't list Vibb One, so no app sees a distributor. This is
    the spec's "SHOULD be disabled until the requirement is met".
  - It becomes the second deliberate exception to "every receiver is `exported="false"`", after the
    `MdmDeviceAdminReceiver` kind. A manifest test pins it: the actions, the default disabled state, and no other
    newly exported receiver.
- **Read before `goAsync()` (M1).** `onReceive` reads `getSentFromPackage()`, the action and every extra
  synchronously, into an immutable request. Only then does it call `goAsync()` and hand the request to `UpRuntime`'s
  executor. Nothing on the executor touches the `Intent` or the receiver's identity.
- **Identity:** `getSentFromPackage()` (API 34+, `FLAG_SHARE_IDENTITY`).
  - A null identity is ignored. Our minSdk is 34, and the connector sets the flag by the device's SDK, not the app's
    target.
  - **A deliberate deviation (L4).** We never resolve the sender through the `pi` extra, and never accept AND_2's
    `application` extra (`UnifiedPush.kt:268-274`). The spec asks a distributor to implement the `pi` path on a
    device that supports apps targeting below 34, which Android 14+ does. But a `pi` path would let any app name
    another app's package, and every connector since 3.0 shares its identity on 34+. So an AND_2-only app (a
    connector before 3.0) can't register with Vibb; the PIN-gated Settings' diagnostics row logs it by package once.
- **Extras, checked by the spec's rule** ("Any request containing an extra that do not follow the specification MUST be
  ignored"). This applies to the **known** extras only:
  - `token`: a non-empty UTF-8 string of at most 100 bytes;
  - `message`: at most 100 bytes;
  - `vapid`: exactly 87 base64url characters;
  - `id`: at most 100 bytes.
  **Unknown extras are ignored, never a reason to refuse** (L4): the connector still sends AND_2's `features`,
  `application` and `pi`, and a later spec may add more.
- **Who may register** (the pure `UpRules.registerDecision`, `UpRulesTest`, rows in
  `launcher/app/src/test/resources/up_rules.json`):
  - UP on;
  - the package on the cached policy's allowlist (any app on an unmanaged phone, whose allowlist is `null`);
  - not on `stopped`;
  - under the caps: 100 registrations per app, 300 per phone. That is below the spec's "SHOULD be over 1000": a kid's
    phone has a handful of accounts, and a runaway app must not mint thousands of public endpoints. The deviation is
    stated here and in the code;
  - **at most 20 new registrations an hour per app (L10),** counted on the phone (CE prefs, the hour's count) and
    again on the server (`busy`). A re-REGISTER of a known token is not new and is answered from the store;
  - a token not held by another package (otherwise `REGISTRATION_FAILED`, as the spec says).
  - **Every refusal** is `REGISTRATION_FAILED(reason = "ACTION_REQUIRED")`. The spec reserves `INTERNAL_ERROR` for
    "try again directly", which would loop.
  - **No network or a 5xx:** `NETWORK`. The connector retries when the network is back.
  - `TEMP_UNAVAILABLE` is never sent: the spec forbids it for the device's own network, and our server is the
    phone's server.
  - **`UNREGISTER` and `MESSAGE_ACK`** must come from the package that owns the token. The spec allows ignoring
    identity once the token is linked; we check anyway.
- **Threading:** one single-thread executor (`UpRuntime`). A new registration's server call takes at most 20 s,
  within a background broadcast's budget, and Element X waits 30 s (`RegisterUnifiedPushUseCase.kt`,
  `withTimeout(30.seconds)`).

### 5.2 The Link Activity: `UpLinkActivity`

- **The manifest:** `exported="true"`, `enabled="false"`, with the filter `VIEW`, `DEFAULT`, scheme `unifiedpush` and
  host `link`.
- **The window:** `Theme.Translucent.NoTitleBar`, `excludeFromRecents`, `noHistory`, no `taskAffinity` (it runs in the
  caller's task), `finish()` in `onCreate`. It extends nothing of ours, so Home's, Dagen's and Kid Settings' rule
  redirects never run.
- **The result:** `RESULT_OK` with `pi`, an immutable broadcast `PendingIntent` to `org.unifiedpush.dummy_app` from
  which the connector reads our package. That is given only if `callingPackage` is non-null, UP is on, and the caller
  may register (§5.1). Otherwise `RESULT_CANCELED`.
- **Under kiosk and lock task:** our package is always in the lock-task packages, so an allowed app's
  `startActivityForResult` into it is admitted, also with design 16's block-activity-start bit (that bit blocks
  packages off the list, never ours). During a time rule the calling app is suspended and can't ask.
- **Vibb as the default distributor (L3), the captive-portal pattern** (`AppEnforcer.enforceCaptivePortalPin`):
  - `apply()` **always** sets a persistent preferred activity for the `unifiedpush://link` filter, pointing at
    `UpLinkActivity`, whatever UP's state;
  - `reseatPreferredActivities` adds it back in the same pass. It is our **fifth** PPA there: HOME, the Play link
    blocker, the call-log pin, the captive portal, then this one. The boot cover's PPA is `HomePin`'s own and is added
    last;
  - **whether it is used is the component's enabled state.** With UP off, the Link Activity is disabled and the link
    resolves as if Vibb weren't there;
  - so a re-seat never leaves a moment where Android shows its chooser and the kid could pick another distributor
    (an ntfy app);
  - `PlayInvariantsTest` is checked and extended: the re-seat's list and order, and "every PPA set in `apply()` is
    re-added by the re-seat".
  - Element X doesn't use the deep link (it calls `saveDistributor` directly, §6.1), so this serves the other apps.

### 5.3 Storage, and before the first unlock

- **One CE store** through `SealedPrefs` (design r10), key `up_regs_v1`, one JSON blob written with `commit()`. Each
  row holds:
  - `reg`, `package`, `token`, `endpoint`, the secret;
  - `state` (`pending` or `confirmed`) and `confirmedAtMs`;
  - `lastAppAckMs`, `pingId`/`pingSentMs`;
  - `message`, the app's description: Element X's MXID, cut to 100 bytes, **never sent anywhere**, shown only in the
    PIN-gated Settings' "Push for apper" diagnostics row (§6.5).
- **A second key, `up_deletes_v1`:** the `reg`s whose `DELETE` hasn't reached the server yet, retried at every sync.
- **Why sealed:** tokens and endpoints are capabilities. The device token already sits in this store.
- **Before the first unlock (L6).** The receiver and the Link Activity are not `directBootAware`, so Android neither
  starts nor delivers to them before the first unlock, and the apps aren't running then either.
  - **But the main process does start before the first unlock:** `BootCallReceiver` and `KidInCallService` are
    direct-boot-aware.
  - So `UpRuntime` checks `UserManager.isUserUnlocked()` before it touches `up_regs_v1` or `up_deletes_v1`. Before
    that, `requestPull` and the reconcile are no-ops.
  - The queue waits on the server (TTL ≤ 24 h), and the first unlock's anchor start pulls.
  - Design 91 (Find My Device before the first unlock) adds nothing here. UP stays CE-only, and `/locked/stream`
    carries no `up` (§3.9).

### 5.4 Delivery: `UpDelivery`, and the raise to the foreground

- **The plan** is the pure `UpDelivery.plan(message, target)`, with rows in `UpDeliveryPlanTest`:

  | The app is | The message is |
  |---|---|
  | installed and visible | delivered |
  | **suspended** (a time rule, the SMS rule) | **delivered** (§5.7) |
  | hidden (`isApplicationHidden`) | dropped and acknowledged; the server deletes the registration through the allowlist anyway |
  | uninstalled | dropped and acknowledged, the registration removed (§2.3) |
  | unknown `reg` (removed meanwhile) | dropped and acknowledged |

- **The broadcast:** `Intent(ACTION_MESSAGE).setPackage(app).addFlags(FLAG_INCLUDE_STOPPED_PACKAGES)` with the extras
  `token`, `bytesMessage` (the raw bytes) and `id`. The flag matters: a force-stopped app otherwise misses it, as
  `MusicSync.kt:160` already handles for the music app.
- **Explicit action on every intent.** On targetSdk 35+, an explicit-package intent must match the target's filter
  (Android 15's safer-intents rule). The MESSAGE broadcast and the bind intent therefore always carry their action.
- **`push/up/vendor/RaiseToForeground.kt`** is adapted from the UnifiedPush android-distributor's
  `RaiseAppToForeground.kt` (Apache-2.0, commit 6daaf14). It keeps the header and says what changed (§11). The
  algorithm is the same:
  - a `ServiceConnection` per app;
  - `bindService(Intent(RAISE_TO_FOREGROUND).setPackage(app), BIND_AUTO_CREATE)`;
  - messages queued while binding and sent on `onServiceConnected`;
  - each new message pushes the unbind 5 s out;
  - the unbind on a timeout, `onBindingDied`, `onNullBinding` or `onServiceDisconnected`.

  **The changes from the original, the loss fixes first (H1).** The original loses messages in two places: it queues
  a message whenever `bindService` was *called*, and its `unbind()` does `msgsQueue.clear()`. A bind that never
  connects (the app's service died, `onNullBinding`, the 5 s timeout before `onServiceConnected`) therefore drops
  every queued message silently.
  - **`bindService` returns `false`** (no such service, or not allowed): send the message at once, without the raise.
  - **Every unbind path flushes by sending, never clears:** the timeout, `onBindingDied`, `onNullBinding` and
    `onServiceDisconnected` each send every queued message first, without the raise, and then unbind.
  - **An id reaches the ack only after its `sendBroadcast` has run:** the class reports each sent key through a
    callback, and `UpRuntime` adds the key to the next pull's `ack` there. A crash between the fetch and the send
    therefore re-delivers instead of losing.
  - `checkForeground()` is gone. Our process always holds the anchor's FGS, and that is the precondition the original
    tests by scanning our own processes.
  - `hasRaiseToForegroundService()` is fixed. On SDK < 33 the original queried the wrong intent (`ACTION_REGISTER`); we
    run on 34+ only and query `queryIntentServices(Intent(ACTION).setPackage(app))`.
  - `send()` adds `FLAG_INCLUDE_STOPPED_PACKAGES` and the `id` extra.
  - The executor is the `UpRuntime` one.
  - An app with no such service is sent the message without the raise and logged once. The connector has declared the
    service since 3.0, so only an old connector lacks it.
  - The pure part (the queue, the state machine and its flushes) is unit-tested with a fake binder
    (`RaiseToForegroundTest`).
- **Why this is enough without Google:** binding from a process at `PROCESS_STATE_FOREGROUND_SERVICE` raises the bound
  app's process state for those 5 s, and lets it start a foreground service from the background (AOSP's FGS-start
  exemption for a client allowed to start one). Element X starts one at once (`fetchPushForegroundServiceManager.start()`
  in `VectorUnifiedPushMessagingReceiver.onMessage`). **Not yet verified on a device:** this is the R1 gate's first
  check (§12.3).
- **`FLAG_RECEIVER_FOREGROUND` is no real fallback (L10).** It only moves the broadcast to the foreground queue and
  shortens the receiver's ANR budget. It grants the app no right to start a foreground service. If the raise fails,
  the only remedy is the app's own battery exemption, which a Device Owner can't grant (§5.8).

### 5.5 The anchor and the pull

- **`CommandListenerService.onEvent`:** `type == "up"` calls `UpRuntime.requestPull("sse")`; every other event is
  today's `SyncRunner.request`.
- **`UpRuntime.requestPull`:** a timed partial wake lock (30 s), then one pull in flight and one trailing. The loop:
  - `POST up/messages {ack}`, then deliver each message;
  - each key goes into the next ack once its broadcast has been sent (H1, §5.4);
  - repeat while `more` is true, or while acks are left (the last call sends only acks and gets the next batch).
- **Catch-up:** every `sse_open` and every `performMdmSync` while UP is on also ask for a pull (after the first
  unlock only, §5.3).
- **The origin (M4):** when the policy's `origin` differs from the one the stored endpoints carry, `UpRuntime` rewrites
  every endpoint (`origin + "/up/" + secret`) and sends each app `NEW_ENDPOINT` with a fresh id.
- **Retries:** a failed pull keeps its acks and retries at the next nudge or sync. An unacknowledged message comes
  again (at least once); a duplicate after a crash between broadcast and ack is accepted.
- **The connection:** `ServerClients.base(path)`, as every server client does (S-M1's transport rule, design 76's pin).

### 5.6 Liveness: the 30-day rule

`UpRules.liveness(row, now)`, run once a day in `performMdmSync`:

- **No app `MESSAGE_ACK` for 23 days** (counting the `NEW_ENDPOINT` ack): send `NEW_ENDPOINT(token, endpoint, id =
  new)`. That is the spec's ping before an unregistration.
- **Ping unanswered 7 days:** `UNREGISTERED`, `DELETE`.
- A suspended app still acks (broadcasts reach it), so a long time rule never trips this.

### 5.7 Time rules: deliver (Q5, the user, 2026-11-16)

During a time rule every app that isn't usable is **suspended**, not hidden (`computeEnforcementPlan`). A suspended
app still receives broadcasts and runs. Android hides its notifications while it is suspended and shows them again
when it is unsuspended (NMS hides and unhides by package; to be verified per version at the gate).

- **Deliver (chosen).**
  - The app syncs quietly. Its notifications appear when the rule ends, with their real times, the same as FCM today.
  - A ring from a parent still reaches an app the rule leaves usable (`lockUsableApps`, or the contacts' messaging
    apps once the budget is used up).
  - No queue grows overnight on the server.
- **Hold (rejected).** The server would hold messages until the rule ends, which needs the rule's end on the server
  per phone. It would burst at the end and age past the TTL on long rules. And it breaks the 30-day liveness rule's
  premise.
- **Drop (rejected).** Messages read after bedtime would never notify.
- **Gate checks:**
  - the hidden notifications come back on Android 14 and 16 (and 17 by `dumpsys`);
  - a suspended Element X may start its FGS. If Android refuses it, Element X still posts from its receiver path. The
    gate records which.

### 5.8 Android 14-17, Doze and standby

- **Doze:**
  - the anchor's FGS keeps the launcher under `FOREGROUND_THRESHOLD_STATE`, so the SSE stream has network in Doze
    (`push-and-play.md`);
  - the 5 s raise lifts the app over the same threshold, and its own FGS keeps it there;
  - no battery exemption is needed for the app. A Device Owner can't grant one anyway (`setApplicationExemptions` is a
    system API).
- **App standby buckets:** a broadcast with an explicit package reaches an app in the `restricted` bucket. A gate row
  checks it with `am set-standby-bucket <pkg> restricted`.
- **34 (14):** `getSentFromPackage`, `FLAG_SHARE_IDENTITY` and the cached-app freezer (a broadcast thaws the app).
- **35 (15):** the safer-intents matching (§5.4), and the FGS type rules (Element X's own; the same with FCM).
- **36 (16), the Jelly Star:** nothing new found for broadcasts, binds or FGS-from-bind. It is the main gate image
  (`jelly36`).
- **37 (17):** read API 37's behaviour-change list for broadcasts, binds and FGS at the build. `compat372` kills
  system_server on a screencap, so its gate rows are `dumpsys`-only (`dumpsys activity processes` for the raised
  state, `dumpsys notification`).

---

## 6. Element X

### 6.1 Choosing UP: one manual switch per session

**Selection** (`DefaultPushService.ensurePusherIsRegistered`) takes the first provider with a distributor. Firebase
has `INDEX = 0` and offers a distributor only while Play services is available (`FirebasePushProvider.getDistributors`).
UnifiedPush has `INDEX = 1`, and its first distributor is the first package `queryBroadcastReceivers` returns, minus
Element X itself.

- **The Play build on the Jelly Star (GMS present) picks Firebase.**
  - The picker is in Settings › Notifications › advanced settings, "Push provider".
  - It shows while `currentPushDistributor.isFailure() || availablePushDistributors.size > 1`
    (`NotificationSettingsState.kt:85`). With Firebase and Vibb One both present that is true, so the picker shows.
  - The choice is **per session** (`userPushStore.setPushProviderName`) and is kept across restarts.
- **The F-Droid build** has no Firebase provider, so a new session picks UP and Vibb One by itself.
- **The Play build on a phone without Play services** (§8) also picks UP for a *new* session. An existing session keeps
  "Firebase" as its provider and needs the switch.

**Can it be automatic?** Not on a GMS phone with the Play build:

- Element X has no managed configuration;
- its provider choice is its own preference;
- and our Link Activity's PPA doesn't affect it (it never opens the deep link).

The ways out are the F-Droid flavour, or no Play services. **Decided (Q4, the user): the F-Droid build**, from
Element's GitHub releases as a catalog app (§6.6). A new session then picks UP and Vibb One by itself, with no picker
and no Firebase in the app. The PWA's "no push" warning (§3.10) says when a phone's Element X hasn't connected.

### 6.2 Gateway discovery against our host

`DefaultUnifiedPushGatewayResolver.getGateway` makes a GET to the endpoint's origin, `/_matrix/push/v1/notify`, over
the phone's own network.

| Outcome | Element X then uses |
|---|---|
| 200 with `unifiedpush.gateway == "matrix"` | our URL |
| 401, 403, 404, 405 or 406 | the public `https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify` |
| any other error | its stored gateway, else that public one |

With a public origin set we answer 200, so it uses ours. If our name is unreachable from the phone at that moment
(a DNS failure, a captive portal), Element X falls back to unifiedpush.org's gateway. That gateway re-posts to our
`/up/` endpoint (common-proxies `gateway/matrix.go`), so delivery still works, with a third party in the path. R2's
`outside_gateway_at` sees such a body (Matrix JSON arriving at `/up/` directly) and warns the parent. The fix is to
toggle the provider in Element X, which re-runs the discovery.

### 6.3 No fallback, and with the F-Droid build no Google path at all (A3)

The F-Droid build has no Firebase provider. So whenever Vibb is not a working distributor, Element X has **no push at
all**:
- the phone's switch is off;
- the app is stopped;
- no public origin is set (or it was cleared);
- Vibb is uninstalled.

Element X sees `UNREGISTERED` or `NoDistributorsAvailable`. It shows that in its own notification settings, and it
may notify the kid that push doesn't work. It syncs only while it is open.

- **On Stop,** Element X re-registers by itself and is refused (`ACTION_REQUIRED`). That is expected, and a gate row
  checks it (§12.3, row 13).
- **The PWA's sheets say so** (§3.10): the switch's off sheet and Stop's confirm sheet.
- **The "no push" warning is in R1:** an allowed known UP app with no registration while UP is on.
- **The fix is always on our side:** turn the switch on, allow the app again, or set the origin. Element X needs no
  change on the phone, and re-registers at its next start.

### 6.4 The message format and decryption

- **What arrives:** Element X sets an `EVENT_ID_ONLY` pusher with `data {"cs": "<client secret>"}`. Our gateway queues
  the notify JSON **minimised** (Q8, decided by the design QA and the coordinator; the user can overrule): without
  `notification.content`, `sender_display_name`, `room_name` and `room_alias`. An `event_id_only` pusher sends none of
  these anyway; a full-format pusher of another client would lose them, and that client then fetches the event
  itself.
- **What Element X reads:** `UnifiedPushParser` reads only `notification.event_id`, `room_id`, `counts.unread` and
  `prio`, and finds its session by the registration's instance (= the client secret).
- **Decryption:** the connector tries to decrypt every message (`MessagingReceiver.kt:214-219`). For our plaintext
  JSON it fails, logs a warning, and hands the bytes on with `decrypted = false`, which Element X never checks.
  - This is how every Matrix gateway works with UP (ntfy, common-proxies).
  - The gateway can't encrypt: the app's Web Push keys are made by its connector and given to its app server, never to
    the distributor or a gateway.

### 6.5 The MXID in the REGISTER `message`

Element X registers with `messageForDistributor = sessionId` (`RegisterUnifiedPushUseCase.kt`), for example
`@emma:example.org`.

- **The launcher keeps it on the phone only:** in its sealed store, shown in the PIN-gated Settings' diagnostics row
  ("Element X · @emma:example.org · sist levert 14:02").
- **It is never sent to the server,** so the PWA says "Element X · 2 kontoer" and can't tell accounts apart (Q7).

### 6.6 Switching Element X to Vibb One, once per session (the amendment; was the F-Droid runbook, A2)

**Since the amendment (the user, 2026-11-16) Element X's Play build stays.** No reinstall, no catalog row, no
recovery-key step. After R1 is released and the phone's App push is on:

1. In Element X on the kid's phone, for **each account** (session): Settings › Notifications › Advanced › Push
   provider › **Vibb One** (the picker shows, because Firebase and Vibb One are both offered, §6.1).
2. Element X registers with Vibb One, runs its discovery against our origin (§6.2) and sets its pusher. Its
   Settings › Notifications › Troubleshoot names Vibb One, and the PWA's App push row shows "Element X · 1 konto".
3. **The way back** is the same setting: Push provider › Google (Firebase). While Vibb's push is off, stopped or has no
   origin, a session left on Vibb One gets no push until it is switched back or Vibb is on again (§6.3).

A new session (a new login) starts on Firebase again and needs the switch too; the PWA's "isn't using Vibb" warning
(§3.10) shows while an allowed Element X has no registration. Element X stays a Jelly Star check; R1's gate uses the
UP example app (§12.3).

**What follows was revision 2's runbook, out since the amendment, kept as the record:**

The Play build can't be updated into the F-Droid build, because the signers differ: Play App Signing's key against
Element's own release key. The catalog also refuses to pin an app that a phone's last report shows installed by
another installer (`tracked_apps::foreign_on_a_phone`, `server/src/handlers/tracked_apps.rs`). So the switch is a
parent's runbook, done once per phone after R1 is released:

1. **Back up the recovery key.** In Element X: Settings › Encryption, for every account on the phone. The new install
   is a new session and needs it to read old encrypted messages.
2. **Uninstall the Play build,** during "Pause all restrictions": Android's Settings › Apps › Element X › Uninstall.
3. **Wait for the phone's next status report.** The PWA's Device › Apps no longer lists Element X from Play, so
   `foreign_on_a_phone` stops refusing.
4. **Add the catalog row:** GitHub `element-hq/element-x-android`, the asset `app-fdroid-arm64-v8a-release-signed.apk`,
   prereleases **off**. Tick it for the phone.
5. **Let it install** (the catalog's own rules: Wi-Fi, the night window or Install Now). **Then log in, verify with the
   recovery key,** and run Settings › Notifications › Troubleshoot. The push line names Vibb One, and the PWA's App
   push row shows "Element X · 1 konto".

**The APK comes from Element's GitHub releases only, never from F-Droid's own repository.** F-Droid signs its builds
with its own key, so an F-Droid-repository copy would never update over the GitHub one, nor the other way round.

**Testing:** Element X stays a Jelly Star check. The arm64 asset doesn't run on the x86_64 AVDs, and R1's gate uses
the UP example app instead (§12.3).

---

## 7. Other apps, and the adaptations each needs

The user asked (2026-11-16) whether apps other than Matrix need special adaptations, the way Matrix needs the gateway.
**Short answer: no.** Matrix is the only protocol whose servers can't post to a push endpoint themselves.
unifiedpush.org's own gateway page says so: "Right now, the only gateway that exists is for the Matrix protocol"
(`documentation/content/users/gateway.md:16`). Every other app's server posts Web Push to the endpoint directly. The
only other adaptation that exists is a legacy encoding fold (§7.2), and it is not needed.

### 7.1 The adaptations

| Adaptation | Who needs it | Our work | When |
|---|---|---|---|
| **The Matrix gateway** (`/_matrix/push/v1/notify`, GET discovery, POST) | Element X, SchildiChat Next, FluffyChat, Commet, Mages, Tammy, polycule: homeservers speak the Matrix push-gateway API, not Web Push | the port of ntfy's `server_matrix.go` (§3.3) | **R1** (the first step is Element X) |
| **Generic RFC 8030 Web Push** (`Content-Encoding: aes128gcm`, RFC 8291) | Mastodon clients on a Mastodon server that sends standard Web Push (Tusky, Pachli, Fedilab, Moshidon/Megalodon), DAVx⁵ (WebDAV-Push), Nextcloud Talk, Goguma (soju), Jami (its DHT proxy), Lichess, FOSS Warn, the UP example app | none beyond the endpoint: `/up/{secret}` takes the bytes and the app's connector decrypts | **R1** (the same route is Element X's fallback through the public gateway, and the gate's example app uses it) |
| **VAPID check** (RFC 8292, `Authorization: vapid t=…, k=…`) | apps that pass a `vapid` key at REGISTER (DAVx⁵'s WebDAV-Push servers sign; some Mastodon clients may pass the server's key) | verify only when a key was registered; never required, so no `VAPID_REQUIRED` | **R3**: accepting unsigned or unverified pushes is what ntfy does today, so R1 loses nothing |
| **The legacy `aesgcm` fold** (Web Push draft 4 / ECE draft 3: `Content-Encoding: aesgcm`, the salt in `Encryption:` and the key in `Crypto-Key:` *headers*) | a server that still sends draft-era Web Push: Mastodon before 4.4, and 4.4+ for a client that doesn't ask for standard Web Push | **skip** (§7.2) | not planned |
| **Its own server in the path** | Molly (MollySocket), Amethyst (its push relay), IronFox/Fennec (Mozilla's autopush), Delta Chat forks (a chatmail notifier) | none: their middle server posts generic Web Push to our endpoint | works with R1 when that server can reach our origin (Molly, §7.3) |
| No UP at all | Tuta, Thunderbird/K-9 | - | - |

### 7.2 The `aesgcm` fold: skip it

UP delivers only bytes. A draft-era `aesgcm` push carries its salt and its key in the `Encryption` and `Crypto-Key`
headers, so the body alone can't be decrypted.

- **What common-proxies does:** its aesgcm gateway (`gateway/aesgcm.go`, MIT, about 70 lines) folds the headers into
  the body as `"aesgcm\nEncryption: …\nCrypto-Key: …\n" + body`. It is reached at `<gateway>/aesgcm?e=<endpoint>`
  after a discovery that answers `{"unifiedpush":{"gateway":"aesgcm"}}`.
- **Why we skip it:**
  - **Nothing on the phone decrypts the folded form.** The Android connector decrypts only aes128gcm
    (`WebPushKeysEntries23.kt`; no `aesgcm` anywhere in the connector), and ntfy has no `aesgcm` handling at all. Only
    an app with its own parser could use it, and none in unifiedpush.org's list documents one.
  - **The Mastodon clients we checked against the list use a push as a wake-up.** They fetch the notifications from
    the API, so a body they can't decrypt still triggers the fetch (the connector passes it with `decrypted = false`).
    This is **to verify per client in R3**, with Tusky first.
  - **The aesgcm senders are going away.** Mastodon added standard Web Push in 4.4, to confirm against its changelog
    in R3.
- **If a real app ever needs it:** the fold is about 40 lines in `publish.rs`. On `Content-Encoding: aesgcm` with both
  headers present, prepend them to the stored body; no separate route or discovery is needed, since we are the push
  server. It would be ported from common-proxies under its MIT licence, compatible with GPL-3.0-or-later, with a
  `NOTICE.md` line.

### 7.3 Molly through the user's own MollySocket

**This needs nothing special from us.** MollySocket keeps Signal's connection on the user's server and POSTs a
wake-up to the Molly app's UP endpoint as plain Web Push to `/up/{secret}`. Two things on the user's side:

- MollySocket's allowed-endpoints setting must admit our public origin;
- Molly must be on the phone's allowlist.

Molly's install itself was removed with the journal fork on 2026-10-06. Bringing Molly back is the user's choice, not
part of this design.

### 7.4 What the first step needs

**R1 needs exactly two of these: the Matrix gateway and the generic `/up` endpoint.** Both are in R1 already.

- **VAPID** waits for R3, until an app that registers a key is on a kid's phone.
- **The aesgcm fold** waits indefinitely.
- **Molly and the other relay-backed apps** need no code.

The UP example app (`org.unifiedpush.example`, F-Droid) registers and can post an encrypted Web Push test to its own
endpoint. It is the R1 gate's generic app.

---

## 8. A phone without Google Play services

**What UP removes:** Element X's and the other UP apps' need for FCM. Today Play services and GSF are in
`neverRestrict` (`PLAY_NEVER_RESTRICT`) for exactly that, and the DNS filter keeps FCM's hosts open (`FcmHosts.kt`).

**What still needs Google on the Jelly Star (stock Android 16 with GMS):**

1. **Play Store installs and updates** (install mode, the nightly window). Apps can come from the catalog (GitHub,
   F-Droid) instead.
2. **The system WebView and Chrome.** Their updates come only through Play. The WebView is in `neverRestrict` because
   every WebView breaks without it (the music app's Spotify, design 102's sign-in window). A stock ROM accepts updates
   only signed by Google, so without Play the WebView stops getting security fixes. **This is the largest cost.**
3. **Google Play system updates (mainline modules).** These are the monthly APEX updates (Conscrypt, the DNS resolver,
   the media codecs and more), delivered through Play. Unihertz's OTA does not replace them.
4. **Network location.** The `network` provider on a GMS phone is Play services' fused/network location. Without it,
   `LocationManager` has GPS only: indoor fixes for Find My Device and the zones (design 64, design 91's Lost) get much
   worse.
5. **Android Emergency Location Service (ELS)** is part of Play services on GMS phones. Whether 112 in Norway gets
   AML/ELS from the Jelly Star is **to verify** on the phone. If it does, removing GMS removes it.
6. **Google's own apps** (Gmail, Maps, YouTube). On a managed phone most are hidden by the allowlist anyway, and
   Element X's F-Droid build needs none of them (Q4).

**Can a Device Owner switch GMS off safely?**

- **Mechanically, only in part (L11).**
  - **Play Store (`com.android.vending`) can't be hidden.** It is usually the phone's required package verifier, and
    AOSP refuses to hide the required installer, verifier, uninstaller or permission controller. It can only be
    suspended, as it is today outside install mode.
  - **Play services can't be hidden while Google's Find My Device is an active device admin.** It is one on most GMS
    phones, and AOSP refuses to hide an active admin. A Device Owner can't remove another app's admin; the user would
    have to turn it off by hand first.
  - **GSF** has neither block.
  - Where a hide is possible, it is reversible (unhide), and it survives reboots and OTAs.
- **Suspension is not "off":** a suspended Play services still runs FCM.
- **What breaks:** items 1-6 above, plus any preinstalled Google component that expects it (Google Dialer or Messages,
  if the ROM uses them; we hold the dialer and SMS roles ourselves). The Jelly Star's setup is done, so the setup
  wizard and QR provisioning no longer matter.
- **The standing rule holds:** such a switch would be a restriction like any other, lifted by the pause and the parent
  code.
- **Decided (Q9, the user): no** "GMS off" on the Jelly Star. Items 2 and 3 would trade one dependency for unpatched
  security components on a kid's phone. With UP built, the Jelly Star keeps GMS but no app *needs* FCM. R4 is dropped.

**GrapheneOS** is the real Google-free target:

- no GMS, so the Play build of Element X picks UP and Vibb One by itself;
- the WebView (Vanadium) is updated through GrapheneOS's own app repository;
- an opt-in network location of its own.

It is still **blocked on QR provisioning** (PLAN.md, "Vibb on GrapheneOS": no QR step in SetupWizard2, every QR PR
closed unmerged, only `adb dpm set-device-owner`). UP removes the last push question from that list. Nothing in this
design is GMS-specific, so it carries over unchanged once enrolment works.

---

## 9. The shared contract

`server/testdata/unified_push.json`, byte-identical in `launcher/app/src/test/resources/`:

- `capability`: `unified_push_v1`.
- `policy`: the `unified_push` fragment (with `origin`, M4), in the rows `off`, `on_empty`, `on_two`, `stopped` and
  `origin_changed`, and `older_server` (no key, so UP is off and the receiver disabled).
- `register`: the exact `POST /api/devices/up/registrations` body, and its `answers`: `ok` (`endpoint`), a retry
  (`ok`, the same endpoint), `off`, `stopped`, `not_allowed`, `limit`, `busy`, `conflict`, `invalid` (400). Plus
  `failed_reason`: each refusal mapped to the connector's reason (`ACTION_REQUIRED`/`NETWORK`).
- `messages`: the exact `POST /api/devices/up/messages` body with acks (random keys), and an answer with two messages
  (one minimised Matrix JSON, one binary in base64) and `more`.
- `event`: the SSE event's name and data (`up`/`up`).
- `endpoint`: the endpoint's form, `^https://[^/]+/up/[A-Za-z0-9_-]{27}$`.

**The tests:**

- **The server's `tests::unified_push::the_contract`** posts `register` and `messages` and produces every `answers`
  row and the `policy` rows from seeded data (`UNIFIED_PUSH_CONTRACT_WRITE=1` rewrites them).
- **The launcher's `UnifiedPushContractTest`** encodes `register` and `messages` exactly, decodes every answer and
  policy row, and maps each refusal to `failed_reason`.
- **Both** compare the copies.
- **Server-only:** `server/testdata/unified_push_gateway.json`, the Matrix and `/up` rows ported from ntfy's tests,
  with headers, bodies and the expected status, body and queue row: the minimised body (Q8), tombstoned against
  never-seen secrets (M5), the 404 for a Matrix-shaped body at a gone `/up/` (L1), the previous origin (M4).
- **Launcher-only:** `launcher/app/src/test/resources/up_rules.json`, the register, unregister and ack decisions,
  liveness and the delivery plan.

**The path lists:**

- `launcher.yml`'s `launcher-build` filter gains `server/testdata/unified_push.json`;
- `server-ci.yml`'s `server-build` filter gains the launcher's copy;
- the root `CLAUDE.md` gains a paragraph in its contract-file list and both path-list sentences, and
  `scripts/fake-homeserver.py` in its scripts list (L10);
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` gain `unified_push`.

---

## 10. Reversing the 2026-10-06 rule

**The rule** is in `docs/notes/launcher/gotchas-and-history.md:60` and `docs/notes/server/retention-and-crashes.md:100-106`
(and DEPLOY.md's "Removed" section): "If UnifiedPush is ever needed …, install the ntfy app as the distributor instead
of building one into the launcher again - the launcher must not export a registration receiver or hold a third-party
relay connection."

**What it guarded against,** in the removed code (`272e5f4a2^`, `UnifiedPushRelay.kt`):

- a second, always-on WebSocket to **public ntfy.sh**, which got a clean close every ~5 s (its own comment);
- a third party seeing every endpoint and push;
- an exported receiver that any app could reach, gated only by a Settings switch.

The removal went together with the conversation journal and browser history, under one principle: collect only what
managing the phone needs.

**What changes, and why it is acceptable now:**

1. **No third party, no second connection.** The pushes ride the one SSE stream to the family's own server. The
   "third-party relay connection" half of the rule stays true.
2. **The exported receiver** is the only part of the rule that is reversed (the user, 2026-11-16, Q2). With the
   per-phone switch on by default (Q3, A1), **the receiver is enabled on every managed phone once a public origin is
   saved.** Existing phones included: the `/settings/push` page says so at the Save, and the Phone Setup prefill never
   saves by itself. It is acceptable because:
   - it trusts only Android's identity (`getSentFromPackage`), read before `goAsync()`;
   - it serves only allowlisted, not-stopped apps, under caps and a churn limit;
   - it fails closed (refusals are `ACTION_REQUIRED`);
   - the parent turns it off per phone, or for all by clearing the origin, and it is disabled whenever either is off.
3. **Nothing is collected.** Bodies are held seconds to 24 h, never logged, never shown. Matrix bodies are stored
   minimised, without a message's content or a room's or sender's name (Q8). The MXID stays on the phone. The PWA shows
   app names, counts and times only. The `/up/` paths are kept out of the security log (L5). So the 2026-10-06
   principle stands.
4. **The ntfy-app route** remains possible for anyone who prefers it: with the phone's switch off, Vibb One isn't
   listed.

**When built,** R1's merge commit rewrites both notes' paragraphs (the history kept, the rule replaced by "Vibb One is
the distributor since design 108; see `docs/notes/launcher/unified-push.md`"), DEPLOY.md's section and the root
`CLAUDE.md`'s contract list. The user's "yes" to the reversal is recorded (Q2, 2026-11-16).

**Done:** R1-S rewrote the server note (`retention-and-crashes.md`), DEPLOY.md's section and the root `CLAUDE.md`; R1-L
rewrote `docs/notes/launcher/gotchas-and-history.md`'s paragraph ("Removed monitoring and upstream extras") and added
`docs/notes/launcher/unified-push.md`.

---

## 11. Licensing

**ntfy** is dual-licensed under the Apache License 2.0 and the GPLv2 (its `README.md`, "License"; `LICENSE` and
`LICENSE.GPLv2`).

- The GPLv2 option names no "or later", so it doesn't combine with our GPL-3.0-or-later.
- **The port is taken under Apache-2.0.** Apache-2.0 code may be included in a GPL-3.0 work (the FSF's compatibility
  list); the combined server is distributed under GPL-3.0-or-later, as `server/` is.
- **Apache-2.0 §4 requires:**
  - a copy of the licence;
  - the attribution kept;
  - a notice in each changed file saying it was changed;
  - any upstream `NOTICE` carried. ntfy's tree at a098f52 has none, and its Go files carry no per-file header.
- **Done as:**
  - **Each ported Rust file starts with:** "Ported from ntfy (https://github.com/binwiederhier/ntfy), `server/<file>.go`
    at commit a098f52, by Philipp C. Heckel and the ntfy contributors, licensed under the Apache License 2.0; changed
    here: rewritten in Rust for Vibb's push server (see `README.md`). Distributed as part of this server under the GNU
    GPL v3.0 or later."
  - **`server/src/unified_push/README.md`** lists each ported function, its source lines and every deviation (§3.3's
    table), in the manner of the PDU codec's `README.md`.
  - **`server/src/unified_push/Apache-2.0.txt`** is a copy of the licence text.
  - **`NOTICE.md`** gains a paragraph: "The server's UnifiedPush push server and Matrix gateway
    (`server/src/unified_push/`, design 108) are ported in part from ntfy (https://github.com/binwiederhier/ntfy),
    commit a098f52, Copyright Philipp C. Heckel and contributors, licensed under the Apache License 2.0 (ntfy is dual
    Apache-2.0/GPLv2; the Apache-2.0 option is used) - see `server/src/unified_push/Apache-2.0.txt`. Each ported file
    says so; the rest of that directory is under the GPL-3.0 (or later)."

**Shipping the notices (L8).** A port under Apache-2.0 needs the licence and the attribution in what we distribute,
not only in the repository:
- `server-release.yml`'s Package step copies `NOTICE.md` and `server/src/unified_push/Apache-2.0.txt` (as
  `LICENSES/Apache-2.0.txt`) into the tarball;
- `server/Dockerfile` copies both into the image (`/app/NOTICE.md`, `/app/LICENSES/`);
- the image smoke test (`deploy/image-smoke.sh`) checks they are there.

**The launcher's `push/up/vendor/RaiseToForeground.kt`** is adapted from UnifiedPush's android-distributor
(`distributor/src/main/java/org/unifiedpush/android/distributor/RaiseAppToForeground.kt`, commit 6daaf14,
Apache-2.0).

- It keeps the upstream package-level attribution in a header, plus the change list of §5.4.
- The launcher already ships `assets/licenses/Apache-2.0.txt` (the PDU codec's).
- `NOTICE.md` gains a sentence next to the PDU codec's paragraph.
- It is not in the MIT directories.
- The rest of `push/up/` is our own GPL-3.0-or-later code. The connector (Apache-2.0) is not a dependency: we
  implement the distributor side from the spec, and only the action and extra names are shared.

**`unifiedpush-rs`** (Apache-2.0) is not used (§3.3).

---

## 12. Steps, sizes, gates and tests

### 12.1 Rounds (variant A)

| Round | Size | What | Order |
|---|---|---|---|
| **R0** | - | done: the user's answers (§14) and the reversal (Q2), 2026-11-16 | done |
| **R1-C** | S | the contract file and its two tests, the migration (the next free number), the policy key and capability on the server side (stubbed answers): one commit both worktrees start from | 1 |
| **R1-S** (server) | M | `unified_push/`: the port (`matrix.rs` with the minimised body, `publish.rs`, `rate.rs` with the sub-bucket), `store.rs` (tombstones, one delete path), the edge (per-IP in-flight, the body-read timeout); the device API (`busy`, random keys); `up_notify`, trailing-edge coalescing and the SSE event; the origin and its change; `prune_disallowed` in every allowlist writer; the security-log redaction; retention; `/settings/push` and Device › Apps' group (switch on by default, rows, Stop, the sheets, the "no push" warning); DEPLOY.md; the notices in the tarball and image | 2, in parallel with R1-L |
| **R1-L** (launcher) | M | `UpReceiver` (read before `goAsync`), `UpLinkActivity` + the always-set PPA (+ the re-seat, `PlayInvariantsTest`), `UpStore`, `UpRules` (with the churn limit), `UpRuntime` (register, pull, ack after send, the origin rewrite, `isUserUnlocked`), `UpDelivery` + `vendor/RaiseToForeground` with H1's fixes, the uninstall/data-clear/allowlist reconcile, the policy's on/off, `origin` and `regs`, the capability, the Settings diagnostics row | 2, in parallel |
| **R1-SR** | S | the security review of R1-S's public routes (an Opus agent, own worktree) | 3, before any tag |
| **R1-G** | S | the emulator gate (§12.3); then the server tag before the launcher RC (the launcher checks the capability, so either order is safe) | 4 |
| **R1-E** | - | the per-session switch of Element X's Play build to Vibb One on the Jelly Star (§6.6, the amendment; no F-Droid reinstall), and the Jelly Star checks (§12.3, "Owed") | 5, after both are released |
| **R2** | S | the 30-day ping and unregister (§5.6), the 30-day-unseen prune, the "outside gateway" warning | after R1 is on the Jelly Star a week |
| **R3** | S | other apps: VAPID verification (`ring`, both header forms, checked against Mastodon's sender at the step), the `Urgency`-based hold if wanted (the spec's SHOULD; R1 delivers every urgency at once, a stated deviation), FluffyChat, SchildiChat Next, Tusky and DAVx⁵ tried | when the kids get such an app |
| **R4** | - | dropped: Q9 is no ("Google av" is not offered) | - |

**The smallest useful first step is R1 for Element X** (R1-C + R1-S + R1-L, with the gate, then R1-E). Nothing in R1
can be cut for Element X:

- the generic `/up` POST is about 120 lines;
- it is Element X's own fallback path through unifiedpush.org's gateway;
- and the UP example app's gate needs it.

**Variant B's rounds** (not chosen): R1-S would be replaced by R1-S' (M-: `ntfy.rs`, the config page with the sealed
token and the Test button; no edge, port or gateway), R1-SR would shrink to an S- (the outbound client, the token at
rest, the ntfy ACL recipe in DEPLOY.md), and R3 would have no VAPID work (ntfy has none).

**Total for A:** about 2 M + 4 S. Revision 2's fixes grow R1-S and R1-L inside their M (about +15% each), so it is
still the research's "~2 M rounds + a security review", plus the follow-ups.

### 12.2 Unit tests (lean)

**Server:**
- `unified_push::matrix::tests`: ntfy's 9 Matrix tests, ported.
- `unified_push::publish::tests`: the answer table of §3.4, with TTL/Urgency/Topic.
- `unified_push::rate::tests`: the phone is charged, the per-registration sub-bucket, the IP failure map is separate
  and counts never-seen secrets only, a ban never blocks a resolving secret, abandoned is 410.
- `unified_push::store::tests`: every delete path writes tombstones; tombstones prune at 30 days.
- `unified_push::matrix::tests::previous_origin`: the previous origin for 30 days, case-insensitive host, then
  rejected.
- `unified_push::matrix::tests::minimised`: `content`, `sender_display_name`, `room_name` and `room_alias` are gone,
  and the rest is byte-for-byte kept.
- `unified_push::notify::tests::trailing_edge`: two pushes 100 ms apart give two `up` sends, the second after the
  second enqueue.
- `tests::unified_push::the_contract`.
- `tests::unified_push::gateway_rows`: the server-only file.
- `tests::unified_push::allowlist_save_removes`, and `every_allowlist_writer_prunes` (the source scan, L10).
- `tests::unified_push::security_log_redacts_up_paths` (L5).
- `tests::unified_push::switch_on_by_default`: a phone made before the migration has the switch on, and `on` stays
  false until an origin is saved (A1).
- `tests::unified_push::nothing_logged`: a tracing capture with no secret, path or body.
- `tests::listeners::up_routes_only_on_the_phone_listener`.
- `retention::tests::up_expired_and_unseen`.
- `policy_json_keys_snapshot`.
- `i18n::tests` (automatic).

**Launcher:**
- `UnifiedPushContractTest`.
- `UpRulesTest`: the register, unregister and ack decisions, liveness, from `up_rules.json`.
- `UpDeliveryPlanTest`.
- `RaiseToForegroundTest`: a fake binder. `bindService` false sends at once; the timeout, `onBindingDied`,
  `onNullBinding` and `onServiceDisconnected` each flush by sending; nothing is cleared unsent; a key is reported for
  the ack only after its send (H1).
- `UpReceiverReadTest`: the request is read before `goAsync()`; unknown extras are ignored; a `pi`-only (AND_2)
  sender is ignored (M1, L4).
- `UpRuntimeLockedTest`: nothing touches the store before `isUserUnlocked` (L6).
- `UpStoreTest`: decode an old blob, `commit()`.
- `UpManifestTest`: the receiver's and the Link Activity's filters, `enabled=false`, no other newly exported receiver;
  extends `SecurityLowsTest`'s manifest pattern.
- `UpReconcileTest`: `regs` with the race rule of §2.3.
- `PolicyResponseCompatTest`.

### 12.3 The emulator gate (R1-G, lean)

**The rig:** `jelly36` (API 36) and `compat34` (API 34), plus `compat372` with `dumpsys` only for rows 3, 6 and 10. The
real launcher (debug) and server.

- **The public origin** is set to a name the host maps to the server: `https://up.test` with the server's phone
  listener behind a local Caddy with an internal CA. The *apps* never connect to it except in row 2b. The UP example
  app's own "send test" needs its CA trusted, so row 2b uses a user-CA build of the example app or is skipped. Every
  other push is posted by `scripts/fake-homeserver.py` from the host, straight to `127.0.0.1:3101` with
  `Host: up.test`.
- **`scripts/fake-homeserver.py`** (new; standard library, `--selftest`, run by `server-ci.yml`'s changes job):
  - it reads an endpoint (the argument, copied from the example app's screen with `uiautomator dump`);
  - it makes the discovery GET;
  - it POSTs Synapse-shaped notify JSON (`event_id_only`, `devices[0]` with that pushkey and `data.cs`), and Web-Push-like
    bytes to `/up/<secret>` with TTL/Urgency/Topic;
  - it prints each answer;
  - `--burst N` tests the bucket.

**The rows:**

1. **Register.** Allow the example app (PWA) and switch UP on; the app lists "Vibb One" as its distributor
   (`queryBroadcastReceivers`). Register, and see `NEW_ENDPOINT` within 2 s. The PWA row "UP-Example · 1". With UP
   off: Vibb One isn't listed.
2. **Deliver.**
   - a. The gateway POST gives 200 `{"rejected":[]}`, the example app shows the message within 3 s of the post, and
     the server's queue is empty after the ack.
   - b. (with a trusted CA) the app's own encrypted test arrives decrypted.
3. **The raise.** During the delivery, `dumpsys activity processes` shows the example app at a foreground-service
   level for about 5 s, and then not. On `compat372`: the same through `dumpsys`.
4. **Stopped, Doze and standby.**
   - `am force-stop` the app: it still gets the message;
   - `dumpsys deviceidle force-idle`: still delivered;
   - `am set-standby-bucket org.unifiedpush.example restricted`: delivered.
5. **A time rule.** Under Leggetid the app is suspended: the message is delivered, no notification shows, and it
   appears when the rule ends (`dumpsys notification`). Note whether its FGS start was refused.
6. **Remove.**
   - Uninstall the app: the server row is gone, and the next POST gives 410 / `rejected`.
   - Take it off the allowlist: the same.
   - PWA Stop: `UNREGISTERED` reaches the app, and a new register gets `REGISTRATION_FAILED(ACTION_REQUIRED)`.
   - UP off: `UNREGISTERED` to every app, and the component is disabled (`dumpsys package`).
7. **Offline.** Stop the server, then register: `REGISTRATION_FAILED(NETWORK)`. Post while the phone's stream is down;
   start the stream: the message is delivered by the reopen's pull.
8. **Limits and answers.** `--burst 200` gives 429 after about 120; 4097 bytes gives 413; an unknown secret gives 410;
   a pushkey on another host is rejected; 31 unknown secrets from one IP give 429. The phone's own sync stays
   unaffected throughout.
9. **The Link Activity.** In kiosk, the example app's "choose distributor" returns `RESULT_OK` at once, with no
   window, no chooser and no Recents entry; `dumpsys package preferred-activities` lists our PPA. **With UP off
   (L3):** the PPA is still listed, the Link Activity is disabled, the link resolves to nothing of ours, and no
   chooser shows.
10. **No message lost in the raise (H1).** Disable the example app's raise service
    (`pm disable org.unifiedpush.example/<raise service>`): a push is still delivered (sent at once, no raise). Then
    re-enable it, post three pushes, and `am force-stop` the app inside the 5 s bind: all three arrive (flushed on the
    lost binding), and none is in the next pull's ack before its broadcast ran (the launcher's debug log of acked
    keys).
11. **Trailing edge (M2).** Two pushes 100 ms apart, the phone idle: both arrive within 3 s, without the backstop.
12. **One registration flooded (M3).** `--burst 200` to the example app's endpoint while a second registration (a
    second instance of the example app) gets one push a second: the flood gets 429 after about 30, and the second
    registration's pushes all arrive.
13. **Stop with an app that re-registers by itself (A3).** PWA Stop on the example app with its "register on start"
    on: `UNREGISTERED` reaches it, its own new REGISTER gets `REGISTRATION_FAILED(ACTION_REQUIRED)` and no endpoint,
    the server makes no new row, and the PWA shows it under "Stoppet". Allow again: the next REGISTER gets an
    endpoint.
14. **Origin change (M4).** Change the origin in `/settings/push`: the example app gets `NEW_ENDPOINT` with the new
    origin; a gateway POST naming the old origin is still queued (within 30 days), one naming a third host is
    rejected.
15. **On by default (A1).** On a phone provisioned before the migration, with no origin: the receiver is disabled.
    Save an origin: it is enabled within one sync, and the example app lists Vibb One.

**Owed on the Jelly Star (not emulatable):**
- Element X's F-Droid build (arm64 only, so not on the x86_64 AVDs) end to end with the family's homeserver, after
  §6.6's runbook;
- a ring from a parent while the screen is off in Doze, and its latency compared with FCM;
- the tsnet path (the emulator's debug build has the tsnet stub);
- whether 112/AML relies on Play services (§8).

---

## 13. Upkeep

| What | Where to watch | Cadence | Effort |
|---|---|---|---|
| The UP Android spec (AND_3.1.0, last changed 2025-10) | codeberg.org/UnifiedPush/specifications | about once a year | a read, rarely code: new extras are optional, and old ones must keep working (the spec is versioned; the connector still sends AND_2's `application`/`features`) |
| The connector (3.3.5 in Element X; 33d59cb has 3.3.x) | android-connector releases, Element X's `libs.versions.toml` | each Element X release | none unless the extras change; note that the connector reads `newDistributor` where the spec names `useDistributor` (we send neither) |
| ntfy's UP logic (the port's source) | ntfy's releases and changelog: Matrix and UnifiedPush items | twice a year | a re-read of `server_matrix.go`, `server_rate_visitor.go` and the UP branch; re-port what changed (2026 brought the 410 for abandoned topics and the subscriber charging) |
| The Matrix push gateway API | spec.matrix.org push-gateway-api | stable since r0 | none expected |
| Element X's push code | `libraries/pushproviders/unifiedpush`, `DefaultPushService` | twice a year, or when push breaks | a read: the discovery, the provider order, the picker condition |
| Element X's F-Droid asset on GitHub | the catalog row's sync (a release without `app-fdroid-arm64-v8a-release-signed.apk` shows as a failed check) | each release | a renamed asset needs the row's asset name changed |
| Android 17+ (broadcasts, binds, FGS from bind) | each API's behaviour-change list | yearly | the gate's rows 3-5 on the new image |
| Mastodon's and Synapse's answer rules | their push workers | when R3 lands, then yearly | a read |

- **Variant A:** about 1-2 days a year, plus any Android-version fix.
- **Variant B:** about the same, plus keeping the user's ntfy current and its ACL and settings right. An ntfy change to
  the UP rules (as in 2026) lands on the user's server without our code changing, which is good when it fixes things
  and bad when it changes the 410 timing.

---

## 14. Decisions (the user, 2026-11-16)

| Q | Decision | Its visible cost |
|---|---|---|
| **Q1** A or B | **A**, ntfy's logic ported into the Vibb server | three new public routes on the phone listener, and a security review |
| **Q2** reverse the 2026-10-06 rule | **yes** | an exported entry point on the kid's phone, enabled once a public origin is saved (§10.2) |
| **Q3** who may register | **a per-phone switch, on by default**; when on, any allowlisted app; the switch stays | an allowed app gets push without a separate tick; existing phones flip on at the first Save of an origin (A1) |
| **Q4** Element X | **the Play build, switched to Vibb One once per session** (the amendment, 2026-11-16; was the F-Droid build) | one setting per account in Element X (§6.6); Google stays the manual way back; a session left on Vibb One gets no push while Vibb's is off (A3) |
| **Q5** during a time rule | **deliver** (as recommended) | the app syncs in the background during bedtime; notifications appear when the rule ends |
| **Q6** turning UP off or stopping an app | **allowed, with the warning** (as recommended) | with the F-Droid build that app has no push at all until it is on again, and Element X may tell the kid so (A3) |
| **Q7** the account in the PWA | **on the phone only** (as recommended) | the PWA shows "Element X · 2 kontoer" and can't say which |
| **Q8** Matrix bodies | **minimised**, decided by the design QA and the coordinator (the user can overrule) | another Matrix client using the full format shows less, and fetches the event itself |
| **Q9** "Google av" on the Jelly Star | **no** (as recommended); R4 is dropped | - |
| **Q10** the queue keeps a push | **24 h** (as recommended) | a phone off longer misses those pushes; the apps catch up when opened |
| **Q11** app push only with a public phone address | **yes** (as recommended) | a tailnet-only install gets no app push; the page says so |

---

## 15. Risks and checks owed

1. **The raise from a bind** (§5.4) is believed to work, but is not verified on a device. If Android 16 or 17 refuses
   Element X's FGS start after our bind, Element X still gets the broadcast, but may be killed mid-sync in Doze. There
   is no real fallback on our side: `FLAG_RECEIVER_FOREGROUND` grants no FGS start (L10), and a Device Owner has no
   temporary-allowlist API. The gate's rows 2-4 decide it on 34 and 36; the Jelly Star decides it for Element X.
2. **Unhiding a suspended app's notifications at the rule's end** (§5.7): per-version behaviour, checked at the gate.
3. **Element X's discovery and pusher** with the family's real homeserver (`ip_range_blacklist`, hairpin NAT): checked
   on the Jelly Star.
4. **ntfy's licence:** Apache-2.0 is chosen; the attribution text is to be confirmed against ntfy's repository at the
   commit used, when the port is made.
5. **Element X changes its provider order or picker condition:** watched (§13).
6. **The user's phone listener being public is now load-bearing for push.** An outage of the proxy or the name means
   no pushes. Senders retry 5xx and timeouts; Synapse backs off.
7. **A session switched to Vibb One has no automatic Google fallback (A3).** Any time Vibb's push is off, it has no
   push until it is switched back in Element X. The PWA's warning and sheets say so; the risk is accepted with Q4.
8. ~~The switch-over loses local history without the recovery key~~ - gone with the amendment: no reinstall.

---

## 16. Design QA: how each finding was handled

The design QA of revision 1 (2026-11-16, relayed by the coordinator with the user's answers). Every finding is fixed
in the section named.

| Finding | What it said | The fix | Where |
|---|---|---|---|
| **A1** | Q3 "on" removes "disabled until opted in" | the column defaults to 1 and existing phones flip on; the receiver is enabled on every managed phone once an origin is saved; `/settings/push` says that Save turns app push on for all phones; the prefill never saves by itself | §3.1, §3.5, §5.1, §10.2, Q2/Q3 in §14, gate row 15 |
| **A2** | the catalog refuses to pin over the Play build (`foreign_on_a_phone`), and the signers differ | the runbook: back up the recovery key, uninstall the Play build, wait for the report, add the row (asset, prereleases off), install, log in, verify; GitHub only, never F-Droid's signer; Element X stays a Jelly Star check | §6.6, §12.1 (R1-E), §12.3 |
| **A3** | with the F-Droid build, off, Stop or no origin means no push at all | the sheets rewritten; Q6's cost; Element X re-registers on Stop and may notify the kid; the "no push" warning moved into R1 | §2.3, §3.10, §6.3, §12.1, §14, gate row 13 |
| **H1** | the vendored RaiseAppToForeground loses messages | `bindService == false` sends at once; every unbind path flushes by sending, never clears; a key enters the ack only after its `sendBroadcast` ran | §2.2, §5.4, §5.5, `RaiseToForegroundTest`, gate row 10 |
| **M1** | identity read after `goAsync()` | `getSentFromPackage()` and the extras read synchronously first | §5.1, `UpReceiverReadTest` |
| **M2** | coalescing could strand the last push; Lagged sent both kinds | trailing-edge coalescing per device at the sender; a lag on `up` sends only `up` | §3.9, `trailing_edge`, gate row 11 |
| **M3** | one registration could exhaust the phone's bucket | a per-registration sub-bucket (30 burst, one per 2 s) under the phone's | §3.3, §3.4, gate row 12 |
| **M4** | an origin change strands every endpoint | the policy carries `origin`; the phone rewrites its endpoints and sends `NEW_ENDPOINT`; the gateway resolves by the secret and accepts the current and previous origin (case-insensitive host) for 30 days | §2.3, §3.1, §3.3, §3.8, §5.5, gate row 14 |
| **M5** | gone secrets counted toward the ban; a ban could block a real sender | 30-day tombstones answered 410/rejected without counting; only never-seen secrets count; a ban never blocks a resolving secret | §2.3, §3.4, §3.5 |
| **M6** | one client could hold every slot; slow bodies | a per-IP in-flight cap of 4 and a 3 s body-read timeout | §3.2, §3.11 |
| **L1** | common-proxies rejects only on 401-404, so a 410 at `/up/` leaves the homeserver pushing | a gone secret with a Matrix-shaped body at `/up/` gets 404; 410 otherwise | §3.4 |
| **L2** | the capability gate on `up` is unneeded; design 91's stream | no gate on `up`; `/locked/stream` must not merge `up_notify` | §3.7, §3.9, §5.3 |
| **L3** | the PPA should follow the captive-portal pattern; the PPA count was wrong | always set in `apply()` and the re-seat, the component's enabled state decides; the fifth of ours; `PlayInvariantsTest` checked | §5.2, gate row 9 |
| **L4** | the `pi`/AND_2 refusal and unknown extras | refusing `pi` and AND_2 is stated as a deliberate deviation; unknown extras are ignored | §5.1 |
| **L5** | `/up/*` paths could reach the security log | redacted before storing (`admin_refused`, `device_auth_failed`, `audit::clip` callers); the ban record is path-free | §3.5, §10.3 |
| **L6** | the main process starts before the first unlock | `UpRuntime` checks `isUserUnlocked` before touching its store | §5.3, `UpRuntimeLockedTest` |
| **L7** | Funnel names are `*.ts.net`; option (b) | the prefill excludes only tailnet IPs and non-Funnel ports; option (b) only with `DEVICE_BIND_ADDR` | §2.4, §3.1, §3.12 |
| **L8** | the notices must ship | `NOTICE.md` and the Apache-2.0 text in the tarball and the image, checked by the smoke test | §11 |
| **L9** | 0151 is taken | "the next free number at build time" | §3.5, §12.1 |
| **L10** | random ids; register churn; every allowlist writer; the scripts list; `FLAG_RECEIVER_FOREGROUND` | random keys for `Location` and MESSAGE; 20 new registrations an hour per app (`busy`); `prune_disallowed` in every writer with a source scan; `fake-homeserver.py` in the root `CLAUDE.md`; `FLAG_RECEIVER_FOREGROUND` named as no fallback | §2.1, §3.4, §3.5, §3.7, §3.8, §5.1, §5.4, §9, §15 |
| **L11** | the Play Store and Play services can't simply be hidden | the Play Store is the required verifier and can't be hidden; Play services can't while Find My Device is an active admin | §8 |
| **Gate** | a row for Stop with an app that re-registers by itself | added | §12.3, row 13 |

### The security review of R1-S (R1-SR, 2026-11-17: 0 H, 3 M, 9 L), fixed on `up`

| Finding | What it said | The fix |
|---|---|---|
| **M1** | slow bodies held every global slot | the edge takes the per-client cap and bucket, then the body (its own bound of 256 reads), and only then one of the 32 handler slots; DEPLOY.md's Caddy site `request_buffers 8KiB` |
| **M2** | `prune_disallowed` on another pool connection would block inside a `BEGIN IMMEDIATE` writer (design 109) | it takes the writer's connection and runs before its commit; the nudge after; `prune_disallowed_now` for a writer without a transaction; the note says how |
| **M3** | the queue's caps untested | `tests::unified_push::the_queue_caps_and_ttl_zero`: 101 rows, the 1 MiB phone cap, 507, TTL 0 closed and open |
| **L1** | `outside_gateway_at` written for any push | only after a queued push, at most once a minute |
| **L2** | battery drain from a steady flood | the phone's sustained rate one per 5 s (burst 120 kept); Device › Apps' "Many pushes" line from 300 a day |
| **L3** | a lookup for every request | a per-client request bucket (40, 20 a second) at the edge, before the database |
| **L4** | registration races | checks and insert in one `BEGIN IMMEDIATE`; the churn check and count under one lock |
| **L5** | no per-phone churn cap | 60 new registrations an hour per phone, across its apps (`busy`) |
| **L6** | a deny-list of Matrix keys | an allow-list (`matrix::KEPT`, `KEPT_DEVICE`); the pushkey dropped (Element X reads only `event_id`, `room_id`, `counts`, `prio`); the contract's Matrix body changed in both copies |
| **L7** | a second origin change dropped the first | `up_previous_origins`: every replaced origin until its own 30 days |
| **L8** | DEPLOY.md's listener table, the footer's wording | the push routes named; "No Matrix message text is kept; app pushes are held at most 24 hours" |
| **L9** | ban-map eviction | a client that isn't banned is evicted first |

