# Design 108b: App push R2 and R3 (liveness, the outside gateway, VAPID, urgency, other apps)

Status: design, 2026-11-17 (branch `up-r2r3`, from main 39fc98371). **Not built.** Builds on design 108 (R1-C, R1-S,
R1-L and R1-G built and merged; `docs/design/108-unified-push.md`, the notes `docs/notes/server/unified-push.md` and
`docs/notes/launcher/unified-push.md`). The user approved building R2 and R3 now (2026-11-17: "jeg vil at du bygger r2 og
r3 likevel. vi kan heller installere en app for å teste at det funker, feks fluffy"). §13 has three questions for the
user; nothing they decide changes the contract except Q2's option (b).

**Sources read for this note** (scratchpad clones of 2026-11-17):

| Source | Commit | Date | What was read |
|---|---|---|---|
| UnifiedPush `specifications` | 0c5301f | 2025-10-16 | `android.md`: the ping and unregister (flow 6-7, `UNREGISTERED`), `vapid`, the urgency table |
| `android-connector` | tags 3.1.2 (6948ff3), 3.3.5 | 2025-10 | `UnifiedPush.register` (identity, `vapid`), `MessagingReceiver` (NEW_ENDPOINT, MESSAGE, MESSAGE_ACK), `resolveDefaultDistributor` |
| FluffyChat | 6a0fda5 | 2026-11-15 | `lib/utils/background_push.dart`, `pubspec.lock`, `settings_notifications_view.dart`, `release.yaml` |
| pub.dev `unifiedpush` 6.2.0, `unifiedpush_android` 3.4.1, `unifiedpush_ui` 0.2.0 | - | - | the Flutter plugin's `register`, its connector dependency, `registerAppWithDialog` |
| element-x-android | 739f0b4 | 2026-11-17 | `VectorUnifiedPushMessagingReceiver.onNewEndpoint`, `RegisterUnifiedPushUseCase` |
| Fedilab | 1dfcb7f | 2026-10-03 | `PushHelper.java`, `NotificationsVM.pushSubscription`, `app/build.gradle` |
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
  `/up/` (unifiedpush.org's gateway re-posting). R2 ends the mark when a push comes through our own gateway again
  (migration **0153**: `gateway_at`), puts the marked registrations in the policy (`unified_push.outside`), and **the
  phone heals it by itself**: once a day while marked it sends the app `NEW_ENDPOINT`, which makes Element X and
  FluffyChat run their gateway discovery again and re-set their pusher (both checked in their sources, §3.3). The PWA
  warns on the app's row and says what to do if it stays.
- **R3-a, VAPID (both, mostly server).** A registration made with a `vapid` key only takes pushes signed with that key
  (RFC 8292), in both header forms: `Authorization: vapid t=…,k=…` and the older `Authorization: WebPush <jwt>` with
  `Crypto-Key: p256ecdsa=…`. Never required: a registration without a key takes any push, as today. The check runs
  before the phone's bucket, so a leaked endpoint without the key can't wake the phone. The gateway refuses a keyed
  registration (otherwise it would bypass the check). The key is checked as a P-256 point at registration, and an app
  that re-registers with another key updates it (today the phone answers a known token from its store and never tells
  the server). (Q3.)
- **R3-b, Urgency.** R1 delivers every urgency at once. The spec's SHOULD would hold `very-low` and `low` until Wi-Fi or
  a charger, and `normal` on a low battery. **Recommended: keep delivering everything at once** (Q2).
- **R3-c, other apps.** **FluffyChat works with R1 as it is** and picks Vibb One by itself (§6.1): the user can test it
  now. The VAPID path's real app is **Fedilab with a Mastodon 4.4+ account** (it passes the instance's VAPID key and
  subscribes with standard Web Push; Mastodon signs every push, §6.2). DAVx⁵ is the second (it registers only with a
  key). Tusky can't register at all (an AND_2 connector).
- **Size:** a contract commit (XS), server S, launcher S, the gate S, then each side's code QA. **Migration 0153.**

---

## 1. What R1 left, and what is already there

| Item (design 108) | Already built in R1 | R2/R3 adds |
|---|---|---|
| §5.6 the 30-day rule | `UpRow.lastAppAckMs`, `pingId`, `pingSentMs`; `UpEngine.ack` records an ack (hourly) and clears the ping when it answers it | `UpRules.liveness`, run in `policyAccepted`; the note |
| the 30-day-unseen prune | built in R1 (`store::prune`, `devices.last_seen_at`) | nothing |
| §3.10 R2's warning | `up_registrations.outside_gateway_at`, noted for a queued Matrix-shaped `/up/` push at most once a minute (R1-SR L1) | the mark's end (`gateway_at`), `unified_push.outside`, the phone's refresh, the PWA line |
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
`NEW_ENDPOINT` and a `MESSAGE` synchronously in its receiver (`MessagingReceiver.mayAcknowledgeMessage`, 3.1.2 and
3.3.5). What remains is a token the app no longer knows - the connector then returns before any ack
(`store.registrations.getInstance(token) ?: return`):
- Element X switched back to Google for a session, or a session logged out, without an `UNREGISTER` reaching us;
- the app's data restored or reset in a way `PACKAGE_DATA_CLEARED` doesn't cover;
- an app hidden for a reason other than the allowlist (it gets no broadcast; R1-L's L5 already drops it on delivery).

Such a row keeps a public endpoint alive, holds up to 100 queued pushes for 24 h each, and keeps the PWA's "No delivery in
7 days" line up.

**The rule** (pure `UpRules.liveness(rows, nowMs)`, rows in `up_rules.json`):

- only confirmed rows; `since = max(lastAppAckMs ?: 0, confirmedAtMs ?: createdAtMs)`;
- no ping out and `now - since >= 23 days`: **ping** - `NEW_ENDPOINT(token, endpoint, id = new)`, the row gets
  `pingId` and `pingSentMs`;
- a ping out and `now - pingSentMs >= 7 days`: **dead** - `UNREGISTERED(token)`, the row removed, its `DELETE` queued
  (R1's `remove` path; the server tombstones the secret, so the app server gets 410 or `rejected`), and a diagnostics
  note `NOTE_UNANSWERED` for the PIN-gated Settings row;
- any `MESSAGE_ACK` from the app (a message's or the ping's) clears the ping (R1's `UpEngine.ack` already does).
- **A clock moved back** only delays the rule (negative ages do nothing); **a clock moved forward** only pings early, and
  a live app answers within a second.

**Where it runs:** `UpEngine.policyAccepted`, after the reconcile and the origin rewrite, on the kept rows - every
accepted fresh policy, so at least every 30 minutes (the backstop). No new alarm or worker. Nothing runs before the first
unlock or while UP is off (R1's guards). A row the rewrite just sent `NEW_ENDPOINT` to is not pinged in the same pass.

**The ack path needs no change** (checked in `UpEngine.ack`): the in-memory "a token read within the hour is skipped" can't
swallow a ping's answer, because a ping is sent only after 23 days without an ack, so the token has no recent read.

**Why the server isn't involved:** only the phone sees the app's acks. The server sees the `DELETE`, as for any other
unregistration; the PWA's row loses an account or disappears. The phone's PIN-gated "App push" row shows the note
(nb "%1$s svarte ikke på 30 dager; registreringen ble fjernet", en "%1$s didn't answer for 30 days; its registration was
removed"). If the app is in fact alive, it registers anew at its next start (both Element X and FluffyChat register at
every start, §3.3, §6.1).

---

## 3. R2-b: the outside gateway

### 3.1 How it happens

Element X and FluffyChat each run a gateway discovery against the endpoint's origin (`GET /_matrix/push/v1/notify`)
whenever they get a `NEW_ENDPOINT`. If it fails (no network at that moment, a captive portal, DNS, our server briefly
down), Element X takes its stored gateway, else `https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify`
(design 108 §6.2); FluffyChat takes unifiedpush.org's on any error (`_newUpEndpoint`'s `catch`). That gateway re-posts
the Matrix JSON to our `/up/<secret>`, so delivery still works, with a third party in the path (it sees the event and
room ids, the unread count and the pushkey). R1 already notes such a push (`outside_gateway_at`).

### 3.2 The mark (server)

- **Migration 0153** adds `up_registrations.gateway_at TEXT`: the last push queued through **our own** gateway
  (`matrix::notify`), written as `outside_gateway_at` is - only for a queued push, at most once a minute (the same
  conditional `UPDATE`).
- **Marked** = `outside_gateway_at` within the last 7 days **and** (`gateway_at IS NULL` or `gateway_at <
  outside_gateway_at`). So the mark ends at the first push through our gateway, and fades by itself after 7 quiet
  days. A homeserver that still holds both pushers (one per gateway) flips the mark with each push; the PWA shows it
  while it is set at render time, which is fine for a warning.

### 3.3 The heal (phone): a fresh `NEW_ENDPOINT` once a day while marked

**Checked in the sources:** a `NEW_ENDPOINT` re-runs the discovery and re-sets the pusher in both apps.
- Element X: `VectorUnifiedPushMessagingReceiver.onNewEndpoint` calls `unifiedPushGatewayResolver.getGateway(endpoint)`,
  stores the gateway and calls `pusherSubscriber.registerPusher(client, endpoint, gateway)` (739f0b4, lines 92-114).
  The pusher's `app_id` and `pushkey` are unchanged, so the homeserver replaces the old one's gateway URL.
- FluffyChat: `_newUpEndpoint` makes the discovery GET and calls `setupPusher` for every client, which replaces a pusher
  of the same `app_id` whose URL differs.

So the phone can heal the mark without anyone touching the app:

- **The policy** gains `unified_push.outside`: the phone's marked `reg`s, sorted (an empty list when none). Contract
  rows in §7.
- **The launcher** (pure `UpRules.refreshes(rows, outside, nowMs)`): a confirmed row whose `reg` is in `outside`, not
  rewritten in this pass, and whose new `UpRow.refreshMs` is null or 24 h old gets `NEW_ENDPOINT(token, endpoint, id =
  new)`; `refreshMs` is set. It runs in `policyAccepted`, i.e. right after a successful policy fetch, so the phone has a
  network when the app re-checks. Once a day at most: the mark stays up to 7 days after the last outside push, and an
  app whose discovery keeps failing is asked once a day, not every sync.
- The app's ack of that `NEW_ENDPOINT` also counts for liveness (§2).
- A pending row is never refreshed; an older server (no `outside`) means none.

### 3.4 The PWA

The app's row in Device › Apps › App push gets a warning line while any of its registrations is marked:

- nb: "Varslene går gjennom unifiedpush.org, utenfor familiens server. Vibb One ber {app} sjekke adressen på nytt.
  Hjelper ikke det: {fix}"
- en: "Notifications go through unifiedpush.org, outside the family's server. Vibb One asks {app} to check the address
  again. If that doesn't help: {fix}"

`{fix}` per app, from `KNOWN_UP_APPS` (which gains a fix key per app):
- Element X: nb "I Element X: Innstillinger › Varsler › Avansert › Push-leverandør: velg Google, og så Vibb One igjen,
  for hver konto.", en "In Element X: Settings › Notifications › Advanced › Push provider: pick Google, then Vibb One
  again, for each account.";
- FluffyChat: nb "Lukk FluffyChat helt og åpne den igjen.", en "Close FluffyChat fully and open it again." (it registers
  at every start, and our answer from the store is a `NEW_ENDPOINT`);
- any other app: nb "Åpne {app} igjen.", en "Open {app} again.".

If "Last seen from outside" on `/settings/push` shows no recent "Address checked by an app", the phones' address itself
isn't reachable; the warning's text doesn't try to diagnose that. No save on this page changes: nothing can jump.

---

## 4. R3-a: VAPID (RFC 8292)

### 4.1 When it applies

Only to a registration made with a `vapid` key (the app passed it at REGISTER; the phone passes it on, R1). Without a
key nothing changes: unsigned and signed pushes are taken, as ntfy does. **Never required** (no `VAPID_REQUIRED`): Element
X, FluffyChat and Pachli register without a key.

### 4.2 The header forms (both, and Mastodon's exact spelling)

Read in Mastodon's sender (43126b2, `WebPushRequest`, `Web::PushNotificationWorker`), the most likely real sender:

| Form | Headers | Who |
|---|---|---|
| RFC 8292 | `Authorization: vapid t=<jwt>,k=<key>` - Mastodon writes **no space** after the comma; others write one; order may differ; values may be quoted (auth-param syntax) | Mastodon 4.4+ for a `standard: true` subscription (`aes128gcm`), most libraries |
| draft (older) | `Authorization: WebPush <jwt>` and `Crypto-Key: dh=<…>;p256ecdsa=<key>` (`;` or `,` between params) | Mastodon for a legacy subscription (`aesgcm`), older libraries |

The JWT (Mastodon's): header `{"typ":"JWT","alg":"ES256"}`, claims `aud` = the endpoint's `normalized_site`
(`https://phones.example.com`, no default port), `exp` = now + 24 h exactly, `sub` = `mailto:…`.

### 4.3 The check (`server/src/unified_push/vapid.rs`, pure, `now` passed in)

1. **Form.** An `Authorization` of either form, at most 1024 bytes; for `WebPush`, the key from `Crypto-Key`'s
   `p256ecdsa`. Neither form, or no key: **missing**.
2. **The key.** `k` base64url (padding and the standard alphabet tolerated) decoding to 65 bytes. Not the registered
   key: **wrong key**.
3. **The JWT.** Three base64url parts; the header's `alg` is `ES256` (any `typ`); the signature is 64 bytes (JWS's
   fixed r‖s; ASN.1 is refused - RFC 7518 §3.4), verified with `ring`'s `ECDSA_P256_SHA256_FIXED` over `header.claims`
   with **the registered key** (never the request's own).
4. **`aud`** parsed as a URL and normalised (lowercase scheme and host, port 443 dropped) equals the current public
   origin or one in `up_previous_origins` within its 30 days (M4: an app server still on the old address signs for it).
5. **`exp`** in `[now - 5 min, now + 24 h + 5 min]` (RFC 8292 §2: in the future and at most 24 h out; 5 minutes for
   clock skew, since Mastodon signs exactly 24 h ahead and our clock may lag its).
6. `sub` is not checked (RFC 8292: a push service MAY ask for it; nothing uses it here).

Any failure in 3-5 is **invalid**.

### 4.4 The answers, and where the check sits in `/up/`

The order in `publish`: the size checks, the secret's lookup (a gone secret still gets 410, or 404 for a Matrix body),
**then the VAPID check**, then the buckets and the queue (`accept`).

| Case | Answer |
|---|---|
| no key registered | as today (201, 429, 507 …) |
| a key registered, the push verifies | as today |
| missing or invalid | **401**, `WWW-Authenticate: vapid` |
| a valid JWT for another key | **403** |

- Neither counts toward the client's ban (the secret resolved; R1's M5 rule).
- **Before the buckets**, so a leaked endpoint without the private key can't spend the phone's bucket or wake it. The
  check's cost (one ECDSA verify, ~0.1 ms) is bounded by the edge's per-client bucket (40, then 20 a second; R1-SR L3).
- A refusal writes `up_registrations.vapid_refused_at` (migration 0153), at most once a minute, for the PWA.
- **What a 4xx does at the sender:** Mastodon destroys the subscription on any 4xx but 408/429
  (`push_notification_worker.rb`'s `send`). That is the RFC's intent - a push that isn't the app server's is no
  subscription - but a check of ours that fails a real sender (a bug, or our clock badly wrong) silently ends that app's
  push until it re-subscribes; Fedilab does that at each start (§6.2). This is Q3.

### 4.5 The gateway must not bypass it

A homeserver never signs. `POST /_matrix/push/v1/notify` with a pushkey whose registration has a key is answered with
the pushkey in `rejected` (as a pushkey that isn't ours), so nobody can push a keyed endpoint unsigned through the
gateway. No Matrix app registers a key (Element X: `RegisterUnifiedPushUseCase` passes `instance` and
`messageForDistributor` only; FluffyChat: `UnifiedPush.register(instance, features)`), so this costs nothing. A Matrix
body from unifiedpush.org's gateway to a keyed `/up/` gets 401, which common-proxies treats as a rejected pushkey.

### 4.6 The key at registration (both)

- **The server** checks that a `vapid` decodes to an uncompressed P-256 point on the curve (65 bytes, `0x04`, and
  `ring`'s `agreement::agree_ephemeral` with a throwaway key accepts it - `webpush.rs` already uses `ring::agreement`).
  Otherwise **400** (the contract's `invalid`), which the phone already maps to `REGISTRATION_FAILED(ACTION_REQUIRED)`.
  A key that can never verify must not be stored: every push would be refused and the sender would drop the
  subscription.
- **A changed key.** Today a REGISTER of a known, confirmed token is answered from the phone's store with no network
  (the spec's "SHOULD NOT create a new endpoint if a valid registration exist[s] for the token **and nothing has been
  updated**"). R3: when the request's `vapid` differs from the row's (added, removed or another key), `registerDecision`
  answers a new `Update(row)`: the row's `vapid` replaced, the same `reg` and secret posted again; the server's
  "a retry of the same `reg` and secret" branch now also **updates the stored key** (and clears `vapid_refused_at`), and
  answers the same endpoint; the phone sends `NEW_ENDPOINT` with it. A pending row is retried with the new key. Only the
  app itself can do this (its token, its shared identity), so removing a key is no downgrade an outsider can make.
- **Order of release:** none needed. R1 phones already send keys and R1 servers already store them; an R3 server
  verifies those at once. An R1 phone never updates a changed key, which only matters if an app server rotates its key
  (rare; Mastodon's own subscriptions break on a rotation too).

### 4.7 The PWA

The app's row, when any of its registrations has a key: nb "Bare push signert av appens server", en "Only pushes signed by
the app's server". A warning while `vapid_refused_at` is within 7 days: nb "Push som ikke var signert av appens server, ble
avvist, sist {when}", en "Pushes not signed by the app's server were refused, last {when}". Neither is a save.

### 4.8 The server's clock

The `exp` check depends on it. The server already runs on a VM with NTP (and design 35's own Web Push signs with it), so
DEPLOY.md's "App push" section gains one line: the server's clock must be right, or signed pushes are refused.

---

## 5. R3-b: the Urgency hold (Q2)

The spec (`android.md`, MESSAGE): "only messages more urgent than the minimum urgency SHOULD be send", the minimum by the
device's state: `very-low` on power and Wi-Fi, `low` on either, `normal` on neither, `high` on a low battery.

**What our senders set** (read in the sources): Mastodon always `Urgency: normal`; our gateway maps Matrix `prio: high`
to `high` and everything else to `normal` (R1, `matrix.rs`). So for Element X, FluffyChat and Fedilab the hold would change
nothing - except the spec's low-battery row, which would **hold every ordinary chat message** (`normal`) while the
battery is low: a kid's phone at 15 % would stop showing Mamma's messages in a group chat. Only a sender that sets
`low`/`very-low` itself (none of the apps above) would see the rest of the rule.

**Recommended (Q2 = a): no hold.** R1's behaviour stays, and the deviation stays stated (design 108 §12.1).

**If the user picks (b), hold only `very-low` and `low`, never `normal` or `high`, and skip the low-battery row:**
- the pull's body gains `min_urgency` (`"very-low"|"low"|"normal"`); the server returns only messages at or above it and
  counts `more` the same way; held rows stay queued until their TTL (at most 24 h, so a phone that never charges or
  sees Wi-Fi in a day loses them - their senders said they could wait);
- the phone sets it from its state (`normal` on neither power nor Wi-Fi, `low` on either, `very-low` on both) and pulls
  again on a change: the anchor's existing network callback for Wi-Fi, a context-registered `ACTION_POWER_CONNECTED`
  receiver in the anchor's process (manifest receivers don't get it since Android 8; the same on 14-17);
- the contract's `messages` gains a `body_min_urgency` row and an answer that leaves a `low` message out;
- size: server XS, launcher S-.

---

## 6. R3-c: other apps (checked in their sources)

### 6.1 FluffyChat: works with R1 as it is

- **Connector:** `unifiedpush` 6.2.0 → `unifiedpush_android` 3.4.1 → `org.unifiedpush.android:connector:3.1.2`.
  REGISTER and UNREGISTER go with `setShareIdentityEnabled(true)` on SDK 34+ (`UnifiedPush.register`), so our
  `getSentFromPackage()` identity holds. MESSAGE_ACK as Element X's connector (plain broadcast, token only).
- **No VAPID key, no MXID:** `UnifiedPush.register(instance: 'default', features: [])` - one registration for all its
  accounts (the PWA says "FluffyChat · 1 account" however many are logged in).
- **No embedded FCM distributor** (no `embedded-fcm-distributor` in its Gradle files). Its own FCM path is separate:
  `setupPush` takes UnifiedPush **whenever any distributor exists** (`UnifiedPush.getDistributors().isNotEmpty`), even in
  the Google build. `unifiedpush_ui`'s `registerAppWithDialog`: a saved distributor is used; else with exactly one
  (Vibb One on a kid's phone) it is saved without a dialog. **So FluffyChat picks Vibb One by itself**, at every start of
  its chat list (`chat_list.dart:440`), and replaces an earlier Google pusher of the same app id.
- **Matrix gateway:** its discovery GETs `<endpoint origin>/_matrix/push/v1/notify` and takes ours when the answer is
  `{"unifiedpush":{"gateway":"matrix"}}` (or the older `{"gateway":"matrix"}`); any error → unifiedpush.org's (§3).
- **The pusher:** `format: event_id_only`, `data_message: true`, `app_id` `chat.fluffy.fluffychat.<device id>`.
  `_onUpMessage` reads `json.decode(message)['notification']` and fetches the event itself, so the minimised body (Q8)
  loses nothing.
- **The raise:** connector 3.1.2's manifest declares the `RAISE_TO_FOREGROUND` service.
- **Builds:** GitHub releases' `fluffychat.apk` (`flutter build apk`, all ABIs including x86_64; with Firebase), Play
  (with Firebase), its F-Droid repository. Any of them works.
- `KNOWN_UP_APPS` gains `chat.fluffy.fluffychat` ("FluffyChat") with its own "isn't using Vibb" text: nb "{app} bruker
  ikke Vibb for varsler. Åpne {app} én gang, så velger den Vibb One selv.", en "{app} isn't using Vibb for
  notifications. Open {app} once: it picks Vibb One by itself."

### 6.2 The VAPID path: Fedilab with a Mastodon 4.4+ account

- **Fedilab** (connector 3.0.9: shares its identity) fetches the instance's VAPID key (`/api/v2/instance`'s
  `configuration.vapid.public_key`, else Pleroma's) and registers with it: `UnifiedPush.register(context,
  "<user_id>@<instance>", null, vapid)` (`PushHelper.java:193`). On `onNewEndpoint` it subscribes the Mastodon server to
  our endpoint with **`subscription[standard]=true`** (`NotificationsVM`), so Mastodon 4.4+ sends RFC 8291 `aes128gcm`
  signed `vapid t=…,k=…` (§4.2), which the connector decrypts.
- **Flavours:** the F-Droid flavour `fr.gouv.etalab.mastodon` has no FCM: with Vibb One the only distributor it saves it
  by itself. The Play flavour `app.fedilab.android` embeds the FCM distributor, so it sees two and saves neither; there
  the distributor is picked in Fedilab's own settings. **Use the F-Droid flavour** (the server's F-Droid catalog).
- **Mastodon's sender** (§4.2): `TTL 48h` (we clamp to 24 h), `Urgency: normal`, a 4xx but 408/429 destroys the
  subscription, a 5xx is retried 5 times.
- **Also VAPID, for later:** DAVx⁵ (connector 3.3.5) registers **only** when its WebDAV-Push server advertises a VAPID
  key (`PushRegistrationManager`), and finds its distributor through `resolveDefaultDistributor`, i.e. our Link
  Activity's preferred activity (R1's L3) - but it needs a WebDAV-Push server (Nextcloud with the DAV Push app).
- **Not VAPID:** Pachli (connector 3.0.10) registers without a key; mastodon-android has no UnifiedPush; **Tusky** (on
  GitHub, last commit 2025-05) uses connector **2.4.0**, an AND_2 connector without the shared identity, so it can't
  register with Vibb One at all (design 108 §5.1, L4's deliberate deviation).

### 6.3 What each test needs from the user (on the phone)

**FluffyChat, now (R1 is enough; R2's warning and heal come later):**
1. PWA, Device › Apps: allow FluffyChat and install it (Play, or a catalog row: GitHub `krille-chan/fluffychat`, asset
   `fluffychat.apk`). App push on, the address saved.
2. On the phone: open FluffyChat and log in as Emma on the family's homeserver. Nothing to pick.
3. PWA › App push: "FluffyChat · 1 account". In FluffyChat › Settings › Notifications: the distributor line names Vibb
   One, and the pusher's URL is the phones' address + `/_matrix/push/v1/notify` (if it is
   `matrix.gateway.unifiedpush.org`, the discovery failed: R2's case).
4. With the phone's screen off, send Emma a message from Pappa's account: the notification comes. Note the delay
   against Element X on Google if both are there.

**Fedilab and Mastodon, after R3 is released:**
1. A test Mastodon account (Emma) on a 4.4+ server. PWA: allow Fedilab (F-Droid flavour) and install it.
2. On the phone: log in. Fedilab registers with the server's key and subscribes Mastodon to our endpoint.
3. PWA › App push: "Fedilab · 1 account" and "Only pushes signed by the app's server".
4. From Pappa's account, reply to or favourite Emma's post: the notification comes, and no "refused" warning shows.

---

## 7. The shared contract and the migration

**`server/testdata/unified_push.json`** (byte-identical in `launcher/app/src/test/resources/`), in the contract commit:

- every `policy` row gains `"outside": []`, and a new row `outside_one` (`on_two` with `"outside":
  ["c0ffee00000000000000000000000002"]`); `older_server` is unchanged (no key: no refresh);
- `register` gains `body_vapid` (`reg` …03, secret …003, `org.unifiedpush.example`, `vapid` = the test key's public half,
  §10), `body_vapid_changed` (the same `reg` and secret, another valid key) and `body_vapid_not_a_point` (87 base64url
  that isn't a P-256 point); `answers` gains `ok_vapid` (200, the endpoint), `vapid_changed` (200, the **same**
  endpoint) and `vapid_not_a_point` (400, `null`).

The server's `tests::unified_push::the_contract` produces the new rows (and checks the stored key after
`vapid_changed`); the launcher's `UnifiedPushContractTest` decodes `outside` and encodes `body_vapid` and
`body_vapid_changed` exactly. `policy_json_keys_snapshot` and `PolicyResponseCompatTest` gain `unified_push.outside`.
An R1 launcher ignores the key (`ServerJson` has `ignoreUnknownKeys`). No new capability: `outside` is additive, and a key
update sent to an R1 server is answered with the same endpoint (the key just isn't updated there).

**Migration `0153_up_gateway_and_vapid.sql`** (0153 is the next free number on every branch, checked 2026-11-17):

```sql
-- Design 108b: the last push through our own Matrix gateway (R2: it ends the outside-gateway mark), and the last
-- push refused by the VAPID check (R3: the PWA's warning). Both written at most once a minute.
ALTER TABLE up_registrations ADD COLUMN gateway_at TEXT;
ALTER TABLE up_registrations ADD COLUMN vapid_refused_at TEXT;
```

**Server-only:** `server/testdata/unified_push_vapid.json` (§10) and two `unified_push_gateway.json` rows (a keyed
registration's pushkey `rejected`; a gateway push setting `gateway_at`). **Launcher-only:** `up_rules.json` gains
`liveness`, `refresh` and the register rows `a known token with another key` → `update:<reg>`, `a known token with the
same key` → `known:<reg>`, `a known token whose key is dropped` → `update:<reg>`.

CI path lists don't change (the same files). The root `CLAUDE.md`'s `unified_push.json` paragraph gains `outside` and
the VAPID register rows, and its scripts line gains `fake-homeserver.py --vapid`.

---

## 8. Words (en and nb)

Server (`src/i18n/en.json`, `nb.json`; `i18n::tests` and `nb_pages_have_no_english` check them): `app_push.outside`,
`app_push.outside_fix.element_x`, `app_push.outside_fix.fluffychat`, `app_push.outside_fix.other`, `app_push.signed`,
`app_push.vapid_refused`, `app_push.missing_open` (§3.4, §4.7, §6.1). The existing `app_push.missing` becomes Element X's
text by key (`KNOWN_UP_APPS` holds `(package, name, missing_key, fix_key)`).

Launcher (`values/strings.xml`, `values-nb/strings.xml`): the diagnostics note `settings_mdm_up_note_unanswered` (§2).
Nothing new on the kid's screens.

---

## 9. Android 14-17

- **Liveness and the heal** send the same `NEW_ENDPOINT` and `UNREGISTERED` broadcasts R1 sends (explicit action,
  `setPackage`, `FLAG_INCLUDE_STOPPED_PACKAGES`), passed on 14 and 16 in R1-G (rows 1, 6, 13, 14). No new API, no new
  manifest entry, no new permission.
- **R3 on the phone** is the register decision only.
- **Q2's option (b)** would add a context-registered `ACTION_POWER_CONNECTED`/`DISCONNECTED` receiver in the anchor's
  process: the same on 14-17 (manifest receivers lost it in 8.0).
- **17:** API 37's behaviour-change list is read at the build for broadcasts; the gate's 17 run stays owed (no API 37
  image boots on this VM, R1-G).

---

## 10. Tests (lean)

**Server:**
- `unified_push::vapid::tests::the_rows`: every row of `server/testdata/unified_push_vapid.json`, through the pure check
  with the row's `now` and origins - `rfc_mastodon` (Mastodon's exact spelling, no space), `rfc_spaced` (`t=…, k=…`,
  reversed order), `draft_webpush` (`WebPush` + `Crypto-Key: dh=…;p256ecdsa=…`), `missing`, `wrong_key` (403),
  `bad_signature`, `asn1_signature`, `alg_none`, `expired`, `exp_too_far`, `skew_ok` (exp 24 h + 2 min), `wrong_aud`,
  `aud_with_port_443` (ok), `previous_origin` (ok), `previous_origin_ended`, `oversized`.
- `unified_push::vapid::tests::points`: the contract's test key and the not-a-point key.
- `tests::unified_push::the_contract` (the new rows), `gateway_rows` (the two new rows), and
  `vapid_at_the_route`: a keyed registration answers `missing` 401 and `wrong_key` 403 at `/up/` without charging the
  phone's bucket and without counting toward the ban, and an unkeyed one takes the same unsigned push (201).
- `tests::unified_push::outside_mark`: a Matrix body at `/up/` marks, the policy lists the `reg`, a gateway push ends it.
- `policy_json_keys_snapshot`, `i18n::tests` (automatic).

**The vectors** are made once by `scripts/fake-homeserver.py --write-vapid-vectors` with a fixed `now`, signing with the
test key; its `--selftest` verifies every row's signature with its own pure-Python verify (the outcome must match the
row) and checks the contract's `body_vapid` key is its key. **The test key:** a P-256 private scalar derived from the
SHA-256 of the phrase "vibb fake vapid key - test only, never a real sender" (secrets rule: fake keys look fake); only
its public half is in the contract.

**Launcher:**
- `UpRulesTest` from `up_rules.json`: `liveness` (no ping before 23 days; ping at 23; an ack clears it; dead at 7 days
  after the ping; a pending row never; a clock moved back does nothing), `refresh` (marked and never refreshed → sent;
  refreshed 2 h ago → not; 25 h ago → sent; a pending row never; a rewritten row not twice), the register rows above.
- `UpEngineTest`: one case each - a dead row is removed with `UNREGISTERED`, its `DELETE` queued and the note written; an
  `Update` posts the same `reg` and secret with the new key and answers `NEW_ENDPOINT`.
- `UnifiedPushContractTest`, `PolicyResponseCompatTest`.

---

## 11. The emulator gate (R2R3-G), Android 14 and 16

The rig of `docs/testing/emulator.md` §6ay (`compat34`, `jelly36`, the debug server and launcher, the origin
`https://up.test`). Two additions:

- **The probe** (`me.vibb.probe.up`) gains a `vapid` extra on REGISTER (the test key's public half, or another), an
  "ack off" switch (it stops sending MESSAGE_ACK), and a re-REGISTER of the same token with or without the key.
- **`scripts/fake-homeserver.py`** gains `--vapid rfc|rfc-spaced|draft|none|wrong-key|expired|wrong-aud` (pure-Python
  ES256 on P-256 with the test key, standard library only; `secrets` for the nonce) and `--outside` (a Matrix-shaped
  body posted to `/up/<secret>`, as unifiedpush.org's gateway does). Its `--selftest` (run by `server-ci.yml`'s changes
  job already) covers both.

| Row | What | Expected |
|---|---|---|
| 1 ping | the probe registered; `adb shell su 0 date` 24 days on; a sync | the probe gets `NEW_ENDPOINT`, the same endpoint, a new id; it acks; no second ping at the next sync |
| 2 dead | "ack off"; 24 days on (ping), then 8 more; a sync each | `UNREGISTERED` to the probe; a POST to its endpoint 410; the PIN-gated Settings row shows the note. The snapshot restored afterwards (the clock) |
| 3 outside | `--outside` to the probe's endpoint | the PWA's warning on the probe's row; the next policy lists the `reg`; the probe gets `NEW_ENDPOINT` within that sync; no second one at the next sync |
| 4 healed | a gateway POST (our `notify`) for the probe's pushkey | the PWA's warning gone; `outside` empty in the next policy |
| 5 VAPID | the probe registered with the test key; `--vapid rfc`, `rfc-spaced`, `draft` | 201 and delivered, each; the PWA shows "Only pushes signed…" |
| 6 refused | `--vapid none`, `wrong-key`, `expired`, `wrong-aud`; a gateway POST for the pushkey | 401, 403, 401, 401, nothing delivered, the phone not nudged; the gateway `rejected`; the PWA's refused warning |
| 7 key update | the probe re-REGISTERs the same token without the key; an unsigned push | `NEW_ENDPOINT` with the same endpoint; 201 and delivered; the "signed" line gone |

Rows 1-2 and 5-7 on both versions; 3-4 are server-and-policy rows and run on one (16). **Owed on the Jelly Star (the
user's tests, §6.3):** FluffyChat end to end (it can't log in on this VM: it needs a homeserver that can reach the push
address), Fedilab with Mastodon's real signed pushes, and Element X (R1-E).

---

## 12. Rounds

| Round | Size | What |
|---|---|---|
| **R23-C** | XS | the contract rows (both copies), migration 0153, the policy's `outside` (stubbed empty), the snapshot and compat test, the root `CLAUDE.md` lines: one commit both worktrees start from |
| **R23-S** (server) | S | `vapid.rs` (the two forms, the check, the point check) and its vectors; the hook in `publish` before `accept`; the gateway's refusal of keyed registrations; `gateway_at` and the mark; the key update in the register retry; the PWA lines and words; `KNOWN_UP_APPS` (FluffyChat, the fix keys); DEPLOY.md's clock line; the notes |
| **R23-L** (launcher) | S | `UpRules.liveness` and `refreshes`, `UpRow.refreshMs`, both run in `policyAccepted`; `RegisterDecision.Update`; `NOTE_UNANSWERED` and its words; `up_rules.json`; the notes |
| code QA | S each | an Opus pass per side; the server's with a security focus on `vapid.rs` (attacker-controlled headers and crypto on a public route), in place of a separate review |
| **R23-G** | S | the probe's and `fake-homeserver.py`'s additions, §11's rows on 14 and 16 |
| release | - | the server tag and the launcher RC (either order, §4.6, §7); then the user's FluffyChat and Fedilab tests |

R23-S and R23-L run in parallel (two worktrees). **If Q2 = (b):** + XS server, + S- launcher, + one gate row. The
FluffyChat test needs none of this and can run on today's release.

---

## 13. Questions for the user

1. **Q1 (R2): unregister a registration automatically when its app hasn't answered for 30 days** (a ping at day 23, the
   end at day 30, the spec's rule)? Its cost: an app that is somehow alive but never answers loses its push until its
   next start, when it registers anew (Element X and FluffyChat register at every start). A suspended, stopped or
   unopened app always answers (Android delivers to it and the connector answers by itself). **Recommended: yes.**
2. **Q2 (R3): the Urgency hold.** (a) **No hold - every push at once, as today (recommended).** (b) Hold only
   `very-low` and `low` until Wi-Fi or a charger, never ordinary or urgent ones, and not the spec's low-battery rule
   (that one would hold every ordinary chat message on a low battery). None of the apps we know sends `low`, so (b)
   changes nothing for them today.
3. **Q3 (R3): a push that fails the VAPID check is refused with 401/403, as RFC 8292 says.** Its cost: Mastodon then
   deletes that app's push subscription, so if our check ever wrongly refuses a real sender (a bug, or the server's clock
   badly off), Fedilab gets no push until it is opened again. The other way (answer 503: the sender retries a few times
   and keeps the subscription) would keep pushing a forged sender's attempts back at us. **Recommended: 401/403**, with
   the PWA's "refused" warning to show it if it happens.

---

## 14. Risks and checks owed

1. **A check that refuses a real sender** (Q3): covered by Mastodon's exact spelling in the vectors (no space, `typ`,
   `aud` without a port, `exp` at exactly 24 h, the 5-minute skew) and by the user's Fedilab test.
2. **The heal depends on the apps' `onNewEndpoint`** re-running the discovery (checked in today's Element X and
   FluffyChat). If an app stops doing it, the PWA's warning stays and its fix text is the way.
3. **A forward clock jump** pings every registration early; a live app answers at once. Only a dead one is removed.
4. **Tusky can't use Vibb One** (an AND_2 connector) until it moves to connector 3.x; Pachli works without VAPID.
5. **Android 17** is owed, as for R1.
