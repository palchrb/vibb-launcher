# Design 108b: App push R2 and R3 (liveness, the outside gateway, VAPID, urgency, other apps)

Status: design, **revision 2**, 2026-11-17 (branch `up-r2r3`). Revision 1 was ee101b19c; its design QA is
`docs/review/qa-108b-design.md` (2fa7a16df: 1 H, 4 M, 12 L), and §15 maps every finding to its fix. **Not built** (R23-C, the contract commit, is the first step; the user answered Q1-Q3 on 2026-11-17, §13).
Builds on design 108 (R1-C, R1-S, R1-L and R1-G built and merged; `docs/design/108-unified-push.md`, the notes
`docs/notes/server/unified-push.md` and `docs/notes/launcher/unified-push.md`). The user approved building R2 and R3 now
(2026-11-17: "jeg vil at du bygger r2 og r3 likevel. vi kan heller installere en app for å teste at det funker, feks
fluffy"). §13 has three questions for the user. **The contract (§7) is the same whatever they answer**: the policy key
`refresh` carries both reasons, and a "no" only means the server never lists one of them.

**What revision 2 changed, in short:**
- **High:** a REGISTER without a key never drops a stored key, and a key update is answered from the store at once, the
  new key posted in the background (H1, §4.6).
- **Medium:**
  - every `NEW_ENDPOINT` that isn't the answer to a REGISTER goes through the raise, and the heal waits until the phone
    is in use (M1, §3.3);
  - Q3 re-framed with four options and the costs stated correctly; one policy key `refresh: [{reg, why}]` serves the
    outside gateway and a VAPID refusal (M2, §4.4, §13);
  - `exp` accepted up to 25 h ahead (M3);
  - at most one key-update call an hour per token (M4).
- **Low:** L1-L12 in §15. The visible changes: one warning text without per-app fixes (L1); 401 for a missing
  signature and 403 for a bad one (L5); the server releases first (L8); the gate backdates the store through a debug
  hook instead of moving the clock (L11).

**Sources read for this note** (scratchpad clones of 2026-11-17):

| Source | Commit | Date | What was read |
|---|---|---|---|
| UnifiedPush `specifications` | 0c5301f | 2025-10-16 | `android.md`: the ping and unregister (flow 6-7, `UNREGISTERED`), `vapid`, the urgency table |
| `android-connector` | tags 3.0.9, 3.1.2 (6948ff3), 3.3.5 | 2025-10 | `UnifiedPush.register` (identity, `vapid`), `MessagingReceiver` (NEW_ENDPOINT, REGISTRATION_FAILED, MESSAGE, MESSAGE_ACK), `resolveDefaultDistributor` |
| FluffyChat | 6a0fda5 | 2026-11-15 | `lib/utils/background_push.dart`, `pubspec.lock`, `settings_notifications_view.dart`, `release.yaml` |
| pub.dev `unifiedpush` 6.2.0, `unifiedpush_android` 3.4.1, `unifiedpush_ui` 0.2.0 | - | - | the Flutter plugin's `register`, its connector dependency, `registerAppWithDialog` |
| element-x-android | 739f0b4 | 2026-11-17 | `VectorUnifiedPushMessagingReceiver.onNewEndpoint`/`onUnregistered`, `RegisterUnifiedPushUseCase` |
| Fedilab | 1dfcb7f | 2026-10-03 | `PushHelper.java`, `NotificationsVM.pushSubscription`, `PushServiceImpl`, `app/build.gradle` |
| Mastodon (server) | 43126b2 | 2026-11-16 | `app/lib/web_push_request.rb`, `app/workers/web/push_notification_worker.rb` |
| mastodon-android | 9ae49ec | 2026-11-11 | `PushSubscriptionManager` (no UnifiedPush) |
| DAVx⁵ OSE | 309e6be | 2026-11-16 | `PushRegistrationManager`, `PushDistributorManager` |
| Pachli | e88ad9e | 2026-11-13 | `EnableAllNotificationsUseCase`, `libs.versions.toml` |
| Tusky | 43ae0bb | 2025-05-23 | `NotificationService`, `libs.versions.toml` |

---

## 0. Summary

- **R2-a, liveness (launcher only).** A registration whose app hasn't acknowledged anything for 23 days gets the spec's
  ping (`NEW_ENDPOINT`, the same endpoint, a new id). Unanswered 7 more days, it is unregistered (`UNREGISTERED`, a
  `DELETE`). The rows already carry `lastAppAckMs`, `pingId` and `pingSentMs`, and the ack path already clears the ping
  (R1-L). No contract change. (Q1.)
- **R2-b, the outside gateway (both).** The server already notes `outside_gateway_at` when a Matrix body arrives at
  `/up/` (an outside gateway, usually unifiedpush.org's, re-posting). R2 ends the mark when a push comes through our own
  gateway again (migration **0153**: `gateway_at`), lists the marked registrations in the policy
  (`unified_push.refresh`, reason `outside`), and **the phone asks the app to check again by itself**: once a day while
  marked, **when the phone is in use**, it sends the app `NEW_ENDPOINT` **through the raise**. That makes Element X and
  FluffyChat run their gateway discovery again and re-set their pusher (both checked in their sources, §3.3). The PWA
  warns on the app's row.
- **R3-a, VAPID (both, mostly server).** A registration made with a `vapid` key only takes pushes signed with that key
  (RFC 8292). The check reads the RFC form `Authorization: vapid t=…,k=…` and the older `WebPush` + `Crypto-Key`. It is
  never required: a registration without a key takes any push, as today. The check runs before the phone's bucket, so a
  leaked endpoint without the key can't wake the phone. The gateway refuses a keyed registration, since it would
  otherwise bypass the check. The key is checked as a P-256 point at registration. An app that re-registers with
  **another** key updates it, answered at once from the store; a REGISTER **without** a key keeps the stored one (H1).
  How a refused push is answered is Q3.
- **R3-b, Urgency.** R1 delivers every urgency at once. The spec's SHOULD would hold `very-low` and `low` until Wi-Fi or
  a charger, and `normal` on a low battery. **Recommended: keep delivering everything at once** (Q2).
- **R3-c, other apps.**
  - **FluffyChat works with R1 as it is** and picks Vibb One by itself (§6.1): the user can test it now.
  - The VAPID path's real app is **Fedilab with a Mastodon 4.4+ account**. Fedilab passes the instance's VAPID key and
    subscribes with standard Web Push, and Mastodon signs every push (§6.2).
  - DAVx⁵ is a second VAPID app: it registers only with a key.
  - Tusky can't register at all (an AND_2 connector).
- **Size:** a contract commit (XS), server S, launcher S+, the gate S, then each side's code QA. **Migration 0153.**
  **The server releases first** (L8).

---

## 1. What R1 left, and what is already there

| Item (design 108) | Already built in R1 | R2/R3 adds |
|---|---|---|
| §5.6 the 30-day rule | `UpRow.lastAppAckMs`, `pingId`, `pingSentMs`; `UpEngine.ack` records an ack (hourly) and clears the ping when it answers it | `UpRules.liveness`, run in `policyAccepted`; the note |
| the 30-day-unseen prune | built in R1 (`store::prune`, `devices.last_seen_at`) | nothing |
| §3.10 R2's warning | `up_registrations.outside_gateway_at`, noted for a queued Matrix-shaped `/up/` push at most once a minute (R1-SR L1) | the mark's end (`gateway_at`), `unified_push.refresh`, the phone's raised `NEW_ENDPOINT`, the PWA line |
| §3.4 VAPID row (R3) | the phone passes a valid-form `vapid` (87 base64url) and the server stores it (`up_registrations.vapid`) | the check, the point check, the key update, the gateway refusal, the PWA lines |
| §12.1 the Urgency hold | `urgency` stored per message and sent to the phone (`UpWireMessage.urgency`), unused | Q2 |
| §7 other apps | the generic `/up/` and the Matrix gateway | findings (§6), FluffyChat in `KNOWN_UP_APPS` |

---

## 2. R2-a: liveness, the 30-day rule (launcher)

The spec (`android.md`, flow 6-7 and `UNREGISTERED`): a distributor unregisters a connection that "hasn't acknowledge[d]
any message nor ping since 30 days", and "MUST send a ping with NEW_ENDPOINT before, to avoid unregistering a valid
connection".

**What makes a registration dead on our phones.** Not a stopped, suspended or force-stopped app: R1's broadcasts carry
`FLAG_INCLUDE_STOPPED_PACKAGES`, a suspended app still gets them (R1-G row 5), and the connector acknowledges a
`NEW_ENDPOINT` and a `MESSAGE` by itself once the app's callback returns (`MessagingReceiver.mayAcknowledgeMessage`,
3.0.9, 3.1.2 and 3.3.5). What remains is a token the app no longer knows. The connector then returns before any ack
(`getInstance(token) ?: return`). That happens when:
- Element X is switched back to Google for a session, or a session logs out, without an `UNREGISTER` reaching us;
- the app's data is restored or reset in a way `PACKAGE_DATA_CLEARED` doesn't cover;
- an app is hidden for a reason other than the allowlist. It gets no broadcast, and R1-L's L5 already drops it on
  delivery.

Such a row costs a live public endpoint and the PWA's "No delivery in 7 days" line. Its pushes don't queue: they are
broadcast, and acknowledged to the server once the send went out (L12).

**The rule** (pure `UpRules.liveness(rows, nowMs)`, rows in `up_rules.json`):

- only confirmed rows; `since = max(lastAppAckMs ?: 0, confirmedAtMs ?: createdAtMs)`;
- no ping out and `now - since >= 23 days`: **ping** - `NEW_ENDPOINT(token, endpoint, id = new)`, the row gets
  `pingId` and `pingSentMs`;
- **A ping still outstanding at a boot is re-sent once after that boot (Q1 refinement).** The same ping id, once per
  boot (`pingBootResent`, cleared by an ack; it does not move `pingSentMs`'s first stamp, only `pingLastSendMs`). A phone
  switched off between a ping and its answer never loses a live app.
- a ping out, **both** `now - pingSentMs >= 7 days` (the first ping) **and** `now - pingLastSendMs >= 1 hour` (the last
  send, the first or the boot's re-send): **dead** - `UNREGISTERED(token)`, the row removed, its `DELETE` queued
  (R1's `remove` path; the server tombstones the secret, so the app server gets 410 or `rejected`), and a diagnostics
  note `NOTE_UNANSWERED` for the PIN-gated Settings row;
- any `MESSAGE_ACK` from the app (a message's or the ping's) clears the ping (R1's `UpEngine.ack` already does).
- **Stamps from a clock that ran ahead (L3).** Every stamp the rules read (`lastAppAckMs`, `pingSentMs`, `refreshMs`,
  `keyTryMs`) that lies more than a day after now is read as now. `UpEngine.ack`'s hourly skip uses the same reading.
  Otherwise a negative age would freeze the row. A clock moved forward only pings early, and a live app answers within
  a second.
- **One `NEW_ENDPOINT` per row per pass (L2).** A row that a rewrite, a ping and a refresh (§3.3) all want gets one.
  When the ping is due, that one carries the ping's id and sets `pingId`/`pingSentMs`, and a refresh due at the same
  time counts as done (`refreshMs`). Only a key-update answer (§4.6) sends a `NEW_ENDPOINT` outside these passes.

**How it is sent.** A ping goes **through the raise** like every `NEW_ENDPOINT` that doesn't answer a REGISTER (§3.3,
M1). The connector's ack doesn't need the raise. The app's own `onNewEndpoint` work may need it, though: FluffyChat
starts a foreground service for it.

**Where it runs:** `UpEngine.policyAccepted`, after the reconcile and the origin rewrite, on the kept rows. That is
every accepted fresh policy, so at least every 30 minutes (the backstop). No new alarm or worker. Nothing runs before
the first unlock or while UP is off (R1's guards). The ping doesn't wait for the phone to be in use: its answer is
the connector's own.

**The ack path needs no other change** (checked in `UpEngine.ack`). The in-memory "a token read within the hour is
skipped" can't swallow a ping's answer: a ping is sent only after 23 days without an ack, so the token has no recent
read.

**Why the server isn't involved:** only the phone sees the app's acks. The server sees the `DELETE` as for any other
unregistration, and the PWA's row loses an account or disappears. The phone's PIN-gated "App push" row shows the note:
- nb: "%1$s svarte ikke på 30 dager; registreringen ble fjernet";
- en: "%1$s didn't answer for 30 days; its registration was removed".

If the app is in fact alive, it registers anew at its next start. FluffyChat registers at every start (§6.1). Element X
re-registers at once on `UNREGISTERED` when it still knows the token (`onUnregistered` → `rotateRegistration`).

---

## 3. R2-b: the outside gateway

### 3.1 How it happens

Element X and FluffyChat run a gateway discovery against the endpoint's origin (`GET /_matrix/push/v1/notify`)
whenever they get a `NEW_ENDPOINT`. The discovery can fail: no network at that moment, a captive portal, DNS, our
server briefly down.
- Element X then keeps its stored gateway, else uses `https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify`
  (design 108 §6.2).
- FluffyChat takes unifiedpush.org's on any error (`_newUpEndpoint`'s `catch`).

That outside gateway re-posts the Matrix JSON to our `/up/<secret>`. Delivery still works, but a third party is in the
path: it sees the event and room ids, the unread count and the pushkey. R1 already notes such a push
(`outside_gateway_at`).

### 3.2 The mark (server)

- **Migration 0153** adds `up_registrations.gateway_at TEXT`: the last push queued through **our own** gateway
  (`matrix::notify`). It is written as `outside_gateway_at` is: only for a queued push, at most once a minute.
- **Marked** = `outside_gateway_at` within the last 7 days **and** (`gateway_at IS NULL` or `gateway_at <
  outside_gateway_at`). So the mark ends at the first push through our gateway, and fades by itself after 7 quiet
  days. A homeserver that still holds both pushers (one per gateway) flips the mark with each push. The PWA shows the
  mark as it stands when the page is rendered, which is fine for a warning.

### 3.3 The heal (phone): a raised `NEW_ENDPOINT` once a day while marked, when the phone is in use

**Checked in the sources:** a `NEW_ENDPOINT` re-runs the discovery and re-sets the pusher in both apps.
- **Element X:** `VectorUnifiedPushMessagingReceiver.onNewEndpoint` launches a coroutine. The coroutine calls
  `unifiedPushGatewayResolver.getGateway(endpoint)`, stores the gateway and calls
  `pusherSubscriber.registerPusher(client, endpoint, gateway)` (739f0b4, lines 92-114). The pusher's `app_id` and
  `pushkey` are unchanged, so the homeserver replaces the old pusher's gateway URL.
- **FluffyChat:** `_newUpEndpoint` makes the discovery GET and calls `setupPusher` for every client. `setupPusher`
  deletes the app id's pushers and posts the new one.

**The work happens after the broadcast (M1).** In both apps the discovery and the pusher call run after `onReceive`
returned, in the background. In Doze or App Standby a non-exempt app has no network, and on 14+ the cached-apps freezer
stops it seconds later. A plain broadcast at a backstop sync, which mostly falls while the phone lies idle, would mostly
fail. So:

- **Through the raise.** Every `NEW_ENDPOINT` that doesn't answer a REGISTER goes through R1's raise
  (`RAISE_TO_FOREGROUND`, 5 s at the foreground-service level): the ping (§2), this refresh, and R1's origin rewrite,
  which has the same exposure. All connectors from 3.0 declare the service; FluffyChat's 3.1.2 does too.
  - `RaiseToForeground.Message` gains a kind: `MESSAGE` or `NEW_ENDPOINT` with its endpoint. Its change list says so.
  - A `NEW_ENDPOINT` kind never enters the pull's ack (`UpEngine.sent` ignores it: it isn't a server message).
  - An app without the service is sent the broadcast plainly, as R1 does for a message.
- **Only while the phone is in use.** A refresh is sent only while `PowerManager.isInteractive` and not
  `isDeviceIdleMode`. A refresh that falls due otherwise waits. `UpRuntime` registers its own receiver for
  `ACTION_USER_PRESENT` in the anchor's process (context-registered: manifest receivers don't get it). At the next
  unlock, or the next accepted policy while the phone is in use, the refreshes due are sent. The pings and the rewrite
  don't wait: their acks are the connector's.

**The rest of the rule:**
- **The policy** gains `unified_push.refresh`: `[{reg, why}]`, sorted by `reg`; `why` is `outside` here (and
  `refused`, §4.4, if Q3 takes (d)). Every listed `reg` is one of `regs`; the launcher ignores a `reg` it doesn't hold
  (L12).
- **The launcher** (pure `UpRules.refreshes(rows, refresh, nowMs, inUse)`): a confirmed row listed in `refresh`, not
  already sent a `NEW_ENDPOINT` in this pass (L2), whose `UpRow.refreshMs` is null or 24 h old, while `inUse`. It sends
  `NEW_ENDPOINT(token, endpoint, id = new)` through the raise and sets `refreshMs`.
- **Once a day at most**, counted from an attempt actually made. The mark stays up to 7 days after the last outside
  push, and an app whose discovery keeps failing is asked once a day, not at every sync.
- The app's ack of that `NEW_ENDPOINT` also counts for liveness (§2).
- A pending row is never refreshed. An older server (no `refresh`) means none.
- **A visible side effect (L12):** FluffyChat starts its Flutter engine with a foreground service
  (`background_push`) for an `onNewEndpoint` when it isn't running. That may show its notification briefly: at most
  once a day while marked, and once every 23 days for a ping.

Whether the heal reaches our server from a real phone (FluffyChat's discovery GET counted in `last_discovery_at`) is
owed on the Jelly Star (§11). The probe isn't a Matrix app.

### 3.4 The PWA

The app's row in Device › Apps › App push gets a warning line while any of its registrations is marked. It is one text
for every app (L1): every fix an app offers (FluffyChat's restart, Element X's provider switch) ends in the same
discovery the heal already asks for, so a mark that survives the heals means the discovery itself fails from the phone.

- nb: "Varslene til {app} går gjennom en ekstern tjeneste (som regel unifiedpush.org), utenfor familiens server. Vibb
  One ber {app} sjekke adressen på nytt når telefonen er i bruk. Blir dette stående i flere dager, når ikke telefonen
  adressen: se «Sist sett utenfra» under Innstillinger › Push for apper."
- en: "{app}'s notifications go through an outside gateway (usually unifiedpush.org), outside the family's server. Vibb
  One asks {app} to check the address again when the phone is in use. If this stays for days, the phone can't reach
  the address: see “Last seen from outside” in Settings › App push."

No save on this page changes, so nothing can jump.

---

## 4. R3-a: VAPID (RFC 8292)

### 4.1 When it applies

Only to a registration made with a `vapid` key: the app passed it at REGISTER, and the phone passes it on (R1). Without
a key nothing changes: unsigned and signed pushes are taken, as ntfy does. **Never required** (no `VAPID_REQUIRED`):
Element X, FluffyChat and Pachli register without a key.

### 4.2 The header forms

Read in Mastodon's sender (43126b2, `WebPushRequest`, `Web::PushNotificationWorker`), the most likely real sender:

| Form | Headers | Who |
|---|---|---|
| RFC 8292 | `Authorization: vapid t=<jwt>,k=<key>`. Mastodon writes **no space** after the comma, others write one, the order may differ, and values may be quoted (auth-param syntax) | Mastodon 4.4+ for a `standard: true` subscription (`aes128gcm`), most libraries |
| draft (older) | `Authorization: WebPush <jwt>` and `Crypto-Key: p256ecdsa=<key>` (`;` or `,` between params, `dh=` beside it) | older libraries |

**The draft form buys little (L10).** Senders pair it with `aesgcm` payloads, whose salt and `dh` travel in the
`Encryption`/`Crypto-Key` headers. `/up/` doesn't pass those on, and the connectors decrypt only RFC 8291 `aes128gcm`.
So a legacy Mastodon subscription can't work through any distributor, which is why Fedilab subscribes `standard`. The
parsing stays because it costs a few lines and a sender may sign the draft way with an `aes128gcm` body. It is not a
supported path for `aesgcm` (design 108 §7.2).

The JWT (Mastodon's): header `{"typ":"JWT","alg":"ES256"}`, claims `aud` = the endpoint's `normalized_site`
(`https://phones.example.com`, no default port), `exp` = now + 24 h exactly, `sub` = `mailto:…`.

### 4.3 The check (`server/src/unified_push/vapid.rs`, pure, `now` passed in)

Cheap steps first, then the signature, then the claims (**Q3 refinement, decided 2026-11-17**): the signature is verified
with the registered key **before** `exp` and `aud` are judged, so a bad signature is always 403 and 503 goes only to a
correctly signed push whose time or address is off. A forger, who can't sign, never gets a 503. The ECDSA cost stays
bounded by the edge's per-client bucket (§4.4); a garbage token still costs nothing, because the shape check (step 3)
comes first.

1. **Form.**
   - An `Authorization` header of at most 1024 bytes, with the scheme `vapid` or `WebPush` in any case.
   - For `WebPush`, the key comes from `Crypto-Key`'s `p256ecdsa`.
   - Neither form, or no key: **missing**.
2. **The key.** `k` is base64url (padding and the standard alphabet tolerated) and decodes to 65 bytes. It is compared
   with the registered key **as decoded bytes**, never as strings. Not the registered key: **wrong key**.
3. **The JWT's shape.** Three base64url parts.
   - The header is JSON with `alg` `ES256` and any `typ`. `kid`, `jku` and `x5u` are ignored and never fetched; a
     `crit` member is refused.
   - The claims are JSON, and the signature is 64 bytes: JWS's fixed r‖s. ASN.1 is refused (RFC 7518 §3.4).
   - Any failure here: **bad**.
4. **The signature**, verified with `ring`'s `ECDSA_P256_SHA256_FIXED` over `header.claims` with **the registered
   key** (never the request's own). A failure: **bad** (403). Judged before `exp` and `aud`.
5. **`exp`** is any JSON number, floored. It must be in `[now - 5 min, now + 25 h]` (M3). RFC 8292's 24 h is the
   sender's MUST. We allow an hour more, because Mastodon signs exactly 24 h ahead by its own clock, and a fast sender
   clock must not be refused. The 5 minutes below cover a slow one. Otherwise: **time**.
6. **`aud`** is a JSON string, or an array holding one (RFC 7519). It is parsed as a URL and normalised: lowercase
   scheme and host, port 443 dropped. It must equal the current public origin or one in `up_previous_origins` within its
   30 days (M4: an app server still on the old address signs for it). Otherwise: **address**.
7. `sub` is not checked (RFC 8292: a push service MAY ask for it; nothing here uses it).

**Never logged (L4):** the `Authorization` and `Crypto-Key` headers are never in tracing or the security log. The JWT is
a 24-hour credential for that endpoint.

**A stored key that isn't a point (L7).** An R1 server stored any 87-character base64url. At use, a stored key that
doesn't parse as a P-256 point is treated as **no key**: the push is taken, as R1 did, with one warning in the log per
registration. Refusing every push for it would make the sender drop the subscription for good. Real apps send real
keys (the connector checks the form, and the key comes from the app's server), so this is a corner case. It is not a
loophole: R1 never verified these registrations either.

### 4.4 The answers, and where the check sits in `/up/`

The order in `publish`: the size checks, the secret's lookup (a gone secret still gets 410, or 404 for a Matrix body),
**then the VAPID check**, then the buckets and the queue (`accept`).

| Case | Answer (Q3's recommended (c)) | Answer if Q3 = (a) |
|---|---|---|
| no key registered | as today (201, 429, 507 …) | the same |
| a key registered, the push verifies | as today | the same |
| **missing** (no `Authorization` of either form) | **401**, `WWW-Authenticate: vapid` | 401 |
| **wrong key**, **bad** (form, `alg`, `crit`, signature) | **403** | 403 |
| **time** (`exp`) or **address** (`aud`) of a **correctly signed** push | **503**, `Retry-After: 300` | 403 |

- **401 and 403 follow RFC 8292 §4.2 (L5):** 401 for absent authentication, 403 for invalid. Mastodon treats both
  alike.
- **Time and address may be our fault** (the server's clock, or the origin setting), so under (c) they get a 5xx, which
  Mastodon retries and which keeps the subscription. What the sender alone can fix (no signature, a wrong key, a bad
  signature) gets a 4xx, which ends the subscription.
- Neither counts toward the client's ban: the secret resolved (R1's M5 rule).
- **Before the buckets**, so a leaked endpoint without the private key can't spend the phone's bucket or wake it. The
  check's cost is bounded by the edge's per-client bucket (40, then 20 a second; R1-SR L3) and by §4.3's order.
- **The refusal's stamp without a write per request (L6).** `store::lookup` returns `vapid_refused_at` with the row.
  `UPDATE … SET vapid_refused_at = datetime('now')` runs only when the stamp is null or more than a minute old, so a
  refused flood takes SQLite's write lock at most once a minute per registration.
- **Recovery, Q3's (d).** While `vapid_refused_at` is within the last day and no accepted push came after it
  (`last_push_at` older or null: a keyed registration accepts only verified pushes), the policy lists the registration
  in `refresh` with `why: "refused"`. The phone then sends the app a raised `NEW_ENDPOINT` once a day while the phone
  is in use (§3.3). Fedilab's `PushServiceImpl.onNewEndpoint` re-subscribes Mastodon, so a wrongful 4xx costs at most
  about a day. Without (d) the server never lists `refused`, and the contract is unchanged.

**Decided 2026-11-17 (Q3 (c) + (d)):** a bad signature is 403 whatever its `exp` and `aud`; only a push that verifies
with the registered key and has a wrong time or address gets 503.

**What each answer does at the sender** (`push_notification_worker.rb`'s `send`):
- **A 4xx** other than 408 or 429 destroys the subscription. The app gets nothing until it subscribes again: Fedilab
  does that at each start, and on a `NEW_ENDPOINT`.
- **A 5xx** raises an error. Sidekiq retries that push 5 times over some minutes, then drops it. The subscription
  stays, so pushes flow again as soon as the cause is fixed.

Our status code doesn't change what a forger does; it only steers a real sender.

### 4.5 The gateway must not bypass it

A homeserver never signs. So `POST /_matrix/push/v1/notify` with a pushkey whose registration has a key is answered
with the pushkey in `rejected`, as a pushkey that isn't ours. Nobody can push a keyed endpoint unsigned through the
gateway.
- It also writes `vapid_refused_at` (L9), so the PWA's refused warning shows if a Matrix app ever passes a key.
- No Matrix app registers a key today. Element X's `RegisterUnifiedPushUseCase` passes only `instance` and
  `messageForDistributor`. FluffyChat calls `UnifiedPush.register(instance, features)`.
- A Matrix body from an outside gateway to a keyed `/up/` gets 401, which common-proxies treats as a rejected pushkey.

### 4.6 The key at registration (both)

- **The server** checks that a `vapid` decodes to an uncompressed P-256 point on the curve: 65 bytes, `0x04`, and
  `ring`'s `agreement::agree_ephemeral` with a throwaway key accepts it (`webpush.rs` already uses `ring::agreement`).
  Otherwise it answers **400** (the contract's `invalid`). A key that can never verify must not be stored: every push
  would be refused, and the sender would drop the subscription.
- **A REGISTER without a key never drops one (H1).**
  - Fedilab fetches the instance's key at every start, and registers with `null` when that fetch failed: no network
    yet, or the instance slow (`PushHelper.java`). Connector 3.0.9 keeps the instance's token.
  - So a known, keyed token re-registered **without** `vapid` is answered `Known`, from the store: no network, the key
    kept, as in R1.
  - Dropping a key takes an `UNREGISTER` and a new REGISTER, which gives a new registration.
- **Another valid key is an update, answered from the store (H1).** A known, confirmed token re-registered with a
  **different** key gets `Update(row)`:
  1. The phone answers `NEW_ENDPOINT` **at once from the store**: the old endpoint, which with the old key still works.
  2. It sets `UpRow.vapidPending` to the new key and posts `{reg, secret, package, vapid: <new>}` (the same pair).
  3. **A 200** (the server's retry branch now also **updates the stored key** and clears `vapid_refused_at`) moves
     `vapidPending` into `vapid`.
  4. **No network or a 5xx** keeps it pending; it is posted again from `policyAccepted`.
  5. **A 400** (not a point) clears `vapidPending`, keeps the old key and writes a diagnostics note
     (`NOTE_BAD_KEY`).
  6. **Never `REGISTRATION_FAILED`.** Connector 3.0.9 removes the instance and its Web Push keys on it, which would
     silence the app until its next start with a network.
- **At most one key-update call an hour per token (M4).** The call's time is `UpRow.keyTryMs`. A REGISTER past that is
  answered `Known` with the new key in `vapidPending`, posted by a later `policyAccepted`. A key flipping on every start
  costs one call an hour.
- A **pending** row is retried with the newest key (it is a server call anyway).
- Only the app itself can update its key: its token, its shared identity.
- **The server releases first (L8).** An R1 server answers an update with the same endpoint without storing the key,
  and an R3 phone would take the 200 as done. Releasing the server tag before the launcher RC avoids that without a
  contract change. The other order (an R3 server, R1 phones) is safe: R1 phones send and R1 servers already store keys,
  and an R1 phone never updates a key, which matters only if an app server rotates its key (rare; Mastodon's own
  subscriptions break on a rotation too).

### 4.7 The PWA

- When any of the app's registrations has a key, the row says: nb "Bare push signert av appens server", en "Only
  pushes signed by the app's server".
- While `vapid_refused_at` is within 7 days, it shows a warning:
  - nb "Push som ikke var signert av appens server, ble avvist, sist {when}";
  - en "Pushes not signed by the app's server were refused, last {when}".
- Neither is a save.

### 4.8 The server's clock

The `exp` check depends on it. The server runs on a VM with NTP, and design 35's own Web Push already signs with that
clock. DEPLOY.md's "App push" section gains one line: the server's clock must be right, or signed pushes are refused.
Under Q3 (c) such a refusal is a 503, which keeps the subscriptions.

---

## 5. R3-b: the Urgency hold (Q2)

The spec (`android.md`, MESSAGE): "only messages more urgent than the minimum urgency SHOULD be send". The minimum
depends on the device's state:
- `very-low` on power and Wi-Fi;
- `low` on either;
- `normal` on neither;
- `high` on a low battery.

**What our senders set** (read in the sources): Mastodon always sends `Urgency: normal`. Our gateway maps Matrix `prio:
high` to `high` and everything else to `normal` (R1, `matrix.rs`).
- So for Element X, FluffyChat and Fedilab the hold would change nothing, except through the spec's low-battery row.
- That row would **hold every ordinary chat message** (`normal`) while the battery is low: a kid's phone at 15 % would
  stop showing Mamma's messages in a group chat.
- Only a sender that sets `low`/`very-low` itself (none of the apps above) would see the rest of the rule.

**Recommended (Q2 = a): no hold.** R1's behaviour stays, and the deviation stays stated (design 108 §12.1).

**If the user picks (b): hold only `very-low` and `low`, never `normal` or `high`, and skip the low-battery row.**
- **The pull** gains `min_urgency` (`"very-low"|"low"|"normal"`). The server returns only messages at or above it and
  counts `more` the same way.
- **Held rows stay queued until their TTL** (at most 24 h). A phone that never charges or sees Wi-Fi in a day loses
  them; their senders said they could wait.
- **The phone** sets the value from its state: `normal` on neither power nor Wi-Fi, `low` on either, `very-low` on
  both. It pulls again on a change: the anchor's existing network callback for Wi-Fi, and an `ACTION_POWER_CONNECTED`
  receiver registered in the anchor's process (manifest receivers don't get it since Android 8; the same on 14-17).
- **The contract** is the only change outside `refresh`: `messages` gains a `body_min_urgency` row and an answer that
  leaves a `low` message out.
- **Size:** server XS, launcher S-.

---

## 6. R3-c: other apps (checked in their sources)

### 6.1 FluffyChat: works with R1 as it is

- **Connector:** `unifiedpush` 6.2.0 → `unifiedpush_android` 3.4.1 → `org.unifiedpush.android:connector:3.1.2`.
  - REGISTER and UNREGISTER go with `setShareIdentityEnabled(true)` on SDK 34+ (`UnifiedPush.register`), so our
    `getSentFromPackage()` identity holds.
  - MESSAGE_ACK is sent as Element X's connector sends it: a plain broadcast, token only.
- **No VAPID key, no MXID:** `UnifiedPush.register(instance: 'default', features: [])`. That is one registration for
  all its accounts, so the PWA says "FluffyChat · 1 account" however many are logged in.
- **No embedded FCM distributor** (no `embedded-fcm-distributor` in its Gradle files). Its own FCM path is separate:
  - `setupPush` takes UnifiedPush **whenever any distributor exists** (`UnifiedPush.getDistributors().isNotEmpty`),
    even in the Google build.
  - `unifiedpush_ui`'s `registerAppWithDialog` uses a saved distributor. Without one, it saves the only one there is
    (Vibb One on a kid's phone) without a dialog.
  - **So FluffyChat picks Vibb One by itself**, at every start of its chat list (`chat_list.dart:440`). It also
    replaces an earlier Google pusher of the same app id.
- **Matrix gateway:** its discovery GETs `<endpoint origin>/_matrix/push/v1/notify` and takes ours when the answer is
  `{"unifiedpush":{"gateway":"matrix"}}` (or the older `{"gateway":"matrix"}`). Any error → unifiedpush.org's (§3).
- **The pusher:** `format: event_id_only`, `data_message: true`, `app_id` `chat.fluffy.fluffychat.<device id>`.
  `_onUpMessage` reads `json.decode(message)['notification']` and fetches the event itself, so the minimised body (Q8)
  loses nothing.
- **The raise:** connector 3.1.2's manifest declares the `RAISE_TO_FOREGROUND` service.
- **Builds:** any of these works.
  - GitHub releases' `fluffychat.apk` (`flutter build apk`: all ABIs including x86_64, with Firebase);
  - Play (with Firebase);
  - FluffyChat's own F-Droid repository.
- **`KNOWN_UP_APPS`** gains `chat.fluffy.fluffychat` ("FluffyChat") with its own "isn't using Vibb" text:
  - nb "{app} bruker ikke Vibb for varsler. Åpne {app} én gang, så velger den Vibb One selv.";
  - en "{app} isn't using Vibb for notifications. Open {app} once: it picks Vibb One by itself."

### 6.2 The VAPID path: Fedilab with a Mastodon 4.4+ account

- **Fedilab** (connector 3.0.9: it shares its identity):
  - At every start it fetches the instance's VAPID key (`/api/v2/instance`'s `configuration.vapid.public_key`, else
    Pleroma's). It registers with it: `UnifiedPush.register(context, "<user_id>@<instance>", null, vapid)`
    (`PushHelper.java:193`).
  - **It registers with `null` when that fetch failed.** §4.6 keeps the stored key then (H1).
  - On `onNewEndpoint` it subscribes the Mastodon server to our endpoint with **`subscription[standard]=true`**
    (`NotificationsVM`). Mastodon 4.4+ then sends RFC 8291 `aes128gcm` signed `vapid t=…,k=…` (§4.2), which the
    connector decrypts.
- **Flavours:**
  - the F-Droid flavour `fr.gouv.etalab.mastodon` has no FCM. With Vibb One the only distributor, it saves it by
    itself;
  - the Play flavour `app.fedilab.android` embeds the FCM distributor, so it sees two and saves neither. There the
    distributor is picked in Fedilab's own settings.
  - **Use the F-Droid flavour** (the server's F-Droid catalog).
- **Mastodon's sender** (§4.2, §4.4): `TTL 48h` (we clamp it to 24 h) and `Urgency: normal`. A 4xx other than 408 or
  429 destroys the subscription; a 5xx is retried 5 times.
- **Also VAPID, for later: DAVx⁵** (connector 3.3.5).
  - It registers **only** when its WebDAV-Push server advertises a VAPID key (`PushRegistrationManager`).
  - It finds its distributor through `resolveDefaultDistributor`, i.e. our Link Activity's preferred activity (R1's
    L3).
  - It needs a WebDAV-Push server (Nextcloud with the DAV Push app).
- **Not VAPID:**
  - Pachli (connector 3.0.10) registers without a key;
  - mastodon-android has no UnifiedPush;
  - **Tusky** (on GitHub, last commit 2025-05) uses connector **2.4.0**, an AND_2 connector without the shared
    identity, so it can't register with Vibb One at all (design 108 §5.1, L4's deliberate deviation).

### 6.3 What each test needs from the user (on the phone)

**FluffyChat, now (R1 is enough; R2's warning and heal come later):**
1. PWA, Device › Apps: allow FluffyChat and install it (Play, or a catalog row: GitHub `krille-chan/fluffychat`, asset
   `fluffychat.apk`). App push on, the address saved.
2. On the phone: open FluffyChat and log in as Emma on the family's homeserver. Nothing to pick.
3. Check that it connected:
   - PWA › App push shows "FluffyChat · 1 account".
   - In FluffyChat › Settings › Notifications, the distributor line names Vibb One.
   - The pusher's URL is the phones' address + `/_matrix/push/v1/notify`. If it is `matrix.gateway.unifiedpush.org`,
     the discovery failed: R2's case.
4. With the phone's screen off, send Emma a message from Pappa's account: the notification comes. If Element X on Google
   is also on the phone, note the delay against it.

**Fedilab and Mastodon, after R3 is released:**
1. A test Mastodon account (Emma) on a 4.4+ server. PWA: allow Fedilab (F-Droid flavour) and install it.
2. On the phone: log in. Fedilab registers with the server's key and subscribes Mastodon to our endpoint.
3. PWA › App push: "Fedilab · 1 account" and "Only pushes signed by the app's server".
4. From Pappa's account, reply to or favourite Emma's post: the notification comes, and no "refused" warning shows.

---

## 7. The shared contract and the migration

**`server/testdata/unified_push.json`** (byte-identical in `launcher/app/src/test/resources/`), in the contract commit.
It is the same whatever the user answers to Q1-Q3:

- **The policy.** Every `policy` row gains `"refresh": []`. Two rows are new:
  - `refresh_outside`: `on_two` with `"refresh": [{"reg": "c0ffee00000000000000000000000002", "why": "outside"}]`;
  - `refresh_refused`: the same with `"why": "refused"`.
  - `older_server` is unchanged: no key, so no refresh.
  - An unknown `why` reads as `outside`: a refresh is the same act for both.
- **`register`** gains three bodies:
  - `body_vapid`: `reg` …03, secret …003, `org.unifiedpush.example`, `vapid` = the test key's public half (§10);
  - `body_vapid_changed`: the same `reg` and secret, another valid key;
  - `body_vapid_not_a_point`: 87 base64url characters that aren't a P-256 point.
- **`answers`** gains three:
  - `ok_vapid`: 200, the endpoint;
  - `vapid_changed`: 200, the **same** endpoint;
  - `vapid_not_a_point`: 400, `null`.

**The tests and the gates:**
- The server's `tests::unified_push::the_contract` produces the new rows. It checks the stored key after
  `vapid_changed`, and that each `refresh` row's `reg` is in `regs`.
- The launcher's `UnifiedPushContractTest` decodes `refresh` and encodes `body_vapid` and `body_vapid_changed`
  exactly.
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` gain `unified_push.refresh`.
- An R1 launcher ignores the key (`ServerJson` has `ignoreUnknownKeys`).
- No new capability: `refresh` is additive, and the order of release is §4.6's.

**Migration `0153_up_gateway_and_vapid.sql`** (0153 is the next free number on every branch, checked 2026-11-17):

```sql
-- Design 108b: the last push through our own Matrix gateway (R2: it ends the outside-gateway mark), and the last
-- push refused by the VAPID check (R3: the PWA's warning and Q3's recovery). Both written at most once a minute.
ALTER TABLE up_registrations ADD COLUMN gateway_at TEXT;
ALTER TABLE up_registrations ADD COLUMN vapid_refused_at TEXT;
```

**Server-only:**
- `server/testdata/unified_push_vapid.json` (§10; made by `scripts/up-vapid-vectors.py`, public data only);
- three new `unified_push_gateway.json` rows: a keyed registration's pushkey is `rejected` and stamps
  `vapid_refused_at`, and a gateway push sets `gateway_at`.

**Launcher-only, `up_rules.json` gains:**
- `liveness` and `refresh`;
- the register rows:
  - a known token with another key → `update:<reg>`;
  - the same → `known:<reg>`;
  - a known keyed token without a key → `known:<reg>` (H1);
  - another key within the hour of the last key call → `known:<reg>` with the key pending (M4).

**Elsewhere:** the CI path lists don't change (the same files). The root `CLAUDE.md`'s `unified_push.json` paragraph
gains `refresh` and the VAPID register rows, and its scripts line gains `fake-homeserver.py --vapid`.

---

## 8. Words (en and nb)

**Server** (`src/i18n/en.json`, `nb.json`; `i18n::tests` and `nb_pages_have_no_english` check them):
`app_push.outside` (§3.4), `app_push.signed` and `app_push.vapid_refused` (§4.7), and `app_push.missing_open` (§6.1).
The existing `app_push.missing` stays Element X's text. `KNOWN_UP_APPS` holds `(package, name, missing_key)`.

**Launcher** (`values/strings.xml`, `values-nb/strings.xml`): the diagnostics notes
`settings_mdm_up_note_unanswered` (§2) and `settings_mdm_up_note_bad_key`:
- nb "%1$s sendte en ugyldig nøkkel; den gamle beholdes";
- en "%1$s sent a key that isn't valid; the old one is kept".

Nothing new appears on the kid's screens.

---

## 9. Android 14-17

- **Liveness and the heal** send the same `NEW_ENDPOINT` and `UNREGISTERED` broadcasts R1 sends: an explicit action,
  `setPackage`, `FLAG_INCLUDE_STOPPED_PACKAGES`. R1-G passed those on 14 and 16 (rows 1, 6, 13, 14).
  - The raise for a `NEW_ENDPOINT` is R1's bind: row 3, foreground-service level for 5 s on both.
  - `ACTION_USER_PRESENT` and `PowerManager.isInteractive`/`isDeviceIdleMode` exist and behave alike on 14-17. The
    receiver is context-registered: the broadcast isn't delivered to manifest receivers.
  - No new manifest entry or permission in the release build. The debug build gains `UpDebugReceiver` (§11).
- **R3 on the phone** is the register decision and the pending key.
- **Q2's option (b)** would add a context-registered `ACTION_POWER_CONNECTED`/`DISCONNECTED` receiver: the same on
  14-17.
- **17:** API 37's behaviour-change list is read at the build for broadcasts and binds. The gate's 17 run stays owed:
  no API 37 image boots on this VM (R1-G).

---

## 10. Tests (lean)

**Server:**
- **`unified_push::vapid::tests::the_rows`** runs every row of `server/testdata/unified_push_vapid.json` through the
  pure check, with the file's `now`, `origin` and `previous_origins` (`registered_key` is the registered key; row
  fields `authorization`, `crypto_key?`, `outcome`):
  - **taken:** (also `scheme_case_webpush`, `exp_slow_sender_edge`) `rfc_mastodon` (Mastodon's exact spelling, no space), `rfc_spaced` (`t=…, k=…`, reversed order),
    `rfc_quoted` (`t="…"`), `scheme_case` (`Vapid`, `webpush`), `k_padded`, `k_std_alphabet`, `draft_webpush`
    (`WebPush` + `Crypto-Key: dh=…;p256ecdsa=…`), `exp_float`, `exp_24h_plus_30min`, `aud_array`,
    `aud_with_port_443`, `previous_origin`;
  - **missing:** `missing`, `no_key`;
  - **wrong key:** `wrong_key`;
  - **bad:** `bad_signature`, `asn1_signature`, `alg_none`, `crit_header`, `oversized`, and (Q3 refinement) `expired_bad_signature` and `wrong_aud_bad_signature`:
    a bad signature is **bad** even where `exp` or `aud` is off;
  - **time:** `expired`, `exp_26h`;
  - **address:** `wrong_aud`, `previous_origin_ended`.
- **`unified_push::vapid::tests::points`:** the contract's test key, the not-a-point key, and a stored non-point read
  as no key (L7).
- **`tests::unified_push::the_contract`** (the new rows) and **`gateway_rows`** (the three new rows).
- **`tests::unified_push::vapid_at_the_route`:** a keyed registration answers `missing` 401, `wrong_key` 403 and
  `expired` 503 at `/up/`. None of them charges the phone's bucket or counts toward the ban, and a second refusal
  within the minute writes nothing. An unkeyed registration takes the same unsigned push (201).
- **`tests::unified_push::refresh_list`:**
  - a Matrix body at `/up/` lists `outside`, and a gateway push ends it;
  - a refusal lists `refused`, and a verified push ends it.
- `policy_json_keys_snapshot`, and `i18n::tests` (automatic).

**The vectors** are made by `scripts/up-vapid-vectors.py --write` (R23-C; pure Python, deterministic, no network; it has no
`--selftest` of its own) with a fixed `now`, signing with the test key; `--keys` prints the two public keys the
contract's `body_vapid` and `body_vapid_changed` carry. R23-G's `fake-homeserver.py --vapid` may read the file and check
that the contract's `body_vapid` key is its key. **The test key** is a P-256 private scalar derived from
the SHA-256 of the phrase "vibb fake vapid key - test only, never a real sender" (the secrets rule: fake keys look
fake). Only its public half is in the contract.

**Launcher:**
- **`UpRulesTest`** from `up_rules.json`:
  - `liveness`: no ping before 23 days; a ping at 23; an ack clears it; dead 7 days after the ping; a pending row
    never; a stamp a week ahead read as now;
  - `refresh`: marked and never refreshed while in use → sent; marked while not in use → waits; refreshed 2 h ago →
    not sent; 25 h ago → sent; a pending row never; one `NEW_ENDPOINT` when a ping and a refresh fall due together,
    carrying the ping's id;
  - the register rows of §7.
- **`UpEngineTest`**, one case each:
  - a dead row is removed with `UNREGISTERED`, its `DELETE` queued and the note written;
  - an `Update` against a failing server answers `NEW_ENDPOINT` with the old endpoint and no `REGISTRATION_FAILED`,
    and the key is posted at the next policy (H1);
  - a 400 keeps the old key and writes the note.
- **`RaiseToForegroundTest`** gains a `NEW_ENDPOINT` kind: sent through the bind and never reported for the ack.
- `UnifiedPushContractTest` and `PolicyResponseCompatTest`.

---

## 11. The emulator gate (R2R3-G), Android 14 and 16

The rig is `docs/testing/emulator.md` §6ay's: `compat34`, `jelly36`, the debug server and launcher, the origin
`https://up.test`. Three additions:

- **The probe** (`me.vibb.probe.up`) gains:
  - a `vapid` extra on REGISTER: the test key's public half, or another;
  - an "ack off" switch, which stops it sending MESSAGE_ACK;
  - a re-REGISTER of the same token with another key, or with none.
  It also logs the uid state it sees during each `NEW_ENDPOINT`.
- **`scripts/fake-homeserver.py`** gains:
  - `--vapid rfc|rfc-spaced|draft|none|wrong-key|expired|wrong-aud`: pure-Python ES256 on P-256 with the test key,
    standard library only, `secrets` for the nonce;
  - `--outside`: a Matrix-shaped body posted to `/up/<secret>`, as an outside gateway does.
  Its `--selftest` covers both and already runs in `server-ci.yml`'s changes job.
- **No clock jumps (L11).** The debug build gains `UpDebugReceiver`: `exported="true"` with `android.permission.DUMP`,
  as `MmsDebugReceiver` is, so only the shell can call it. It backdates a row's `lastAppAckMs`, `pingSentMs` or
  `confirmedAtMs` by N days in the store (`am broadcast -n … --es reg … --ei days 24`). The system clock, the DO's
  date lock, the TLS checks and the time rules are never touched.

| Row | What | Expected |
|---|---|---|
| 1 ping | the probe registered; the store backdated 24 days; a sync with the screen off and `dumpsys deviceidle force-idle` | the probe gets `NEW_ENDPOINT` with the same endpoint and a new id, inside the raise (its uid at the foreground-service level); it acks; no second ping at the next sync |
| 2 dead | "ack off"; backdated 24 days (ping), then the ping 8 days; a sync each | `UNREGISTERED` to the probe; a POST to its endpoint gets 410; the PIN-gated Settings row shows the note |
| 3 outside, idle | `--outside` to the probe's endpoint; the phone idle (screen off, force-idle); a sync | the PWA's warning on the probe's row; the policy's `refresh` lists the `reg`; **no** `NEW_ENDPOINT` while idle |
| 3b outside, in use | unlock the phone | `NEW_ENDPOINT` within a second of `USER_PRESENT`, raised; no second one at the next sync or the next unlock |
| 4 healed | a gateway POST (our `notify`) for the probe's pushkey | the PWA's warning gone; `refresh` empty in the next policy |
| 5 VAPID | the probe registered with the test key; `--vapid rfc`, `rfc-spaced`, `draft` | 201 and delivered, each; the PWA shows "Only pushes signed…" |
| 6 refused | `--vapid none`, `wrong-key`, `expired`, `wrong-aud`; then a gateway POST for the pushkey | 401, 403, 503, 503, nothing delivered, the phone not nudged; the gateway `rejected`; the PWA's refused warning; with Q3 (d), `refresh` lists `refused` until a verified push |
| 7 key update | the probe re-REGISTERs the same token with another key; then once more without a key | `NEW_ENDPOINT` with the same endpoint at once, each time; the server holds the new key after the first; the keyless one changes nothing |
| 7b offline update | the server unreachable; the probe re-REGISTERs with a third key | `NEW_ENDPOINT` with the same endpoint at once, no `REGISTRATION_FAILED`; the server back, the key arrives at the next policy |

Rows 1-3b and 5-7b run on both versions; row 4 is a server-and-policy row and runs on one (16).

**Owed on the Jelly Star (the user's tests, §6.3):**
- FluffyChat end to end. It can't log in on this VM: it needs a homeserver that can reach the push address.
- The heal with FluffyChat and the phone idle overnight: whether its discovery reaches the server
  (`last_discovery_at`) after a refresh at the next unlock (M1).
- Fedilab with Mastodon's real signed pushes.
- Element X (R1-E).

---

## 12. Rounds

| Round | Size | What |
|---|---|---|
| **R23-C** | XS | the contract rows (both copies), migration 0153, the policy's `refresh` (stubbed empty), the snapshot and compat test, the root `CLAUDE.md` lines: one commit both worktrees start from |
| **R23-S** (server) | S | `vapid.rs` (the forms, the ordered check, the point check, the stored-key read) and its vectors; the hook in `publish` before `accept`, with the answers of Q3's choice; the gateway's refusal of keyed registrations; `gateway_at`, the marks and `refresh`; the key update in the register retry; the PWA lines and words; `KNOWN_UP_APPS` (FluffyChat); DEPLOY.md's clock line; the notes |
| **R23-L** (launcher) | S+ | `UpRules.liveness` and `refreshes`; `UpRow.refreshMs`, `vapidPending` and `keyTryMs`; the raise's `NEW_ENDPOINT` kind; the in-use gate and the `USER_PRESENT` receiver; `RegisterDecision.Update` answered from the store; the notes and their words; `UpDebugReceiver`; `up_rules.json`; the notes file |
| code QA | S each | an Opus pass per side; the server's with a security focus on `vapid.rs` (attacker-controlled headers and crypto on a public route), in place of a separate review |
| **R23-G** | S | the probe's, `fake-homeserver.py`'s and the debug receiver's use; §11's rows on 14 and 16 |
| release | - | **the server tag first, then the launcher RC** (§4.6, L8); then the user's FluffyChat and Fedilab tests |

R23-S and R23-L run in parallel, in two worktrees.
- **If Q2 = (b):** + XS server, + S- launcher, + one gate row.
- **If Q3 isn't (c):** the server's answer table changes in one place.
- **If Q3 drops (d):** the server never lists `refused`.

Neither Q3 change touches the contract. The FluffyChat test needs none of this round, and can run on today's release.

---

## 13. Questions for the user

**Decided (the user, 2026-11-17):** **Q1 yes**; **Q2 (a)**, every push delivered at once; **Q3 (c) + (d)**. Two
refinements were added to the answers: Q1's boot re-send and its two-part "dead" (§2), and Q3's signature-first order
(§4.3, §4.4). The questions are kept below as asked.

Short enough to put in Norwegian in a few lines each.

1. **Q1 (R2): remove dead app registrations by themselves?**
   - **The rule:** when an app hasn't answered Vibb One for 30 days, Vibb One removes its registration. It sends a
     ping at day 23 first.
   - **Who it hits:** only an app that no longer knows its registration, e.g. Element X switched back to Google
     without saying so. An app that is installed, even stopped or blocked by a time rule, always answers by itself.
   - **The cost:** if we're wrong, that app has no push until it is opened again. It then registers anew.
   - **Recommended: yes.**
2. **Q2 (R3): hold less urgent pushes?**
   - **(a) No: every push is delivered at once, as today (recommended).**
   - (b) Hold the senders' "low" and "very low" pushes until the phone is on Wi-Fi or charging. Ordinary and urgent
     ones are never held.
   - None of the apps we know sends "low", so (b) changes nothing today. The spec's rule for a low battery is left out
     in both: it would hold every ordinary chat message.
3. **Q3 (R3): what happens to a push that isn't correctly signed by the app's server (VAPID)?**
   - **The trade:** a "no" (4xx) makes Mastodon delete the app's push subscription, so the app gets no push until it
     subscribes again. A "try later" (5xx) keeps it, and Mastodon retries that push a few times. A forger is
     unaffected either way: only a real sender reacts to the answer.
   - **(a)** Always "no", as the standard says. If our server is ever at fault (its clock badly wrong, the address
     setting, a bug), Fedilab silently loses push until it is opened again.
   - **(b)** Always "try later". The subscription survives our faults, but an app server with a wrong key never learns
     it, and its pushes are lost quietly.
   - **(c)** Split by cause: "no" for what only the sender can fix (no signature, the wrong key, a bad signature);
     "try later" for what may be our fault (the time and the address in the signature).
   - **(d)** In addition to any of these: after a refusal, Vibb One asks the app once a day, while the phone is in
     use, to connect again. Fedilab then subscribes again, so a wrongful "no" costs at most about a day.
   - **Recommended: (c) + (d).** The PWA shows a "refused" warning in every case.

---

## 14. Risks and checks owed

1. **A check that refuses a real sender** (Q3). Covered by:
   - Mastodon's exact spelling in the vectors: no space, `typ`, `aud` without a port, `exp` at 24 h and up to 25 h;
   - (c)'s 503 for time and address;
   - (d)'s daily re-subscribe;
   - the user's Fedilab test.
2. **The heal depends on the apps' `onNewEndpoint`** re-running the discovery, as today's Element X and FluffyChat do,
   and on the raise giving them network. The idle case is owed on the Jelly Star (§11). If it fails, the PWA's warning
   stays and points at "Last seen from outside".
3. **A clock that ran ahead** pings every registration early, and a live app answers at once. Stamps more than a day
   ahead are read as now.
4. **Tusky can't use Vibb One** (an AND_2 connector) until it moves to connector 3.x. Pachli works without VAPID.
5. **Android 17** is owed, as for R1.

---

## 15. Design QA: how each finding was handled (`docs/review/qa-108b-design.md`)

| Finding | What it said | The fix | Where |
|---|---|---|---|
| **H1** | a keyless REGISTER dropped Fedilab's key, and an offline update answered `REGISTRATION_FAILED`, which connector 3.0.9 turns into a removed instance | a REGISTER without a key is `Known`; another key is `Update`, answered `NEW_ENDPOINT` from the store at once; the new key is `vapidPending`, posted until a 200; a 400 keeps the old key with a note; never `REGISTRATION_FAILED` | §4.6, §6.2, §7, §10, gate rows 7, 7b |
| **M1** | the heal's plain broadcast at backstop syncs: the app's discovery runs after `onReceive`, mostly without network | every `NEW_ENDPOINT` that isn't a REGISTER's answer goes through the raise (ping, refresh, R1's rewrite); a refresh only while in use (`isInteractive`, not device-idle), else at the next `USER_PRESENT`; 24 h from an attempt made; the warning's wording says "when the phone is in use"; the idle heal owed on the Jelly Star | §2, §3.3, §3.4, §9, §11 rows 1, 3, 3b |
| **M2** | Q3's cost for 503 was wrong; options (c) and (d) missing; one policy key for both reasons | Q3 re-framed with (a)-(d) and both costs; recommended (c) + (d); the policy key frozen now as `refresh: [{reg, why}]` (`outside`, `refused`), so the contract doesn't depend on the answer | §4.4, §7, §12, §13 |
| **M3** | `exp` capped at 24 h + 5 min refuses a fast sender clock | `[now - 5 min, now + 25 h]`; rows `exp_24h_plus_30min`, `exp_26h`; the skew named in Q3's risk | §4.3, §10, §14 |
| **M4** | a key update costs a server call per REGISTER | at most one update call an hour per token (`keyTryMs`); past that `Known` with the key pending | §4.6, §7 |
| **L1** | the fix texts repeat the heal; "unifiedpush.org" may be another gateway | one text for every app: an outside gateway (usually unifiedpush.org); if it stays, the phone can't reach the address - see "Last seen from outside"; the per-app fix keys dropped | §3.4, §8 |
| **L2** | a ping and a refresh in one pass send two | one `NEW_ENDPOINT` per row per pass; when the ping is due it carries the ping's id and counts as the refresh | §2, §3.3, §10 |
| **L3** | a stamp from a clock that ran ahead freezes the rules | every stamp more than a day ahead read as now, in the rules and `UpEngine.ack`'s hourly skip | §2, §10 |
| **L4** | the check's order and cost; scheme case; `kid`/`jku`/`x5u`/`crit`; `aud` arrays; `exp` floats; never log the header | cheap steps first, the ECDSA verify last; `k` compared as bytes; case-insensitive schemes; `kid`/`jku`/`x5u` ignored, `crit` refused; `aud` a string or an array holding ours; `exp` any number; the headers never logged; the vector rows added | §4.3, §10 |
| **L5** | RFC 8292 suggests 401 absent, 403 invalid | aligned: 401 missing, 403 wrong key or bad; (c)'s 503 for time and address | §4.4 |
| **L6** | the refusal's `UPDATE` takes the write lock per request before any bucket | `store::lookup` returns `vapid_refused_at`; the `UPDATE` only when due (a minute) | §4.4, §10 |
| **L7** | R1-stored keys never point-checked | a stored key that isn't a point is read as no key (as R1 did), with one log warning; refusing would make the sender drop the subscription for good | §4.3, §10 |
| **L8** | an R3 phone's update against an R1 server is taken as done | the server tag releases first; no contract change | §4.6, §12 |
| **L9** | the gateway's refusal of a keyed pushkey is silent | it stamps `vapid_refused_at`, so the PWA's refused warning shows | §4.5, §7 |
| **L10** | the draft form buys nothing for `aesgcm` payloads | said so; kept as cheap parsing only; "Mastodon legacy" removed from the table as a supported path | §4.2 |
| **L11** | the gate's clock jumps meet the DO's date lock and much else | a debug-only `UpDebugReceiver` (DUMP-guarded, as `MmsDebugReceiver`) backdates the store; the clock is never moved | §9, §11 |
| **L12** | accuracy: no queue for a forgotten token; Element X re-registers on `UNREGISTERED`; FluffyChat's FGS notification on a heal; `refresh` ⊆ `regs` | each corrected or added | §2, §3.3, §7 |
