# Design 115: App push held while the apps have no network; the glance row's app name

Status: design, 2026-11-17, revised after its design QA (`docs/review/115-up-held-design-qa.md`: M1-M5 and the Lows
applied, see "Revision" at the end). **Not built.** The approved item is PLAN.md's "App push held while the app has no network;
the glance row's app name" (REPORTED AND APPROVED 2026-11-17, queued right after design 112). Inputs: designs 108
(§2.2, §3.6, §3.7, §5.4, §5.5), 108b (§3.3, §5), 108c (§1.1, §7), 95 rev. 3 and 97 (the VPN's shapes, `apps_mobile`, the
kid's switch), `docs/notes/launcher/unified-push.md`, `docs/notes/server/unified-push.md`,
`docs/notes/launcher/dns-and-vpn.md` "Three shapes", `docs/notes/launcher/hardening.md` "Mobilnett", the root
`CLAUDE.md` ("Words in the UI", the contracts, Android 14-17). Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`,
`LT/` = `launcher/app/src/test/`, `S/` = `server/src/`; line numbers on `main` at a65da6857.

Items marked **ASK** are the user's to decide in the morning. The build skips them: each ASK's "skipped" column says
what is built without it, and that is coherent on its own.

The user (2026-11-17), with "Bare Vibb" on mobile data and Wi-Fi off: Element X woke on a push, couldn't fetch, and
showed only "Du har 5 nye meldinger". "Jeg synes det høres bra ut at varslene henger igjen og kommer tilbake når den får
internett igjen, enten ved at full cell data tillates eller at wifi kommer tilbake." And, on the same screenshot: the
lock glance's row read "Element X", not "Chat" (the parent's name for the app).

---

## 0. Summary

- **Part 1.** The launcher pulls the app-push queue only while the apps have a network. Under the VPN's sink (every
  reason: the parent's Bare Vibb, abroad, a roaming session past the lock, the kid's Off) only Vibb One has a
  network, so the pull waits and the messages stay on the server. When the sink ends (Wi-Fi back, the parent's
  choice, the kid's switch, a pause, home again) the launcher pulls 2 s after the tunnel's switch and delivers the
  backlog, oldest first, one raise per app. Every other way the apps lose their network (airplane mode, Android's
  mobile data off, roaming locked off) takes Vibb One's too, so no pull runs then anyway.
- The hold is **no pull**, not "pull and don't deliver" (§2.2). One pure rule, `UpRules.hold`, read at every pull
  step and once more at the send (§2.3, §2.4).
- **The report**: `unified_push: {"held": "no_network" | "none"}` in every status report while UP is on. The server
  stores it (`device_status.up_held`) and 108c's "waiting" row counts only the policies served after the last report
  that said held (§2.8). The first pull run after a hold says `held: true`, so its pushes leave no delivery-time sample
  (§2.5). One contract commit, both sides (`server/testdata/unified_push.json`).
- **Part 2.** The glance row shows the parent's display name when the notification's title is the app's own Android
  label (or empty). Android's shade is unchanged (§3).
- Size: launcher S, server XS; no policy key, no capability, no new words (unless ASK 1).

## 1. What happens today

- The pull runs on every `up` event, every stream reopen and every accepted policy (`L/server/CommandListenerService.kt:329`,
  `:341-344`; `L/push/up/UpRuntime.kt:198-206`). It asks nothing about the target app's network.
- `UpDelivery.plan` (`L/push/up/UpDelivery.kt:36-43`) delivers to any installed app; the raise binds it for 5 s and
  sends MESSAGE (`L/push/up/UpRuntime.kt:470-488`); the key is acknowledged after the send (`L/push/up/UpEngine.kt:534-540`)
  and the server deletes it.
- Under the sink every app but Vibb One routes into the tunnel (`L/server/KidVpnService.kt:251`, `:254-258`): DNS
  `REFUSED`, TCP reset. The app's default network is our VPN, which carries INTERNET and the underlying network's
  validation, so the app believes it is online, tries, fails fast, and Element X posts its fallback "Du har N nye
  meldinger". The push is spent: acknowledged and gone, so nothing re-delivers it when the network comes back.

## 2. Part 1: the hold

### 2.1 Who has a network: the launcher's own model

The launcher can't ask Android whether another app has a network. `ConnectivityManager.getActiveNetworkForUid`,
`isUidNetworkingBlocked` and `NetworkPolicyManager` are system or hidden APIs (`NETWORK_STACK`,
`OBSERVE_NETWORK_POLICY`) on 14-17; `getRestrictBackgroundStatus` answers only for the caller. So the rule reads
what the launcher owns: the VPN's shape. That is exact for the cases that matter, because the launcher is the only
app excluded from the tunnel (`L/server/KidVpnService.kt:251`; D4 of design 95: no per-app exception, not even the
music app), and every cut that isn't ours cuts Vibb One as well:

| Situation | Vibb One's network | The VPN's shape (`L/server/MobileNetwork.kt:176-209`) | The apps | The phone |
|---|---|---|---|---|
| Wi-Fi up, any choice | Wi-Fi | filter / pass / none (`sinkReason` is `OFF` off cellular, `:177`) | Wi-Fi | delivers |
| Cellular, mobile data "På", no lift needed | cellular | filter / pass / none | cellular | delivers |
| Cellular, the parent's Bare Vibb (`apps_mobile: "vibb"`, `:104-107`) | cellular | **sink** (`PARENT`) | none | **holds** |
| Cellular, the kid's Mobildata Off (bit 32, `KID`) | cellular | **sink** | none | **holds** |
| Abroad under "I utlandet: Bare Vibb", or a 901 network under "Ingen data" (`ABROAD`) | cellular | **sink** | none | **holds** |
| A roaming session that outlived the roaming lock (`ROAMING`) | cellular | **sink** | none | **holds** |
| Abroad under "Ingen data" (roaming locked, no 901) | none (`DISALLOW_DATA_ROAMING`) | - | none | no pull possible |
| Android's mobile data off, Wi-Fi off | none | - | none | no pull possible |
| Airplane mode, Wi-Fi off | none | - | none | no pull possible |
| Airplane mode, Wi-Fi on | Wi-Fi | not sink | Wi-Fi | delivers |
| A pause, "Åpne for oppsett", the offline override | lifts every sink reason but the kid's Off (`:51`, `:104-107`, `VpnInputs.kidOff` "no lift touches it") | as the inputs say | | as the shape says |

"No pull possible" needs no new code: the stream is down and the pull fails; the stream's reopen pulls
(`CommandListenerService.kt:329`), and the shape on that network decides.

Not covered (unchanged, §9): Android's own per-app cuts that leave Vibb One online - Data Saver, an app's "Background
data" switch, Low Power Standby on a device that enables it, a DNS filter that blocks the app's server, and (QA L6) the
filter's own DNS upstream down on Wi-Fi under FILTER (design 67's "DNS can't be reached", `ReachTracker`): the apps
can't resolve while Vibb One, outside the VPN, still pulls. A later hold reason could come from that outage. The raise
(design 108 §5.4) already lifts Doze, the standby buckets and Android 15's background network rule for the 5 s.

### 2.2 Decision: no pull while held

Three ways to hold were weighed against R1/R2's pull and ack (`S/unified_push/store.rs:377-460`: acks deleted first,
then the oldest 50 rows; every pull hands the oldest out again until acknowledged) and the TTL (at most 24 h, the
gateway's always 24 h, `S/unified_push/matrix.rs:275`, `S/unified_push/publish.rs:35-42`):

- **(a) Don't pull (chosen).** The queue stays the truth (design 108 §2.2). No body bytes cross the sink's mobile
  data, which is the parent's reason for Bare Vibb. The server's TTL and caps (100 per registration, 500 rows / 1 MiB
  per phone, the oldest dropped, design 108 §3.6) bound the backlog exactly as for a phone that is off. The rows keep
  `fetched_ms` null, so 108c's arm (b) can't fire; arm (a) needs the report's guard (§2.8).
- **(b) Pull and don't deliver.** Rejected. Every pull returns the same oldest 50, so with `more` the engine's loop
  rule (`L/push/up/UpEngine.kt:519-522`, "`more` loops only when the step handled something new") would have to count a
  held message as handled or spin; each `up` event would re-download up to 256 KiB over the sink's mobile data; a
  per-app hold would block other apps behind the held head; the re-hand-outs stamp `handed_ms` and raise 108c's arm (b)
  for every hold longer than two minutes.
- **(c) Pull, acknowledge, keep on the phone.** Rejected. Message bodies at rest on the phone (a sealed store, its own
  TTL, reboot survival), and the ack no longer means delivery, which 108c's waiting row and delivery time read.

Pending acks (keys sent in the last 6 s before the hold) wait in memory and ride the first pull after it; the server
deletes acknowledged keys before it hands anything out, so they are never re-handed. A process death in the hold loses
them: those messages come again (at least once, as today).

**All apps held alike.** The sink cuts every app at once, so the hold is per phone today. The rule still takes the
target package (§2.3) so a later per-app exception (design 95 D4's "a per-app row is possible later") would hold per
app; that would need a skip list on the pull (`{"ack", "skip": [reg]}`) and is not designed here.

### 2.3 The pure rule

`L/push/up/UpRules.kt`, new:

```kotlin
/** Design 115: why the phone holds the app-push queue. [wire] is the report's `unified_push.held`. */
enum class UpHold(val wire: String) { NONE("none"), NO_NETWORK("no_network") }

/**
 * Held while any view of the VPN says the sink: the tunnel the service has up ([tunnel], `null` without one), the shape
 * last decided ([decided], the persisted `vpn_shape`: the sink's lockdown cuts the apps even when its tunnel failed,
 * design 95 code QA M1), and the shape the service's own view of the network asks for ([wanted]: a Wi-Fi -> cellular
 * switch is held before the tunnel follows). UP off holds nothing (no pull runs).
 */
fun hold(on: Boolean, tunnel: VpnShape?, decided: VpnShape?, wanted: VpnShape?): UpHold =
    if (on && (tunnel == VpnShape.SINK || decided == VpnShape.SINK || wanted == VpnShape.SINK)) UpHold.NO_NETWORK else UpHold.NONE

/** The runtime's inputs to [hold] (QA L1, M1 (a)): [persisted] counts only while Android's always-on VPN is ours with
 * lockdown ([lockdown], the thing that cuts the apps without a tunnel), and the service's wanted shape only while the
 * service runs ([running]) - `applyShape(inputs, net, persisted)` with the network the service's watcher saw last, or
 * the launcher's own `bestNet` before its first callback (the service's `serviceShape`). */
fun holdOf(on: Boolean, tunnel: VpnShape?, persisted: VpnShape?, lockdown: Boolean, running: Boolean, inputs: VpnInputs, net: BestNet?): UpHold
```

Leaving the sink, the three agree only once the new tunnel is up: `switchTo` writes the non-sink decision first
(`L/server/MobileNetwork.kt:235`), then `establish` sets the tunnel (`L/server/KidVpnService.kt:272`). Entering it,
`wanted` turns first. So the hold starts early and ends late, never the other way.

**Rows, `LT/resources/up_rules.json` gains `hold`** (launcher-only; `UpRulesTest` runs each through `UpRules.holdOf`,
which computes `wanted` with `applyShape` from `L/server/MobileNetwork.kt:204-209` only while `running`, and counts
`decided` only with `lockdown`). `running` and `lockdown` are true unless the row says otherwise:

| case | on | inputs (filter, parent_sink, roaming_locked, abroad_armed, kid_off) | net (cellular, roaming, abroad, unsure) | decided | tunnel | held |
|---|---|---|---|---|---|---|
| Wi-Fi, Bare Vibb | true | 1,1,0,0,0 | 0,0,0,0 | filter | filter | none |
| cellular, Bare Vibb | true | 1,1,0,0,0 | 1,0,0,0 | sink | sink | no_network |
| cellular, the kid's Off | true | 1,0,0,0,1 | 1,0,0,0 | sink | sink | no_network |
| Wi-Fi, the kid's Off (QA L10) | true | 0,0,0,0,1 | 0,0,0,0 | pass | pass | none |
| abroad, I utlandet Bare Vibb | true | 0,0,0,1,0 | 1,1,1,0 | sink | sink | no_network |
| cellular roaming that can't be placed, abroad armed (QA L10) | true | 0,0,0,1,0 | 1,1,0,1 | pass | pass | no_network |
| roaming session past the lock | true | 1,0,1,0,0 | 1,1,0,0 | sink | sink | no_network |
| cellular, mobile data "På" | true | 1,0,0,0,0 | 1,0,0,0 | filter | filter | none |
| cellular, filter off, nothing armed (no service) | true | 0,0,0,0,0 | 1,0,0,0 | none | null | none |
| Wi-Fi just dropped: wanted sink, tunnel still pass | true | 0,1,0,0,0 | 1,0,0,0 | pass | pass | no_network |
| Wi-Fi back: wanted pass, decided pass, tunnel still sink | true | 0,1,0,0,0 | 0,0,0,0 | pass | sink | no_network |
| the sink's establish failed: lockdown, the old filter tunnel | true | 1,1,0,0,0 | 1,0,0,0 | sink | filter | no_network |
| no network seen, the persisted sink kept (`applyShape`'s doubt) | true | 1,1,0,0,0 | null | sink | null | no_network |
| decided sink, no tunnel, Wi-Fi seen: the service dead under lockdown (QA L10) | true | 1,1,0,0,0 | 0,0,0,0 | sink | null (not running) | no_network |
| decided sink without lockdown (the VPN taken over, §9; QA L1) | true | 1,1,0,0,0 | 0,0,0,0 | sink (no lockdown) | null (not running) | none |
| a pause: Bare Vibb lifted, filter off | true | 0,0,0,0,0 | 1,0,0,0 | none | null | none |
| a pause, the kid's Off (no lift touches it) | true | 0,0,0,0,1 | 1,0,0,0 | sink | sink | no_network |
| UP off | false | 1,1,0,0,0 | 1,0,0,0 | sink | sink | none |

The `refresh` rows gain one: a refresh due, in use, **held** -> not sent (§2.4, last bullet).

### 2.4 Where it hooks

- **The pull** (`L/push/up/UpEngine.kt`): `UpPhone` gains `fun hold(): UpHold` and `fun pullAfterHold()`.
  `requestPull` (`:457-468`) returns false while held and notes the hold; `step` (`:481-525`) checks before its server
  call and ends the pull the same way, keeping `acks`. `UpRuntime.requestPull` (`L/push/up/UpRuntime.kt:213-223`)
  checks before it takes the wake lock, so a held `up` event costs no wake lock and no request; it posts a cheap
  `engine.noteHeld()` instead (QA M5). Its runtime reads `tunnel` from a new `@Volatile KidVpnService.tunnelShape`
  (companion, set beside `shape` at `KidVpnService.kt:272` and `:597`), `decided` from
  `MobileNetworkRuntime.storedShape()` (`L/server/MobileNetworkRuntime.kt:127`) - counted only while Android's
  always-on VPN is ours with lockdown (QA L1: `getAlwaysOnVpnPackage` and `isAlwaysOnVpnLockdownEnabled`, device owner
  only, read only when the stored shape is the sink) - and `wanted` from the service's own view (QA M1 (a)): a companion
  `KidVpnService.wantedNow()` that is `serviceShape(storedInputs(), lastBest, bestNet-before-the-first-callback,
  storedShape())` without the service's side effects, `null` while no service runs (QA L1: it follows within
  milliseconds once one does). A tunnel in the sink answers at once, without the other reads (QA L4); the other reads
  are prefs, the volatile `lastBest` and, only before the watcher's first callback, telephony.
- **The send** (`L/push/up/UpRuntime.kt:470-488`): a MESSAGE whose send finds the phone held throws (a new
  `UpHeldException`, logged as "held"), which `RaiseToForeground.send` already reports as failed
  (`L/push/up/vendor/RaiseToForeground.kt:162-170`) -> `UpEngine.failed` (`:543-549`): no ack, delivered again after
  the hold. Before it throws, the send posts `engine.noteHeld(askPull = true)` (QA M1 (c)): the hold is noted and the
  "net" pull asked, so a message refused on a hold that ends without any change of shape (a blip of `wanted`) is still
  pulled again. This closes the race of a pull in flight when Wi-Fi drops (one batch at most). No vendor change.
  `NEW_ENDPOINT` sends are never held (a ping's MESSAGE_ACK is local).
- **The end of the hold** (no new network callback): the shape's two writers call `UpRuntime.shapeChanged(context)`
  (QA L2: with `applicationContext`, posted to the engine's `work` executor and computed there, never on the service's
  main thread): `KidVpnService` after every change of `shape` (`establish` `:272`, `stopSelfCleanly` `:597`) and
  `MobileNetworkRuntime.writeAlwaysOn` (`L/server/MobileNetworkRuntime.kt:146-162`) at its end, after both the pref
  and Android's always-on are written (QA L2: never before the pref write). Every path that ends a sink goes through
  one of them: the watcher's reshape on Wi-Fi back or a country change (`KidVpnService.kt:338-380` -> `reshape`
  `:290-318`), `apply()` for the parent's choice, a pause, the override (`AppEnforcer` -> `KidVpnService.start`), the
  kid's tap (`MobileNetworkRuntime.setKidOff` -> `KidVpnService.start`).
  `UpEngine.shapeChanged()` notes the hold when `hold()` says held, and **whenever the engine has seen a hold**
  (`heldSeen`, QA M1 (b): not a transition of a value computed at this one call) asks `pullAfterHold()`: the "net" pull
  **2 s after the last such call** (each call moves it; the routes and the lockdown's removal are in place by then, and
  the gate measures it). When it runs, the pull checks `hold()` again: still held only because of `wanted` (the tunnel
  and the decision both not the sink: the two views of the network disagreeing for a moment, QA M1), it tries once more
  10 s later; held by the tunnel or the decision, it ends and the next shape change asks again. The "net" pull takes the
  pull's 30 s wake lock when it is asked and has its own future, apart from the ack flush (QA L3), so neither swallows
  the other and Doze can't stretch the 2 s.
- **The policy**: no hook of its own. `policyAccepted`'s pull (`UpRuntime.kt:205`) is asked before `apply()`
  (`L/server/MdmSyncWorker.kt:168`, then `AppEnforcer.apply` at `:261`): while the old shape still holds it is
  refused, and `apply()`'s shape change asks the pull through `shapeChanged`. The other way (the parent turns Bare Vibb
  on while the phone is on cellular) that pull may deliver just before the sink starts; the apps had a network when it
  was sent, and the send-time check stops the rest once `apply()` has decided the sink.
- **The refresh waits** (design 108b §3.3): a `NEW_ENDPOINT` refresh asks the app to re-register with its server,
  which it can't while held. `UpRules.refreshes` (`L/push/up/UpRules.kt:321-322`) takes `held` beside `inUse`
  (`if (!inUse || held || ...)`), threaded through `UpRules.pass` (`:327-335`) for both callers, `policyAccepted` and
  `userPresent` (QA L5); a due refresh waits for the next accepted policy or unlock. The liveness ping is not held: its
  ack is local, and Element X's `onNewEndpoint` (checked in its source, `VectorUnifiedPushMessagingReceiver`) only
  logs a failed pusher registration and doesn't store the endpoint, so the ping's next send with a network registers
  it (QA L5). The origin's rewrite is not held either (rare; the old origin answers for its 30 days, and the app
  re-registers at its next start).

### 2.5 The hold's end: the burst

- **Order:** the server hands out by `up_messages.id` (`S/unified_push/store.rs:416-419`), oldest first across apps;
  each app's `RaiseToForeground` sends its queue FIFO (`vendor/RaiseToForeground.kt:90-120`).
- **One wake per app:** the first message binds the app, later ones queue or send on the same binding, and each pushes
  the unbind 5 s out; a backlog over 50 is several pull steps about a second apart, inside that window. One 30 s wake
  lock for the pull (`UpRuntime.kt:295-306`).
- **No coalescing:** each Matrix push names its own event; Element X fetches each. The caps already keep the newest 100
  per registration.
- **Android's rate limit on notification updates** (NMS drops updates of an existing key above 5 a second per app):
  Element X fetches one push at a time (a network round trip each), so it should stay under it. Unverified: owed on
  the Jelly Star with Element X (R1-E).
- **The raise's window (QA M3).** All of an app's MESSAGEs go out within about a second (one step of up to 50,
  `flush()` in a tight loop, `vendor/RaiseToForeground.kt:156-158`), each pushing the unbind 5 s out: the app is raised
  for about 6 s in all. Element X starts no foreground service of its own for UnifiedPush; with 10-20 backlogged events
  its fetches can outlast the raise, and Android 15+'s background network rule can then cut it - the fallback this
  design removes. Today one push comes at a time, so the burst is new with 115. **The fix, named now and built only if
  the gate's sequential row or the Jelly Star shows failures:** pace one app's sends - while its raise queue holds more
  than 3, one every 400 ms; each send already extends the raise, so the raise covers the backlog (optionally a cap per
  hold-end burst). The gate's probe works through its MESSAGEs one after another with a 500 ms "fetch" each, so it
  sees this (§6, row 13).
- **Delivery time (108c §7):** a held push is first handed out after the hold, on the same stream generation (Vibb One
  stayed online), so its sample would count the hold as delivery time. The first pull run after a hold posts
  `{"ack": [...], "held": true}` on every step. **One owner (QA M5):** `heldSeen` lives in the engine alone; the
  runtime's early refusal, `shapeChanged` and the send-time hold reach it through `noteHeld()` on the `work` executor.
  It is cleared only when a run whose steps said `held: true` ends with an answer; a run whose call failed keeps it, so
  the next run says it again. It is in memory only, which is enough: a process death opens a new stream generation, and
  `store.rs:446-452` keeps a sample only for a push handed out first on the stream it was accepted on. The server passes no stream
  generation for that pull (`S/unified_push/device.rs:237-238`: `let stream = if pull.held { None } else { ... }`), so
  `store::pull`'s hand-out (`store.rs:446-452`) leaves `stream_ms` null and the ack writes no sample (`:396-402`). An
  older server ignores `held` (`Pull` at `device.rs:219-222` has no `deny_unknown_fields`). `held` is left out when
  false, so `messages.body` stays byte-identical.

### 2.6 TTL and the caps while held

Unchanged (design 108 §3.6, Q10): a push expires on the server after its TTL (our gateway: 24 h); a pull never returns
an expired row and the hourly prune deletes it. A TTL-0 push is kept 30 s while the stream is open, which it is
during a hold, so it is lost: its sender asked for "now or never". A hold longer than a day (a weekend without Wi-Fi
under Bare Vibb) loses the older pushes' notifications; Element X's next push or start syncs the rooms, so the
messages and unread counts are there, only those notifications are missing. See ASK 3. The caps drop the oldest and
count `dropped` per registration, as today.

### 2.7 Element X's fallback

Element X posts "Du har N nye meldinger" only when it got a MESSAGE and couldn't resolve it. While held no MESSAGE is
sent (§2.4, the pull and the send), so the fallback can't come from a held push. It can still come from a push
delivered on a network that fails for a reason outside our model (§2.1's not-covered list, the server down), as
today. Whether Element X replaces an earlier fallback once a later push resolves is Element X's; the Jelly Star check
looks at the shade after a hold.

**Its shape (QA M4, checked in Element X's source at the build):** `DefaultNotificationCreator.createFallbackNotification`
(`libraries/push/impl/.../factories/NotificationCreator.kt`) is a plain `NotificationCompat.Builder` with
`setContentTitle(buildMeta.applicationName)`, the plural "N new messages" as its text and a counter extra - no
MessagingStyle, no sender. §3's rule for plain notifications covers it.

### 2.8 The report and the server

**The field.** A full-report field (not a heartbeat field: its hash covers it, so a change sends the full report),
present while UP is on (`UpView.on`) and the phone is unlocked, absent otherwise:

```json
"unified_push": {"held": "no_network"}
"unified_push": {"held": "none"}
```

- Launcher: `StatusReportRequest.unifiedPush: UpReport? = null` (`@SerialName("unified_push")`, beside
  `notificationChannels` at `L/server/dto/StatusReportRequest.kt:153`), built in `statusReportOf`
  (`L/server/MdmSyncWorker.kt:~509`, beside `:615`) by `UpRuntime.report(context)` with the same `hold()`.
- Server: `models.rs`'s `StatusReportRequest` gains `#[serde(default)] pub unified_push: Option<serde_json::Value>`
  (the pattern of `notification_channels`, `S/models.rs:1694-1697`), and `S/status_heartbeat.rs`'s `REPORT_KEYS`
  (`:51-103`) gains `unified_push` (QA L7: else `kept_body` drops it, and every heartbeat-expanded row of a hold stores
  `up_held` NULL; `the_report_keys_are_the_structs` fails the build until it is there); `unified_push::clean_report(&Value) ->
  Option<String>`: an object whose `held` is a string of 1-32 characters `[a-z_]` other than `"none"` is that word (a
  future reason from a newer launcher still reads as held on purpose); anything else is `None`. Stored in a new column
  by `device_api`'s intake (`S/handlers/device_api.rs:1549-1553` pattern, the INSERT at `:1579-1592`).
- **Migration** `0156_up_held.sql` (QA L8: 0155 is the last; design 114 also takes "the next free number", so the
  second of the two to merge renumbers):
  `ALTER TABLE device_status ADD COLUMN up_held TEXT;` (kept with its status row; no retention change).
- No capability and no policy key: absent (an older launcher) and `"none"` both mean "not held on purpose", which is
  today's reading.

**The Attention guard** (108c §1.1 arm (a), `S/unified_push/health.rs:142-148`). Each sync fetches the policy first and
posts its report seconds later (`L/server/MdmSyncWorker.kt:139` then `:357`), so a sync made while holding is followed
by a report that says so. Arm (a) counts only policies served **after the last held report** in its window:

```sql
SELECT MAX(reported_at) FROM device_status
 WHERE device_id = ? AND reported_at >= ? AND reported_at <= ? AND up_held IS NOT NULL
   AND COALESCE(json_extract(mobile_data_json, '$.sink'), 'off') <> 'off'
```

**Not on the phone's word alone (QA M2).** A held report counts only when the same row's `mobile_data.sink` (design
95 rev. 3's report, `reportedSink`, filled from the same inputs and the persisted shape) is not `off`: a real hold always
reports a sink reason, also while no network is seen and a persisted sink is kept. A hold that is wrong (§9: the VPN
taken over; a disagreement of the launcher's two views of the network) reports `sink: "off"` and leaves the Attention
row free to rise - the case 108c exists for.

with the window's text bounds (the `(device_id, reported_at)` index, as `policy_problem_between` at `:153-170`); the
count runs from `max(C + 10 min, that + 1 s)`. Only read when the oldest push is 30 minutes old and unfetched.

- A phone holding throughout: every served policy has a held report after it, nothing is counted, no row.
- A hold that ends: the next three syncs after the last held report count, so a pull that still doesn't run after the
  hold (a wedged engine) raises the row as it would today, about an hour later.
- A held report before `C + 10 min` changes nothing.
- Arm (b) needs no guard: a held phone makes no pull, so nothing is handed out twice (§2.2); the send-time hold
  (§2.4) re-hands a message only on the first pull after the hold, which acknowledges it within seconds.
- A sync whose report failed after its policy was served counts that policy (the safe direction: at worst a row).
- Held reports whose `mobile_data.sink` is `off` (or missing) don't count: the row rises as today (QA M2).
- A hold seen through heartbeats counts: the heartbeat's expanded row carries the kept full body's `unified_push`
  (QA L7).
- Device › Apps' waiting line calls the same function, so it stays quiet with the row.

**The contract** `server/testdata/unified_push.json` (byte-identical in `LT/resources/`), one commit with both sides:

- `report`: `{"unified_push": {"held": "no_network"}}`; `report_open`: `{"unified_push": {"held": "none"}}`.
- `clean`: rows `{in, stored}`: `{"held": "no_network"}` -> `"no_network"`; `{"held": "none"}` -> `null`;
  `{"held": "app_off"}` -> `"app_off"`; `{"held": 5}`, `{"held": null}`, `{"held": "No Network"}`, a 33-character word,
  `"x"`, `{}` -> `null`.
- `messages.body_held`: `{"ack": ["deadbeef000000000000000000000001"], "held": true}`.
- The launcher's `UnifiedPushContractTest` encodes `report`, `report_open` and `body_held` exactly (and `body` still
  without `held`); the server's `tests::unified_push::the_contract` (`S/tests/unified_push.rs:95`) posts both reports
  and checks `up_held`, runs `clean`, and posts `body_held` against a push accepted on the open stream and checks the
  ack left no `up_delivery` row. `intake_limits` keeps `unified_push`'s key (`S/tests/intake_limits.rs:453` pattern):
  `report`'s `held` is stored as written.
- The CI path lists don't change (the same files).

## 3. Part 2: the glance row's name

Today: `GlanceReader` reads the notification's title at "Navn" and up (`L/badges/GlanceReader.kt:174-183`, via
`glanceTitle`, `L/badges/GlanceRows.kt:207-208`); the panel shows `row.title ?: label`
(`L/lock/GlancePanel.kt:1384-1388`), where `label` is already the display name (`AppDisplay.shownLabel`,
`:1554-1555`; `L/appdisplay/AppDisplay.kt:187-203`: the parent's name, the kid's rename, the app's own). Element X's
fallback titles the notification with its own label, so the row reads "Element X" next to the parent's glyph.

- **The rule** (pure, `L/badges/GlanceRows.kt`): `fun glanceShownTitle(title: String?, shape: GlanceShape, sender:
  String?, appLabel: String?): String?` - `null` when `title` is null, or when the row is a plain notification
  (`shape == PLAIN && sender == null`: its title came from `EXTRA_TITLE`, QA M4) and `appLabel` is known and both fold
  equal; else `title`. A MessagingStyle conversation's or sender's name (DIRECT, GROUP, or a sender) is never replaced.
  The fold (QA L9): the label cut as the title is (`cleanGlanceText(label, GLANCE_TITLE_MAX)`, so a long label cut with
  "…" still matches), both NFC-normalised, then `lowercase(Locale.ROOT)`. An empty title is already `null`
  (`cleanGlanceText`, `:369-370`).
- **Where:** first line of `GlancePanel.bindMessage` (`:1384`): `val row = row.copy(title = glanceShownTitle(row.title,
  row.shape, row.sender, ownLabel(row.packageName)))`. Everything after reads the substituted row: the name (`row.title ?: label` now gives the
  display name), the suffix (`glanceNameSuffix`, `:274-276`, none without a title), TalkBack's parts
  (`glanceSpokenParts`), the missed-call wording. `ownLabel` is a second cache beside `labels` (`:241`, cleared with it
  at `:432`), from a new `AppDisplay.ownLabel(context, pkg)` split out of `shownLabel` (`AppDisplay.kt:197-202`); it
  returns `null` when `getApplicationInfo` fails, never the package name `shownLabel` falls back to (QA L9).
- **When it shows.** A plain notification has a title on the glance only at "Full text" (`GlanceLevel.TEXT`;
  `glanceLevel` is APP_ONLY at "Navn" without MessagingStyle, `L/badges/GlanceRows.kt:197-203`). So the user's row was
  at "Full text", and that is where the rule acts.
- **Not in `GlanceReader`:** `LockGlanceGuardTest` allowlists that file's identifiers and reads; the label isn't
  notification content and needs no guard change. The notice store keeps the app's real title.
- **Android's shade** stays as the app posted it; a notification listener can't rename another app's notification.
- A plain notification genuinely titled like the app shows the display name: harmless. A MessagingStyle conversation
  named like the app keeps its name (the PLAIN-only rule).

## 4. Android 14-17

Checked against the platform's behaviour for 34-37 (the gate runs 14 and 16; 17 as below):

- **Per-UID network under our VPN.** `VpnService.Builder.addDisallowedApplication` (API 21) gives netd a UID range
  without the launcher; every other UID's traffic follows the tunnel's routes. In the sink that is everything; with
  lockdown (`setAlwaysOnVpnPackage(..., lockdown = true)`, the BPF lockdown chain since 13) a dead tunnel still cuts
  them. The apps' default network is the VPN with INTERNET (and validated when the underlying network is), so an app
  can't tell it is cut - which is why the launcher decides. Same on 14, 15, 16; 17: read API 37's behaviour-change
  list for VPN and per-UID networking at the build.
- **How the launcher knows** (§2.1): its own shapes. No hidden API; no new permission.
- **Network callbacks.** None new. The VPN service's `registerBestMatchingNetworkCallback` (API 31,
  `KidVpnService.kt:375`) already sees Wi-Fi come and go and reshapes; the launcher's own `activeNetwork` is the
  physical one (excluded from the VPN). With no VPN service (shape none) there is no sink and so no hold.
- **Android 15's background network rule** (apps outside a valid process lifecycle get no network) and Doze: the raise
  (design 108 §5.4) puts the app at `FOREGROUND_SERVICE` for 5 s on every version; unchanged.
- **Notification update rate** (NMS, 5 a second per app for updates of a posted key): the same on 14-17; §2.5.
- **Glance (Part 2):** `PackageManager.getApplicationLabel` and the listener's extras, unchanged on 14-17.
- **17:** `compat372` kills system_server on a screencap (§6as), so any 17 run is logcat and `dumpsys` only; owed, as
  for design 108's gate.

## 5. Tests (lean)

Launcher:
- `UpRulesTest`: every `hold` row (via `holdOf`) and the new `refresh` row.
- `UpEngineTest` (`FakeUpPhone` gains `var hold` and counts `pullAfterHold`): held -> `requestPull` false and no
  `server.pull`; held at a step -> the pull ends, `acks` kept; the hold ends -> the next pull sends the kept acks with
  `held = true`, every step of that run too, the following run without; a run with `held` whose call fails keeps it for
  the next (QA M5); a send that fails for the hold -> no ack, `pullAfterHold` asked, and with no change of shape at all
  the message is delivered on that pull (QA M1 (d)); `shapeChanged` after a hold asks `pullAfterHold`, before any hold
  it asks nothing.
- `UnifiedPushContractTest`: `report`, `report_open`, `body_held` encoded exactly; `body` unchanged.
- `GlanceRowsTest`: `glanceShownTitle` rows (PLAIN, no sender unless said) - `("Element X", "Element X")` -> null;
  `(" element  x", "Element X")` -> null; `("Mamma", "Element X")` -> `"Mamma"`; `(null, "Element X")` -> null;
  `("Mamma", null)` -> `"Mamma"`; a DIRECT row titled "Element X" -> kept; a GROUP row "Element X" -> kept; a PLAIN row
  with a sender -> kept; a 70-character label cut like the title -> null (QA M4, L9).

Server:
- `tests::unified_push::the_contract`: the report rows, `clean`, `body_held` (above).
- `tests::unified_push::attention_rows_of_108c` (`S/tests/unified_push.rs:1784`) gains: three served policies, each
  followed by a held report -> not raised; a held report, then three policies after it -> raised; a held report before
  `C + 10 min` -> raised as before; held reports with `mobile_data.sink: "off"` -> raised (QA M2).
- `tests::status_heartbeat` (or `tests::unified_push`): a full held report, then a heartbeat -> the new row's
  `up_held = 'no_network'` (QA L7).
- `delivery_time_on_the_pages` (`:2206`) or `store::tests`: a `held` pull leaves no sample; the next plain pull's
  pushes do.

## 6. The emulator gate (rows on 14 and 16)

**Needs the user's OK first** (the standing rule of 2026-11-17: no emulator round without it). The rig is
`docs/testing/emulator.md` §6ay's (`compat34`, `jelly36`, the debug server with the origin `https://up.test`, the
probe `me.vibb.probe.up`, `scripts/fake-homeserver.py`) with §6ak's Mobilnett setup (emulated LTE, `adb shell svc wifi
disable|enable`, the PWA's Mobilnett page). New section §6ba.

What it needs:
- **The probe** gains: on each MESSAGE, log the body's `event_id` (the script numbers them `$fakeEvent0001`, ...) and
  one `GET http://10.0.2.2:3109/` with a 3 s timeout, logged `net=ok|fail` (cleartext allowed in its manifest) - **one
  message after another on one worker thread, each "fetch" taking at least 500 ms** (QA M3 (a): Element X's own pace,
  so the raise's window is tested); a command `notify --es title T` posting a plain notification titled T on a HIGH
  channel, and `notify --es title T --ez messaging true` a MessagingStyle one whose conversation title is T.
- **`scripts/fake-homeserver.py`**: nothing new (`notify --count N --gap-ms G`, `up --ttl S` exist,
  `scripts/fake-homeserver.py:101-112`).
- The launcher's debug log names "Pull held (no_network)", "Pull for net" and `held=true` on a pull (by reason and
  count only, no keys beyond the existing 8-character prefixes).
- SQLite reads on the debug server: `up_messages` (`fetched_ms`, count), `up_delivery` count, `device_status.up_held`.

| Row | How | Expected | Versions |
|---|---|---|---|
| 1 baseline | Wi-Fi on, Bare Vibb on; `notify` one | delivered, `net=ok`, acked | 34, 36 |
| 2 held | Wi-Fi off (`Tunnel: sink`); `notify --count 5 --gap-ms 500` | no MESSAGE, no probe notification; "Pull held"; 5 rows, `fetched_ms` null; after Sync now `up_held = 'no_network'` | 34, 36 |
| 3 back | Wi-Fi on | within ~3 s of `Tunnel: pass` (or `filter`): five MESSAGEs `0001`..`0005` in order, each `net=ok`; one `FOREGROUND_SERVICE` window for the probe (`cmd activity get-uid-state` every 0.25 s); queue empty after the ack pull; that pull logged `held=true`; `up_delivery` unchanged; next report `up_held` null | 34, 36 |
| 4 the kid's switch | Bare Vibb off, bit 32, the kid's Mobildata Off, Wi-Fi off; `notify`; then the kid's switch On | held, then delivered with `net=ok` | 34, 36 |
| 5 the parent's choice | held as row 2; the PWA's Mobildata "På" | delivered within the save's sync plus 2 s | 36 |
| 6 a pause | held as row 2; "Åpne for oppsett" from the PWA | delivered; the pause's end with Wi-Fi still off holds again | 36 |
| 7 airplane | airplane on, Wi-Fi off; `notify`; then Wi-Fi on in airplane | no pull while offline; delivered on Wi-Fi | 34, 36 |
| 8 abroad | §6ak D's roaming setup, "I utlandet: Bare Vibb"; `notify`; then `gsm data home` | held (`sink` `abroad`), then delivered | 36 |
| 9 TTL | Wi-Fi off; `up --ttl 60`; wait 90 s; Wi-Fi on | nothing delivered; the row still in `up_messages` past `expires_at_ms` or pruned (QA L10: the prune is hourly) | 34 |
| 10 Wi-Fi drops mid-pull | `notify --count 20 --gap-ms 0` and `svc wifi disable` in the same second | record: every MESSAGE either `net=ok` or not sent ("held" at the send); none lost after Wi-Fi on | 34, 36 |
| 11 Attention quiet | row 2's hold; backdate the oldest row's `created_at_ms` 40 min (SQL); Sync now three times, wait 2 min; the Devices list and the summary. Controls (QA L10, M2): the same with the held rows' `up_held` set NULL by SQL, and with their `mobile_data_json`'s sink set `off` | no badge, no "App notifications aren't reaching the phone"; after Wi-Fi on delivered, still no row. Each control: the row raised | 36 |
| 12 the glance name | the PWA names the probe "Chat"; the probe ticked on the glance at **"Full text"** (QA M4: a plain notification has no title at "Navn"); `notify --es title "UP Probe"` (its label), then `--es title Mamma`, then `--es title "UP Probe" --ez messaging true` | the glance rows read "Chat", "Mamma" and "UP Probe" (a conversation's name is kept); the shade shows "UP Probe" | 34, 36 |
| 13 the burst (QA M3) | Wi-Fi off; `notify --count 20 --gap-ms 200`; Wi-Fi on; the probe's sequential 500 ms fetches | record every MESSAGE's `net=ok|fail` and the probe's uid state: all 20 `net=ok` passes; any `fail` builds §2.5's pacing | 36 |

Owed on the Jelly Star (Element X, R1-E): Bare Vibb, Wi-Fi off, a few chat messages, Wi-Fi on - the real messages
notify, no "Du har N nye meldinger", the last message's notification present (§2.5's rate limit).

## 7. Docs to update (in the build)

- `docs/notes/launcher/unified-push.md`: a section "Held while the apps have no network (design 115)" - the rule, the
  three shapes it reads, the hooks, `held` on the pull, the report.
- `docs/notes/server/unified-push.md`: the report field and `up_held`, the waiting row's guard (108c section), the
  `held` pull and the delivery figure.
- `docs/notes/launcher/lock-and-glance.md`: the glance row's name rule (one sentence beside the row's title).
- `docs/notes/launcher/dns-and-vpn.md` "Three shapes": one line - leaving the sink asks app push to pull.
- Root `CLAUDE.md`: the `unified_push.json` paragraph gains `report`/`report_open`, `clean`, `messages.body_held`; its
  launcher-only rule list gains the hold.
- `docs/testing/emulator.md`: §6ba (§6).
- `PLAN.md`: the item's state line through the build and the merge.

## 8. Size and order

- Launcher S: about 120 lines of code (the rule, the engine's two checks and `heldSeen`, the hooks, the report, the
  glance), about 120 of tests and rows.
- Server XS: about 60 lines (the field, the clean, the migration, the guard, `held`), about 80 of tests.
- One branch; the contract commit carries both sides. Either release order works: an older server ignores the report
  field and `held`; an older launcher sends neither.

## 9. Risks and what this doesn't cover

- Per-app cuts by Android that leave Vibb One online (Data Saver, an app's background-data switch, Low Power Standby)
  and a DNS filter that blocks the app's server: delivered as today, and Element X's fallback can still show.
- A recorded `vpn_shape` of `sink` with the VPN taken over by another app (prevented by `DISALLOW_CONFIG_VPN` unless the
  parent lifted it, `dns-and-vpn.md` L1): the phone would hold while the apps have a network. Accepted.
- The burst against Android's update rate (§2.5): owed on the Jelly Star.
- The first pull run after a hold leaves no delivery samples, also for a fresh push that lands in that run: negligible.
- A MESSAGE sent in the last moment before the sink starts can still fall back in the app (QA M3 (c)): the send-time
  check sees the hold only once a view says the sink. That race is inherent.
- The burst after a long hold against the 5-second raise (§2.5, QA M3): the gate's row 13 and the Jelly Star decide
  whether the pacing is built.
- The second bullet above (the VPN taken over) is now closed for the pull by QA L1 (`decided` counts only with our
  lockdown) and for the Attention row by QA M2.

## 10. ASK

| # | Question | Recommended | Skipped in the build |
|---|---|---|---|
| ASK 1 | Show the hold on Device › Apps' App push group? A quiet line, e.g. en "Waiting until the apps have internet", nb "Venter til appene har internett", only while the latest report says held. | No line: the hold is the parent's own choice working as meant, and "Words in the UI" says a state that is as it should be shows nothing. | No line. |
| ASK 2 | Pushes that carry their own text (a Mastodon / Fedilab Web Push shows its notification without fetching): deliver them during a hold, per app? | Hold them too: one rule for all apps, the user's own words ("varslene henger igjen"), and the phone can't tell from encrypted bytes whether an app needs the network. | Hold all. |
| ASK 3 | Keep held pushes longer than the 24 h TTL (Q10 of design 108), e.g. up to 72 h, for a long stay without Wi-Fi under Bare Vibb? | No: the chat apps sync their rooms at the next push or start, notifications for day-old messages are noise, and the queue's caps would bind first anyway. | 24 h as today. |

## Revision after the design QA (2026-11-18)

`docs/review/115-up-held-design-qa.md` (0 H, 5 M), all applied: M1 - the hold's end follows `heldSeen`, not a computed
transition; `wanted` from the service's own view; a send refused for the hold notes it and asks the "net" pull; the
"net" pull retries once at 10 s when only `wanted` holds (§2.4). M2 - the Attention guard counts a held report only with
a sink that isn't `off` (§2.8). M3 - the raise's window named, the pacing named and left to the gate's row 13 and the
Jelly Star (§2.5, §6, §9). M4 - the glance rule only for a plain notification without a sender; Element X's fallback
checked in its source: plain (§2.7, §3); gate row 12 at "Full text" with a MessagingStyle row. M5 - one owner for
`heldSeen`, its life spelled out (§2.5). The Lows: L1 (lockdown and a running service), L2 (where `shapeChanged` runs),
L3 (the "net" pull's wake lock and own future), L4 (the sink short-circuit), L5 (the refresh through `pass`; Element X's
`onNewEndpoint` only logs), L6 (not-covered list), L7 (`REPORT_KEYS`), L8 (0156), L9 (the fold), L10 (rows).
