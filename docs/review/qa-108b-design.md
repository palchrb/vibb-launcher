# Design QA: design 108b, app push R2 and R3 (`docs/design/108b-unified-push-r2-r3.md`)

Reviewed on `up-r2r3` at ee101b19c, 2026-11-17. Read: design 108b in full; design 108's R1 notes, §16 and the Built
sections it cites; `docs/notes/server/unified-push.md`, `docs/notes/launcher/unified-push.md`; the R1 code
(`server/src/unified_push/{publish,device,store,mod}.rs`, `migrations/0152_unified_push.sql`, the launcher's
`push/up/UpRules.kt`, `UpEngine.kt`, `UpStore.kt`, `UpContract.kt`, `UpRuntime.kt`'s broadcasts, `AppEnforcer`'s date
lock, `MdmSyncWorker`'s `policyAccepted` call and the 304 path); the contract `server/testdata/unified_push.json` (byte-
identical in the launcher, checked with `cmp`); the root, server and launcher CLAUDE.md rules.

**Sources spot-checked** (the session's scratchpad clones the design names; read as data):
- android-connector **3.0.9** and **3.1.2**: `MessagingReceiver.onReceive` returns at `getInstance(token)` /
  `tryGetInstance(token)` for an unknown token, acknowledges `NEW_ENDPOINT` and `MESSAGE` only after the app's callback
  returns, as a plain `sendBroadcast` - holds. **3.0.9's `ACTION_REGISTRATION_FAILED` calls
  `store.registrationSet.removeInstance(instance, keyManager)`**; 3.1.2's only calls `onRegistrationFailed` (H1).
  3.0.9's `register` keeps the instance's token (`newOrUpdate`) and puts the `vapid` extra only when non-null (H1).
- FluffyChat 6a0fda5: `UnifiedPush.register(instance, features)` without a key; `unifiedpush_ui`'s
  `registerAppWithDialog` saves a single distributor without a dialog; `_newUpEndpoint`'s discovery GET accepts both
  answer shapes and falls to unifiedpush.org on any error; `setupPusher` deletes every pusher of its app id, then posts
  (`append: true`); `main.dart` handles a background start with its clients (`BackgroundPush.clientOnly`) and starts a
  foreground service `background_push` - all hold. `unifiedpush_android` 3.4.1 pins connector 3.1.2 - holds.
- Fedilab 1dfcb7f: connector 3.0.9; the embedded FCM distributor only in `playstoreImplementation`; `PushHelper`
  fetches `/api/v2/instance` (else Pleroma's) **at every start** and registers with the key, or **with `null` when the
  fetch failed**; `NotificationsVM` subscribes with `standard=true`; `PushServiceImpl.onNewEndpoint` re-subscribes -
  the design's claims hold; the `null` path is H1.
- Mastodon 43126b2: `t=…,k=…` with no space, `aud` = `normalized_site`, `exp` = now + 24 h, `TTL` 48 h,
  `Urgency: normal`, any 4xx but 408/429 destroys the subscription, a 5xx raises (Sidekiq `retry: 5`) - holds.
- Element X 739f0b4 (a sparse clone, `libraries/pushproviders/unifiedpush` only): `onNewEndpoint` resolves the gateway
  in a launched coroutine; on a discovery `Error` it keeps the **stored** gateway, else the default; `onUnregistered`
  re-registers at once (`rotateRegistration`) - holds. Its start-time registration (`ensurePusherIsRegistered`) is
  outside the sparse checkout: not re-checked, design 108 cites it.
- Tusky connector 2.4.0, Pachli 3.0.10, DAVx⁵ `PushRegistrationManager` registers only with a key; the spec's
  ping/unregister text and the urgency table - hold.
- Android platform behaviour (Doze, App Standby, the cached-apps freezer on 14+) is from platform knowledge, not from
  source read here.

**Verdict: sound in shape, one High to fix before R23-L is built, and Q3 needs re-framing before it goes to the
user.** The liveness rule (R2-a) is right and its false-positive analysis holds against the connectors. The VAPID check
is the right check; its bounds and order need tightening. The outside-gateway heal works in the apps' code but, as
specified, fires when the apps mostly can't use the network.

---

## High

### H1. A REGISTER without the key, or any key update while offline, breaks Fedilab - the VAPID path's own app

**Failure.** Fedilab's `PushHelper.registerAppWithDialog` fetches the instance's key at **every** start and calls
`UnifiedPush.register(context, "<id>@<instance>", null, finalVapid)` with `finalVapid = null` whenever that fetch
failed (no network yet, the instance slow or down). Connector 3.0.9 keeps the instance's token (`newOrUpdate`), so the
phone sees a known, confirmed, keyed token **with the key dropped** - §4.6 makes that an `Update`.
- **Offline start** (the everyday case: Fedilab opened before the network is up): the `Update`'s server call fails,
  and R1's `UpEngine.call` answers `REGISTRATION_FAILED(NETWORK)`. Connector 3.0.9 then **removes the instance and its
  Web Push keys**. Every later push to the old endpoint reaches a token the connector no longer knows: dropped, no ack.
  Fedilab's notifications are dead until it is next started with a network. Then a new token gives a new registration.
  The old row lingers until R2's liveness removes it 30 days on. R1 answered this REGISTER from the store with no
  network (`Known`), so this is a regression R3 introduces.
- **Online, instance fetch failed**: the server's key is removed. Pushes are no longer checked, and the PWA's "Only
  pushes signed by the app's server" goes away. All of that is silent until the next good start puts the key back. Each
  flip costs a server call and a `NEW_ENDPOINT`, and Fedilab re-subscribes Mastodon each time.

**Fix.**
1. A REGISTER **without** `vapid` for a keyed row changes nothing (`Known`). Only a **different valid key** is an
   update. Dropping a key takes UNREGISTER + REGISTER. `up_rules.json`: "a known token whose key is dropped" ->
   `known:<reg>`, not `update:<reg>`. State the reason in §4.6: Fedilab sends `null` on a failed fetch.
2. An `Update` of a **confirmed** row never answers `REGISTRATION_FAILED` for no network or a 5xx. It answers
   `NEW_ENDPOINT` from the store, because the old endpoint and the old key still work. It keeps the new key as
   `UpRow.vapidPending` and posts it from `policyAccepted` until a 200 clears it. A 400 (not a point) keeps the row and
   its old key, answers `NEW_ENDPOINT` and writes a diagnostics note. If the spec's failure is preferred instead, drop the
   row with its `DELETE` queued; §4.6 must say which.
3. Tests: an `UpEngineTest` case (an `Update` against a failing server -> `NEW_ENDPOINT` with the old endpoint, no
   `REGISTRATION_FAILED`, the key posted at the next policy). Gate row 7b: the probe re-REGISTERs with another key while
   the server is unreachable.

---

## Medium

### M1. The heal's `NEW_ENDPOINT` is a plain broadcast at backstop syncs, when the app usually has no network

**Failure.** R1's `newEndpoint` (`UpRuntime.kt:319`) is a plain broadcast with no raise. The connector acknowledges it
synchronously. Element X's `onNewEndpoint` launches a coroutine and returns. FluffyChat's goes through its
`PushService` to a Flutter engine, asynchronously. So the discovery GET and the pusher POST run **after** `onReceive`
returned, in a background process. In Doze and App Standby a non-exempt app has no network, and on 14+ the cached-apps
freezer stops it seconds later. §3.3 runs the heal "right after a successful policy fetch", which is true for the
launcher (DO, FGS-anchored) but not for the app. The 30-minute backstop syncs fall mostly while the phone lies idle
(night, school). So in the field the heal mostly fails. FluffyChat's `catch` picks unifiedpush.org again, and Element X
keeps its stored gateway. Nothing gets worse, but the PWA's line "Vibb One asks {app} to check the address again"
is mostly untrue. On the emulator (awake, charging) gate row 3 passes anyway, and the probe isn't a Matrix app, so the
gate can't see this.

**Fix.** Send the refresh's `NEW_ENDPOINT` through the raise: bind `RAISE_TO_FOREGROUND` for 5 s, R1's MESSAGE
machinery. The connector declares the service (3.1.2's manifest; FluffyChat inherits it). Send it only while the phone
is interactive and not device-idle, e.g. at the first accepted policy after `USER_PRESENT`/screen on, keeping the 24 h
spacing from an attempt actually made. Note in §3.3 that R1's origin rewrite has the same exposure. Gate row 3: run with
the screen off after `dumpsys deviceidle force-idle` and record what the probe sees. The heal itself (the discovery
reaching the server, `last_discovery_at`) is owed with FluffyChat on the Jelly Star, phone idle; add it to §11's
"owed" list.

### M2. Q3 is framed with a wrong cost for the alternative, and the better options are missing

**Failure.** §13 Q3 says that answering 503 "would keep pushing a forged sender's attempts back at us". A forger's
behaviour doesn't depend on our status code. Only a real sender reacts to it: Mastodon retries a 5xx 5 times and keeps
the subscription, and destroys it on a 4xx. The real trade is this. A 4xx follows RFC 8292 and tells a real sender
whose key is wrong to stop. A 5xx keeps a real subscription alive across a fault of ours (clock, origin config, a bug in
`vapid.rs`). The user is asked to choose without that picture (the user's rule: a visible-cost choice goes to them,
framed fairly).

**Fix.** Re-frame Q3 with the two costs above and offer:
- **(c) split by cause.** 401/403 for what the sender can't fix by waiting: no `Authorization`, wrong `k`, a bad
  signature, a wrong `alg`. 503 + `Retry-After` for what may be ours: `exp` and `aud`. RFC 8292 §4.2 requires a
  rejection; the status codes are "might be used", so (c) stays within the RFC.
- **(d) recovery.** List the registrations with a `vapid_refused_at` in the last day in the policy, as R2-b lists the
  marked ones. The phone sends the app a `NEW_ENDPOINT` once a day, and Fedilab's `PushServiceImpl.onNewEndpoint`
  re-subscribes Mastodon. That bounds the cost of any 4xx to about a day. It is cheapest as one policy key for both
  reasons (`refresh: [{reg, why}]`) rather than `outside` alone. **Decide before R23-C**, which freezes the contract.

My recommendation to put to the user: 401/403 + (d), or (c) + (d).

### M3. `exp` at most 24 h + 5 min refuses a correct sender whose clock runs ahead, and that refusal deletes its subscription

**Failure.** Mastodon signs `exp` as exactly now + 24 h by its own clock. A Mastodon host 6 minutes fast, or our server
6 minutes slow, gets every push refused with 401, and Mastodon destroys the subscription (Q3's cost, every time). The
upper bound guards nothing we rely on. RFC 8292's 24 h is a MUST on the sender. A token's replay window matters only if
the JWT leaks, and we never log it (L4).

**Fix.** Accept `exp` in `[now - 5 min, now + 25 h]` and keep the lower bound. Vector rows: `exp_24h_plus_30min` (ok)
and `exp_26h` (refused). §13 should name the skew as part of Q3's risk.

### M4. A key update costs a server call per REGISTER, with no limit

**Failure.** R1's `Known` answer is free, and `New` is churn-limited (20 an hour per app, 60 per phone). §4.6's
`Update` has neither. An app that alternates keys, or Fedilab flipping on every start in a flaky network (H1), puts one
server call per REGISTER on `UpRuntime`'s single-threaded `work` executor, each with up to 20 s of budget, ahead of the
pulls.

**Fix.** At most one `Update` call per token an hour, or count it in the churn record. Past that, answer `Known` and
keep the new key in `vapidPending` (H1). One `up_rules.json` row.

---

## Low

**L1. The outside warning's fix texts repeat what the heal already did.** FluffyChat's "close and open again" runs the
same path as the daily `NEW_ENDPOINT`: REGISTER -> `Known` -> `NEW_ENDPOINT` -> the same discovery. Element X's
provider switch ends in the same discovery. If the mark survives several heals, the discovery itself fails from the
phone (network, captive portal, the phone's DNS filter, TLS). Say that, and point at Settings › App push's "Last seen
from outside". The texts also name unifiedpush.org, but another app's default gateway may differ: write "an outside
gateway (usually unifiedpush.org)".

**L2. One `NEW_ENDPOINT` per row per pass, and any one of them is the ping.** §2 excludes only the rewrite. A ping and a
refresh can fall due in the same pass and send two. Send one. When the row is 23 days without an ack, give it the
ping's id and set `pingId`/`pingSentMs`. Add an `up_rules.json` row.

**L3. A future timestamp freezes the rules.** If an ack is written while the clock is ahead, `lastAppAckMs` is in the
future. `UpEngine.ack` then never writes again (`now - lastAppAckMs < HOUR_MS` holds for a negative age), and `since` is
in the future, so the row is outside the rule until real time catches up. The same goes for `refreshMs` and
`pingSentMs`. This fails safe (no false unregister), but read a stamp more than a day ahead as now. One row.

**L4. The order and cost of the VAPID check.**
- Do the cheap parts first: the form, `k` compared **as decoded bytes** (padding and the standard alphabet are
  tolerated, so never as strings), the JWT's three parts, `alg`, the claims' JSON, `exp`, `aud`. Run the ECDSA verify
  **last**, so an expired, replayed or garbage token never costs a verify.
- Treat the scheme names `vapid`/`WebPush` case-insensitively. Ignore `kid`/`jku`/`x5u` and never fetch them. Refuse a
  `crit` header.
- `aud` must be a JSON string. RFC 7519 allows an array: say whether one holding our origin is taken.
- `exp` is any JSON number.
- Never log the `Authorization` header (tracing, the security log). The JWT is a 24-hour credential for that
  endpoint.
- Vector rows to add: `Vapid`/`webpush` case, quoted params (`t="…"`), a padded or standard-alphabet `k`, `exp` as a
  float, `aud` as an array, a `crit` header.

**L5. 401 vs 403.** As I read RFC 8292 §4.2, it suggests 401 for absent authentication and 403 for invalid. §4.4 gives
invalid a 401. It makes no difference to Mastodon (every 4xx is the same to it). Align with the RFC, or say why not.

**L6. `vapid_refused_at` is written before any bucket.** R1's pattern is a conditional SQL `UPDATE`. In SQLite an
`UPDATE` takes the write lock when the statement starts, even when its `WHERE` matches nothing. Refusals are charged to
no bucket. So a leaked-endpoint flood (many clients at 20/s each) takes the write lock once per request, competing with
registrations (`BEGIN IMMEDIATE`) and the queue. Return `vapid_refused_at` with `store::lookup`'s row (or keep an
in-memory minute throttle per registration) and run the `UPDATE` only when it is due.

**L7. Keys stored before R3 are never point-checked.** An R1 server stored any 87-character base64url. A stored key
that isn't a P-256 point would refuse every push for good, and the sender then drops the subscription. At use, treat a
stored key that doesn't parse as a point as no key, and warn once. Real apps send real keys, hence Low.

**L8. Say "server first" in §12.** An R3 phone's `Update` against an R1 server gets the same endpoint back without the
key being stored, and the phone records the new key. Once the server moves to R3 it verifies with the stale key, and
the sender is refused and drops the subscription. Either release the server first, or count an `Update` as done only
when the answer echoes the key (a contract change). Server first is simpler.

**L9. The gateway's refusal of a keyed pushkey is silent.** It is correct today: FluffyChat passes no key (checked) and
neither does Element X (per the design). If a Matrix app ever passes one, its homeserver deletes the pusher on
`rejected`, and no line shows anywhere. Write `vapid_refused_at` for it too, so the PWA's refused warning appears.

**L10. The draft form (`WebPush` + `Crypto-Key`) buys nothing for UnifiedPush apps.** Senders pair it with `aesgcm`
payloads. Their salt and `dh` travel in the `Encryption`/`Crypto-Key` headers, which `/up/` doesn't pass on, and the
connectors decrypt only RFC 8291 `aes128gcm` (tink's `fixed_webpush`). So a legacy Mastodon subscription can't work
through any distributor, which is why Fedilab subscribes `standard`. Keep the parsing if it is cheap, but say so, and
drop "Mastodon for a legacy subscription" from §4.2's table as a supported path.

**L11. The gate's clock jumps.** Rows 1-2 move the phone 24 + 8 days. The DO's date lock turns automatic time on
(`AppEnforcer.applyDateTimeLock` -> `setAutoTimeEnabled`), so network time may undo the jump mid-row. The rig's TLS
certificate, design 76's checks, the time rules and the restrictions pause all see the jump too. State the setup (the
date lock off for the rig, auto time off), or use a debug-only hook that backdates `lastAppAckMs`/`pingSentMs` in the
store: lighter, and no system clock involved.

**L12. Accuracy and small visible costs.**
- §2 says a forgotten token "holds up to 100 queued pushes for 24 h each". Its pushes are broadcast and acknowledged
  (the send went out), so nothing queues. What such a row costs is a live public endpoint and the PWA's line.
- Element X re-registers at once on `UNREGISTERED` when it still knows the token, so Q1's cost for it is smaller than
  stated (erring safe is fine).
- A heal or ping to FluffyChat while it isn't running starts its Flutter engine with a foreground service
  (`ForegroundServices.startService('background_push')`): a brief notification at most once a day while marked, and
  every 23 days for a ping. Give it a line in §3.3.
- §7: state that `outside` is a subset of `regs`, and that the launcher ignores a `reg` it doesn't hold.

---

## The questions, and silent choices

- **Q1** (auto-unregister at 30 days): framed fairly, and the recommendation (yes) holds. The connectors check out:
  3.0.x and 3.1.2 acknowledge a ping by themselves, and only an unknown token goes unanswered.
- **Q2** (urgency hold): framed fairly, and the recommendation (a) holds. The low-battery reading is right.
- **Q3**: re-frame it (M2), with M3's skew named as part of the risk.
- **Decided silently, with a visible cost:**
  - the key dropped by a REGISTER without one (H1);
  - the 5-minute `exp` slack, which decides when Fedilab's push dies (M3);
  - the PWA's "Vibb One asks {app} to check the address again", which promises a heal that mostly can't run (M1).

  Each needs a line in §13 or a fix.

## The rest checked

- **Migration 0153**: the next free number. `0152_unified_push.sql` is the highest on every local branch (checked
  2026-11-17). Two `ALTER TABLE … ADD COLUMN` and a new file, so the frozen-file rule holds. The tombstone trigger
  copies only `secret_hash` and is unaffected.
- **The contract**: additive, and an R1 launcher ignores `outside`. The test plan for both sides is right and lean. If
  M2(d) is taken, settle the key's name and shape (`refresh` with a reason) in R23-C. Add rows for H1 and M4 to
  `up_rules.json`, and L4's rows to `unified_push_vapid.json`.
- **The VAPID crypto**: `ring`'s `ECDSA_P256_SHA256_FIXED` with the **registered** key, ASN.1 refused, and the point
  check through `agreement` (which validates the peer point) are right. Running the check after the secret's lookup
  and before the buckets is right: a refusal never charges or wakes the phone, and the edge's per-client bucket bounds
  the cost (see L4 and L6).
- **The `aud` window** with `up_previous_origins` matches M4's rewrite: Fedilab re-subscribes on the rewrite's
  `NEW_ENDPOINT`, and until then Mastodon signs for the old origin.
- **Android 14-17**: no new API, receiver or permission in R2/R3 as written. M1's fix uses R1's raise. Q2(b)'s
  context-registered power receiver is right (`POWER_CONNECTED` isn't on the implicit-broadcast exemption list).
