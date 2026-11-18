# Launcher notes: Vibb One as the UnifiedPush distributor (design 108)

The design is `docs/design/108-unified-push.md` (revision 2, variant A, with the user's Q4 amendment: Element X keeps
its Play build and its push provider is switched to Vibb One once per account in Element X). The server's side is
`docs/notes/server/unified-push.md`; the shared contract `server/testdata/unified_push.json` (byte-identical in
`launcher/app/src/test/resources/`, `UnifiedPushContractTest`). Code: `push/up/` (GPL-3.0-or-later), except
`push/up/vendor/RaiseToForeground.kt` (Apache-2.0, adapted from UnifiedPush's android-distributor at 6daaf14; its header
lists every change, `NOTICE.md` names it). Built in R1-L (2026-11-17, branch `up`).

The launcher exports a registration receiver (design 108 §10, the user's Q2), but holds no third-party relay and no
second connection: pushes ride the one SSE stream to the family's own server.

## The pieces

- **`UpReceiver`** (exported, **installed disabled**): REGISTER, UNREGISTER and MESSAGE_ACK only. The sender is
  Android's shared identity (`getSentFromPackage()`, API 34+), read with the action and every extra in `onReceive`
  into an immutable `UpRequest` (M1); only that request reaches the executor, and `onReceive` returns at once - **no
  `goAsync()`** (code QA H1: on 14+ a process's manifest broadcasts are serialised, so a broadcast held across a server
  call would hold up SMS_DELIVER and the alarms, and past 60 s be a background ANR that kills Home). Every answer to an
  app is a broadcast of its own; the anchor's foreground service keeps the process. Never the `pi` extra, never
  AND_2's `application` (L4, a deliberate deviation from the spec: either lets any app name another's package) - a
  REGISTER without an identity is ignored and noted for the diagnostics row by its `application` hint only when that
  package is installed and may register, at most 3 such notes, worded "an app calling itself X ... not verified". Known extras checked by the spec's rule (`token` 1-100 bytes, `vapid` exactly 87 base64url, `id` at most
  100 bytes; a known extra of another type ignores the request); unknown extras ignored. `message` (Element X's MXID) is
  cut to 100 bytes rather than refused - it is only shown. `UpRequests.read` is pure (`UpReceiverReadTest`).
- **MESSAGE_ACK carries no identity**: the connector sends it with a plain `sendBroadcast` (no `FLAG_SHARE_IDENTITY`,
  `MessagingReceiver.mayAcknowledgeMessage`), so it is taken on its token alone (the spec's first way - an unguessable
  token); a sender that is present must own the token. It only feeds the app's liveness (`lastAppAckMs`, written at most
  hourly), never the server's ack. Ignored while UP is off; rate-limited before the sealed store is read (a token read
  within the hour is skipped, at most 30 reads a minute). A REGISTER from an app that may not register is refused from
  the cached policy before the store is read.
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
  `UpView.of(policy).on` = the policy's `unified_push.on` **and** an origin - **only for a policy carrying the parent's
  choice** (`parentChoice`: fresh, or a cache that decodes; code QA M3). A fallback plan or a bare policy leaves the
  components as they are: a disabled receiver makes the connector drop Vibb One, and each Element X account would need
  its manual switch again. No key (an older server) is off. While disabled, `queryBroadcastReceivers` lists no Vibb One.
- **`UpPackageReceiver`** (`exported="false"`): `PACKAGE_FULLY_REMOVED` / `PACKAGE_DATA_CLEARED` (protected, on the
  implicit-broadcast exception list) drop the app's rows and send a `DELETE` each; the sync's reconcile is the backstop
  (an app no longer installed - a hidden one still is).
- **`UpStore`**: CE prefs `unified_push`; `up_regs_v1` one JSON blob sealed under the secrets key (`SealedPrefs`, design
  r10; tokens, secrets and endpoints are capabilities), written with `commit()`; `up_deletes_v1` (the `DELETE`s not yet
  through), `up_churn_v1` (the last hour's new registrations), `up_notes_v1` (diagnostics), `up_unreadable_since_v1`
  plain. A blob that doesn't open or decode is `Unreadable`: kept as it is for a day, and nothing that would delete a
  server row runs on it; after a day (code QA L4) the next accepted policy drops it (a removal needs no seal, so it works
  with a broken key) and reconciles from empty - the server's rows are deleted, the apps' endpoints answer 410 and each
  app registers anew at its next start. No parent action and no UI.
- **`UpEngine`** (the logic, JVM-tested with fakes) on **`UpRuntime`**'s `work` executor: the requests, the pulls (one
  batch per task, so a REGISTER waits at most one call), the reconcile, every server call - each with a budget (a
  REGISTER one 20 s call plus a conflict retry only after a fast answer, the DELETEs 20 s / 20 a run, a pull step one
  15 s call). **The raise has its own `raise` executor** (code QA H1): its sends, its binding's callbacks
  (`bindService(..., raise, conn)`) and its 5 s timer never wait behind a server call; a sent or failed message is posted
  back to `work`. Single-threaded each, so no locks. **Before the first unlock nothing runs (L6)**:
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
app, a suspended one included - Q5; an unknown or unconfirmed `reg`'s dropped and acknowledged; a hidden or uninstalled
app's dropped, acknowledged and its registration removed with a `DELETE` - code QA L5: a hidden app, by the allowlist or
any other reason, can't get a broadcast, and held messages would fill the server's batch for every other app; it
registers anew once visible and started) and handed to `RaiseToForeground`. **A key enters the ack only after its
`sendBroadcast` ran** (`UpEngine.sent`); a failed send is reported (`UpEngine.failed`) and delivered again by a later
pull; a failed pull keeps its acks; the late sends' acks go with a pull 6 s later (`pullLater`). A message still in the
raise, or sent and not yet acknowledged, is never broadcast twice. **`more` loops only when the step handled something
new** (code QA M1); a batch still in the raise waits for its sends. A fault in a step ends the pull (code QA M2). The
wake lock is released when the pull is over and no raise is bound (code QA L2). At least once: a crash between the send
and the ack re-delivers.

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

## R2/R3 (design 108b, R23-L, 2026-11-17)

Design `docs/design/108b-unified-push-r2-r3.md`; the server's part (the VAPID check, `refresh`) is R23-S. The server
releases first (L8). All of it is pure rules in `UpRules` (rows in `up_rules.json`: `liveness`, `refresh`, `pass`, the
register rows) run by `UpEngine`.

- **Liveness (§2).** `UpRules.liveness`, run in `policyAccepted` on the kept rows (so at least every 30 minutes): a
  confirmed row with no ping out and 23 days since `max(lastAppAckMs, confirmedAtMs)` gets a ping (`NEW_ENDPOINT`, same
  endpoint, new id; `pingId`, `pingSentMs`, `pingLastSendMs`, `pingBoot`). A ping out is **re-sent once per boot** (same
  id; `pingBoot` against `Settings.Global.BOOT_COUNT`; unreadable: the boot instant in 10-minute steps, logged once, so it
  errs toward re-sending, L6) **and every day while it is out** (`PING_RESEND_MS`, code QA M1: one lost send or ack no
  longer removes a live app; an ack of a ping this process sent skips the ack limits; a failed send clears the row's
  `pingBoot` so the next pass sends again), and "dead" needs 7 days since `pingSentMs` **and** an hour
  since `pingLastSendMs` - so a phone that was off never buries a live app at its first sync. Dead: `UNREGISTERED`, the
  row removed, its `DELETE` queued, the note `NOTE_UNANSWERED`. Any ack clears the ping (`UpEngine.ack`). Every stamp
  more than a day ahead reads as now (`UpRules.stamp`) and is **written back as now** (`UpRules.clamped` in each pass, and
  `ack` rewrites an ahead `lastAppAckMs`, L1).
- **The refresh (§3.3).** The policy's `unified_push.refresh` (`UpRefresh(reg, why)`: `outside` or `refused`, both the same
  act; an unknown `why` reads as `outside`). `UpRules.refreshes`: a confirmed listed row whose `refreshMs` is null or a day
  old, **only while the phone is in use** (`PowerManager.isInteractive && !isDeviceIdleMode`, `UpPhone.inUse`). A refresh
  that falls due otherwise waits for the next accepted policy while in use, or the unlock: `UpRuntime` registers a
  context receiver for `ACTION_USER_PRESENT` (`RECEIVER_EXPORTED`: SystemUI sends it from its own uid, design 59's trap; protected, once, when the engine is built; manifest receivers
  don't get it) that calls `UpEngine.userPresent`. `UpRules.pass` gives one `NEW_ENDPOINT` per row (L2): a due ping or
  re-send carries the refresh (its id; `refreshMs` set).
- **Through the raise (M1).** Every `NEW_ENDPOINT` that doesn't answer a REGISTER goes through `RaiseToForeground`
  (`Message.kind` `NEW_ENDPOINT` with an endpoint; `UpPhone.deliver`): the ping, the re-send, the refresh and the
  origin's rewrite. `UpEngine.sent` ignores that kind (nothing to acknowledge to the server); `failed` only marks a lost ping or refresh to
  go again. A pass sends only when its stamps were stored (L8). A REGISTER's answer
  (`Known`, `Update`) is a plain broadcast.
- **A key update (§4.6, H1, M4).** `registerDecision(..., vapid)`: a known confirmed token re-registered with no key or
  the same key is `Known` (a stored key is never dropped); **another** key is `Update` - `NEW_ENDPOINT` from the store at
  once, `vapidPending` set, `keyTryMs` stamped, the key posted (`postKey`); a 200 makes it `vapid`; **only a 400** (`Registered.Invalid`) drops the
  pending key and notes `NOTE_BAD_KEY`; any other answer (`Rejected(code)`: 401, 408, 429, a 409 without a body, an
  unreadable 200), no network or a 5xx keeps it pending, posted again by `policyAccepted` (`retryKeys`) at least 5
  minutes after the failed post (L4). The same key as the stored one clears a pending other (L3). At most one key call an hour per token: within it a REGISTER with another key is `Known` with the key
  pending. Never `REGISTRATION_FAILED` for a key. A pending row's retry carries the newest key.
- **Words.** `settings_up_unanswered`, `settings_up_bad_key` (en, nb) in the PIN-gated App push row. A note goes when its
  cause does (L7): `NOTE_UNANSWERED` when the app's registration is confirmed again, `NOTE_BAD_KEY` when a key is taken
  (`UpStore.clearNote`).
- **The gate's hook.** `UpDebugReceiver` (src/debug only, DUMP-guarded; `checkReleaseHasNoDebugHook`,
  `DebugHookAbsentTest`) backdates a row's stamps by N days in the store (`--es reg --ei days [--es field] [--ei send_hours H]`; the last sets the ping's last send H hours ago so row 2's "dead" is one step) on the
  engine's executor, and drops the engine's in-memory ack limit of that token (`UpEngine.forgetAcks`, L5): the system
  clock is never moved (L11).

## Held while the apps have no network (design 115)

`docs/design/115-up-held-without-network.md`. Under the VPN's sink (the parent's Bare Vibb, abroad, a roaming session
past the lock, the kid's Off) only Vibb One has a network, so the queue stays on the server: **no pull**, not "pull and
don't deliver".

- **The rule** (pure `UpRules.hold`, `holdOf`; rows `up_rules.json`'s `hold`): held while any view says the sink - the
  tunnel (`KidVpnService.tunnelShape`, a companion mirror of `shape`), the persisted decision (`vpn_shape`, only while
  Android's always-on VPN is ours with lockdown, `MobileNetworkRuntime.lockdownOurs`) and the service's wanted shape
  (`applyShape` with `KidVpnService.serviceNet`: its watcher's last network, only while a service runs). UP off holds
  nothing. A tunnel in the sink answers at once (the runtime's `holdParts`).
- **Hooks:** `UpRuntime.requestPull` refuses before the wake lock and posts `engine.noteHeld()`; `UpEngine.requestPull`
  and each `step` check `UpPhone.hold()` (a held step ends the pull, the acks kept); a MESSAGE's send that finds the
  phone held throws `UpHeldException` (reported as failed, never acked) after posting `noteHeld(askPull = true)`.
  `KidVpnService.establish`, `stopSelfCleanly` and the end of `MobileNetworkRuntime.writeAlwaysOn` call
  `UpRuntime.shapeChanged()` (posted to `work`): once a hold was seen, `pullAfterHold()` - the "net" pull 2 s after the
  last ask (its own future and the 30 s wake lock), checked again when it runs, once more 10 s later when only the
  wanted shape still holds. After a hold (`heldSeen`) every pull and send also counts as held for 1.5 s after the last
  shape change (`SETTLE_MS`; gate row 4: a sync's own pull 60 ms after the switch handed out a push whose fetch failed). A refresh due waits while held (`UpRules.pass(..., held)`); pings and the origin's rewrite
  are not held.
- **`held` on the pull** (`UpAckBody.held`, left out when false): every step of the first run after a hold, so its
  hand-outs leave no delivery sample. `heldSeen` lives in the engine alone, cleared when such a run ends with an answer.
- **The report:** `unified_push: {"held": "no_network" | "none"}` (`UpRuntime.report`, while UP is on and the phone
  unlocked), a full-report field; the server's Attention guard reads it.
- Tests: `UpRulesTest` (hold rows, the refresh row), `UpEngineTest` (design 115 rows), `UnifiedPushContractTest`
  (`report`, `report_open`, `body_held`).

## Not in R1-L

- (Done in R23-L: the 30-day liveness ping and unregister, §5.6.)
- VAPID verification is the server's (R23-S, design 108b §4): the phone passes the key on and updates it (above).
- The emulator gate is R1-G: `docs/testing/emulator.md` §6ay (14 and 16 passed, row 5 included: a suspended app's
  notifications are hidden by Android and appear in the shade without sound when the rule ends; Android 17 not run). The debug build logs each MESSAGE send and each pull's acks by key prefix (`UpRuntime`, `Log.d`). Element X
  itself is owed on the Jelly Star (R1-E).
