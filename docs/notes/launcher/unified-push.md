# Launcher notes: Vibb One as the UnifiedPush distributor (design 108)

The design is `docs/design/108-unified-push.md` (revision 2, variant A, with the user's Q4 amendment: Element X keeps
its Play build and its push provider is switched to Vibb One once per account in Element X). The server's side is
`docs/notes/server/unified-push.md`; the shared contract `server/testdata/unified_push.json` (byte-identical in
`launcher/app/src/test/resources/`, `UnifiedPushContractTest`). Code: `push/up/` (GPL-3.0-or-later), except
`push/up/vendor/RaiseToForeground.kt` (Apache-2.0, adapted from UnifiedPush's android-distributor at 6daaf14; its header
lists every change, `NOTICE.md` names it). Built in R1-L (2026-11-17, branch `up`).

It reverses the 2026-10-06 rule ("the launcher must not export a registration receiver", `gotchas-and-history.md`,
"Removed monitoring and upstream extras") for the receiver only (design 108 §10, the user's Q2): no third party and no
second connection - pushes ride the one SSE stream to the family's own server.

## The pieces

- **`UpReceiver`** (exported, **installed disabled**): REGISTER, UNREGISTER and MESSAGE_ACK only. The sender is
  Android's shared identity (`getSentFromPackage()`, API 34+), read with the action and every extra **before
  `goAsync()`** into an immutable `UpRequest` (M1); only that request reaches the executor. Never the `pi` extra, never
  AND_2's `application` (L4, a deliberate deviation from the spec: either lets any app name another's package) - a
  REGISTER without an identity is ignored and noted for the diagnostics row by its `application` hint (untrusted, shown
  only there). Known extras checked by the spec's rule (`token` 1-100 bytes, `vapid` exactly 87 base64url, `id` at most
  100 bytes; a known extra of another type ignores the request); unknown extras ignored. `message` (Element X's MXID) is
  cut to 100 bytes rather than refused - it is only shown. `UpRequests.read` is pure (`UpReceiverReadTest`).
- **MESSAGE_ACK carries no identity**: the connector sends it with a plain `sendBroadcast` (no `FLAG_SHARE_IDENTITY`,
  `MessagingReceiver.mayAcknowledgeMessage`), so it is taken on its token alone (the spec's first way - an unguessable
  token); a sender that is present must own the token. It only feeds the app's liveness (`lastAppAckMs`, written at most
  hourly), never the server's ack.
- **`UpLinkActivity`** (`unifiedpush://link`, exported, installed disabled): translucent, `noHistory`,
  `excludeFromRecents`, no `taskAffinity`, `finish()` in `onCreate`; extends `Activity`, nothing of ours. `RESULT_OK` with
  `pi` (an immutable broadcast PendingIntent to `org.unifiedpush.dummy_app`) only for a known `callingPackage` that may
  register; else `RESULT_CANCELED`.
- **The PPA (L3)**: `AppEnforcer.enforceUpLinkPin` sets the persistent preferred activity for the link on every
  `apply()` and in `HomePin`'s re-seat (the fifth of ours: HOME, the Play link blocker, the call-log pin, the captive
  portal, this one; the boot cover's is `HomePin`'s, added last) - whatever UP's state. Whether it is used is the
  activity's enabled state. `PlayInvariantsTest` pins the re-seat's list and "every PPA set in `apply()` is re-added by
  the re-seat".
- **On and off**: `UpRuntime.setComponentsEnabled` (from `apply()`, compare first, `DONT_KILL_APP`) follows
  `UpView.of(policy).on` = the policy's `unified_push.on` **and** an origin. No key (an older server, a policy fetched
  before the first report claimed `unified_push_v1`), a corrupt cache or a fallback plan: off. While disabled,
  `queryBroadcastReceivers` lists no Vibb One.
- **`UpPackageReceiver`** (`exported="false"`): `PACKAGE_FULLY_REMOVED` / `PACKAGE_DATA_CLEARED` (protected, on the
  implicit-broadcast exception list) drop the app's rows and send a `DELETE` each; the sync's reconcile is the backstop
  (an app no longer installed - a hidden one still is).
- **`UpStore`**: CE prefs `unified_push`; `up_regs_v1` one JSON blob sealed under the secrets key (`SealedPrefs`, design
  r10; tokens, secrets and endpoints are capabilities), written with `commit()`; `up_deletes_v1` (the `DELETE`s not yet
  through), `up_churn_v1` (the last hour's new registrations), `up_notes_v1` (diagnostics) plain. A blob that doesn't
  open or decode is `Unreadable`: kept as it is, and nothing that would delete a server row runs on it.
- **`UpEngine`** (the logic, JVM-tested with fakes) on **`UpRuntime`**'s one single-thread scheduled executor: the
  requests, the pulls (one batch per task, so a REGISTER waits at most one call), the reconcile, the raise's binding
  callbacks (`bindService(..., executor, conn)`) and its timers - no locks. **Before the first unlock nothing runs (L6)**:
  every engine entry checks `isUserUnlocked` before the store factory is called (`UpRuntimeLockedTest`); the components
  are not direct-boot-aware.

## Registration (§2.1, §5.1)

`UpRules.registerDecision`, in order: on; a token held by another package refused; the allowlist (any app on an
unmanaged phone); not stopped; a confirmed token answered `NEW_ENDPOINT` from the store (no network), a pending one
retried with the same `reg` and secret; 100 per app / 300 per phone (`limit`, below the spec's "over 1000" on purpose);
20 new an hour per app and 60 per phone (`busy`, the server's numbers). A new registration is written `pending` first,
counted, then posted (20 s call timeout; Element X waits 30 s). The answer: an endpoint of the contract's form ending in
our secret confirms it (`confirmedAtMs`) and sends `NEW_ENDPOINT`; a 409 drops the row (a `conflict` gets one fresh
pair); 400 drops it; no network or a 5xx keeps it pending and answers `NETWORK`. Every refusal is
`REGISTRATION_FAILED(ACTION_REQUIRED)`; `TEMP_UNAVAILABLE` and `INTERNAL_ERROR` are never sent. The connection token
never leaves the phone.

## Delivery (§5.4, §5.5) and H1

`up` on the stream (`CommandListenerService.onEvent`, `UP_EVENT`) asks `UpRuntime.requestPull` - nothing else; every
other event is today's sync nudge. Every stream reopen also pulls (a no-op while UP is off), and every accepted fresh
policy (the sync) does after the reconcile. A pull takes a 30 s timed partial wake lock; one in flight, one trailing.
`POST up/messages {ack}` (at most 200 keys), then each message planned (`UpDelivery.plan`: delivered to an installed
app, a suspended one included - Q5; a hidden app's, an unknown or unconfirmed `reg`'s dropped and acknowledged; an
uninstalled app's dropped, acknowledged, its row removed) and handed to `RaiseToForeground`. **A key enters the ack
only after its `sendBroadcast` ran** (`UpEngine.sent`); a failed pull keeps its acks; the late sends' acks go with a pull
6 s later (`pullLater`). At least once: a crash between the send and the ack re-delivers.

The MESSAGE broadcast (and NEW_ENDPOINT, REGISTRATION_FAILED, UNREGISTERED): explicit action, `setPackage`,
`FLAG_INCLUDE_STOPPED_PACKAGES`. The raise: `bindService(RAISE_TO_FOREGROUND, BIND_AUTO_CREATE)` from the anchor's FGS
process, queued while binding, sent on connect, each message pushing the unbind 5 s out; `bindService` false sends at
once; the timeout, `onBindingDied`, `onNullBinding` and `onServiceDisconnected` send what is queued, then unbind
(`RaiseToForegroundTest`). An app without the service is sent without the raise and noted once.

## The policy's reconcile (§2.3, M4)

`UpRules.reconcile` on every accepted fresh policy, with the request's start (`policyGetStartedMs`): off drops every
row and tells each app `UNREGISTERED` (the server deleted its rows); a stopped or no-longer-allowed app's rows dropped
and told; a confirmed row missing from `regs` dropped and told **only if confirmed before the request started** (a
registration made during the fetch survives); a pending row a day old dropped quietly, its `DELETE` queued; a `reg` the
server holds that no local row knows gets a `DELETE`. A changed `origin` rewrites every confirmed endpoint
(`origin + "/up/" + secret`) and sends `NEW_ENDPOINT` with a fresh id. The queued `DELETE`s are retried there.

## Settings

The PIN-gated Settings' "App push" / "Push for apper" row (`settings_mdm_unified_push`): on or off, each registration's
app, its own description (Element X's MXID - on the phone only, never sent) and its last delivery, and the notes. Shown
while UP is on or anything is registered or noted.

## Not in R1-L

- The 30-day liveness ping and unregister (§5.6) are R2: the rows keep `lastAppAckMs`/`pingId` for it.
- VAPID verification (R3): a registered key is passed to the server as is.
- The emulator gate (R1-G, §12.3) - the raise's process state, Doze, standby, the time rule's notifications, are owed
  there; Element X itself on the Jelly Star (R1-E).
