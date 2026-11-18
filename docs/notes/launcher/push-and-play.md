# Launcher notes: push, battery, Play

Moved from launcher/CLAUDE.md on 2026-11-01.

## Push, battery and Play (handy step 7, design `docs/design/07-battery-fcm-play.md`, at the monorepo root)

- **Anchor** (`CommandListenerService`, `specialUse` with subtype - `dataSync` is capped at 6 h/day and can't start from BOOT_COMPLETED on Android 15): all background syncs and the SSE stream, held whenever it runs (since design 19; read timeout 300 s vs. the server's keepalive, 240 s by default). It syncs once at start and arms the backstop. `SyncRunner.request` takes a timed partial wake lock, then `startForegroundService(ACTION_SYNC)` (also revives a dead anchor - device owners may start FGS from the background); runs coalesce (`SyncCoalescer`: a burst = one extra run), 10 min/5 min timeouts (APK downloads run inside; `CancellationException` is rethrown in the update loop), 11 min wake lock cap, released only when no run and no undelivered request is left. Every launched `apply()` is wrapped - an exception reaching the crash handler would end the call path. A re-check re-applies until the enforced Play key equals the current one (screen on right after screen off).
- **Notifications in the shade** (PLAN "Fewer persistent notifications", 2026-10-21, branch `lfix-notifs`, code QA
  `qa-lfix-notifs-code.md`): the anchor's is the phone's one standing notification, id 1002 - since design 100 the
  Snarveier panel while it is shown (kid-settings.md, "Snarveier"), else "Vibb One" (`notification_listener_title`,
  en/nb), no body, MIN channel `launcher:command_listener` (named "Vibb One" since design 100 D7, was "Find My Device
  background connection"), the Vibb mark `ic_stat_vibb` as its small icon; NMS shows a foreground service's at LOW at
  least, so its status-bar icon stays while the anchor runs (Android has no hidden FGS). The filter's "kobler til …",
  Play's install mode, the ring and the assistant's setup notice carry `ic_stat_vibb` too (design 100 §4; every
  `ic_stat_*` drawable has literal colours - a `?android:textColor` fill draws an empty disc in SystemUI, M10).
  Since 2026-11-17 `ic_stat_vibb` is the mark's two-ring variant from the user's logo set (Logo_design_request5: two
  rings and the core, white on transparent, the mark in the 20 dp live area of 24 dp - the four rings blurred at 24 px
  in the shade and 33 px in the status bar on 240 dpi), a source file with its origin in the header (NOTICE.md);
  `launcher/scripts/vibb-icon.py` no longer writes it.
  `KidVpnService` goes foreground first in `onCreate` as before and drops it (`stopForeground(STOP_FOREGROUND_REMOVE)`,
  only on `VpnSettle.DROP` in `settleForeground`) once the tunnel is up **and** `isAlwaysOn` (a throw counts as not):
  AOSP's `Vpn.establish` binds an established VpnService from system_server with `BIND_AUTO_CREATE |
  BIND_FOREGROUND_SERVICE` (= `PROCESS_STATE_BOUND_FOREGROUND_SERVICE`, the Doze network threshold
  `FOREGROUND_THRESHOLD_STATE`), and the anchor's FGS is in the same process anyway. The state - tunnel, foreground,
  stop, the retry's back-off - is the pure `VpnTunnelState` (`server/VpnForeground.kt`, `vpnHoldsForeground`,
  `QuietNotificationsTest`), changed on the main thread only (`onRevoke` comes on a binder thread and posts its stop
  there). A failed establish or a VPN that isn't always-on yet (first launch before the first sync) holds the
  notification; a read loop that ends (a thrown read or end of stream) closes the tunnel, takes the notification back
  and retries inside the service - 5 s doubling to 5 min, reset by a tunnel that held 10 min, one chain at most
  (Handler time: the retry waits out deep sleep). The notification then reads "Content filter connecting…" /
  "Innholdsfilteret kobler til …", no body - it shows only while the filter is starting or down. Setting always-on
  while our tunnel already runs restarts nothing (`VpnManagerService.setAlwaysOnVpnPackage` calls `startAlwaysOnVpn`,
  which skips a connected VPN), so `applyVpnRestrictions`' `start()` right after is what re-checks; while the tunnel is
  down every `apply()` also gets the system's plain always-on start (a second heal path). Clearing it revokes the
  tunnel (`prepareInternal(LEGACY_VPN)` -> `onRevoke`). `KidVpnService.start` is a plain `startService` whenever an
  instance runs (`vpnStartKind`: a started record skips the background check and carries no `fgRequired`, so a stop
  racing it can't crash us, and nothing is re-posted at every sync), else `startForegroundService` with
  `EXTRA_FOREGROUND`, which `onStartCommand` answers with `startForeground` before anything else (also the
  `IllegalStateException` fallback). Not device-tested: PLAN's checks (one notification in the shade, the filter
  through a night in Doze and a reboot without its notification).
- **Screen time left in the anchor's notification** (design r13 step 3, `docs/design/r13-launcher-rule-card-and-time-left.md`
  §2, at the monorepo root): with a budget set today the same notification (id 1002, the MIN channel, its icon) has the
  time left as its title ("1 t 20 min igjen i dag", `anchor_time_left`; "Skjermtiden er brukt opp i dag") and one
  action "Be om mer tid"; with no budget today, in the pause or the override it is the plain "Vibb One". Pure
  `timerules/TimeLeftNotice.kt` (since design 100 step 2 `timePart(budget, windowOpen, state, policy, now, surface,
  dayStart)` -> `TimePart(text, note, ask)`, `leftMinutes` rounded up; `TimeLeftNoticeTest`); glue
  `server/AnchorNotice.kt` (`build` - silent, ongoing, no time, only-alert-once; the action, only while `ask` is
  `Action`, `NotificationCompat.Action.Builder(..).setAuthenticationRequired(true)` to the explicit, non-exported
  `timerules/TimeAskReceiver`; the note (`noteText`: "Spurt kl. 14:02 · venter på svar", "Pappa svarte nei kl. 14:10",
  `ask_note_*`) is the content text, never a button - R13-8). `refresh` computes the time part and hands it to the one
  poster; it posts nothing itself.
- **One poster of id 1002** (design 100 step 1, `docs/design/100-one-vibb-notification.md` §2): `server/AnchorNotification`
  posts every view of the anchor's notification, on its own thread `anchor-post` (design 99's refresh thread). The view
  is the pure `anchorView(panel, time)` (`server/AnchorView.kt`, `AnchorViewTest`): `Panel` while Snarveier is shown,
  else `Plain` - also when the panel model is missing or `computePanel` throws (Q100-M1); one record, swapped between
  the MIN listener channel and the panel's LOW channel by `notify` (M1-M3: one record and the foreground state kept, the
  row changes in place with the shade open). It keeps its application context from its first caller (`forStart`,
  `notice`, `ShortcutsRuntime.init`); sets Mer tid's state from the time part it posts with before it builds the panel
  (`ShortcutsRuntime.panelFor`, Q100-L6); posts only a changed `(view, locale, channel)`, at most once a second with the
  trailing post (`AnchorPostQueue`, M5); makes sure of the view's channel right before every build (`ensuredBuild`:
  `createListenerChannel` or the panel's `ensureChannel` - a build on a missing channel crashes the launcher when the
  deferral ends, M7); checks in the trailing slot (1.5 s after a post) that 1002 is up with the posted channel and view
  key (`EXTRA_VIEW_KEY` in the extras) and posts again once on a mismatch (a shed update, Q100-L4/R2-L11); and does the
  running check and the `notify` under `AnchorNotification.lock`, which the anchor's `onDestroy` takes before it clears
  its instance (Q100-L5). `onCreate`: `startForeground(1002, AnchorNotification.forStart(this))` literally first - the
  view this process posted last on that key's channel (made sure of; never `computePanel` or the shortcuts prefs, Q100-L7),
  else, or on any exception, `AnchorNotice.plain` on the listener channel, with the time part already cached when there
  is one (code QA L5) -, then `anchorStarted` (cancels design 99's
  old 1011 once, asks for a refresh) and `refreshNow` (CE prefs only after it). The plain notice has no
  `FOREGROUND_SERVICE_IMMEDIATE` (deferred ~10 s unless it has its action, Q100-L2), the panel has it. Not
  emulator-checked yet: design 100 §11. Refreshed from every `TimeRulesRuntime.recheck` with
  its snapshot (counting or not, R13-3), from `ScreenTimeTracker.update`'s counting branch (the checkpoint's delay is
  then `checkpointDelayMs(remaining)` - the next minute flip + 200 ms; else `CHECKPOINT_MS`), the tap, the server's
  answer and the pause/override start (`AnchorRefresh.request`). **The request** (`timerules/TimeRequests.kt`,
  `TimeRequestsTest`; design 100 step 2, `docs/design/100-one-vibb-notification.md` §14): DE prefs `time_request`
  (`record_v1`: `TimeRequestRecord(id 32 hex, askedAtMs, day = the ledger's, liftIdAtAsk, PENDING|ASKED)` - r13's
  `openUntilMs` stays a field with a default so an old record decodes, a new one doesn't write it -, `count_v1`:
  `TimeRequestCount(day, accepted)` - the phone's own three a day, D5), one `commit()` under its lock. **One request
  open at a time**, until a parent answers it or the phone's day ends (r13's 30 minutes are gone): pure `requestState(
  record, count, day, policy)` - a record of today is `Pending(at)`/`Waiting(at)` until the policy's
  `time_policy.answer` names its id (`Answered(no, by, at, minutes, canAskAgain)`; on an older server without
  `time_policy.request_answers`, a lift newer than the one at the ask closes it as r13 did, §14.7 - only a PWA's
  screen-time lift among them is "Du fikk N min mer", a rule pause closes it with no words of time, code QA L4), else
  `Capped` at
  three, else `Idle`; `askStep` (enrolled, a budget today, outside the pause, Idle or Answered under three),
  `timeRequestAnswer` (§2.4's table: accepted, capped - the count set to three -, stale/other 4xx/unparsable dropped, 404
  dropped silently, 401/408/429/5xx/none kept and retried while it is of today) and `afterTimeRequestAnswer`. The note
  (`askNote`, §14.3): Pending "sender …" (15 s on: "sendes når telefonen er på nett", `SENDING_OFFLINE_MS`), Waiting
  "venter på svar", the parent's answer ("Pappa svarte nei kl. 14:10", "Mamma ga deg 30 min"; without a display name
  "Svaret ble nei kl. …"/"Du fikk 30 min mer", D11), and with nothing open time a parent gave without a request (D10:
  `givenLift` - the PWA's newest `budget` lift with a positive id, no `phone_key` and an `at_ms`; the answer's name for
  a lift that answered a request). D12: the shade shows an answer or a gift for 30 minutes (`ANSWER_NOTE_SHADE_MS`), the
  time-rule screen as long as it is today's (`NoteSurface.SCREEN`). `noteChangeMs` (the 15-second mark, the 30
  minutes' end) is one more `TimeRulesRuntime.nextBoundary` instant where r13's `openUntilMs` was, so the shade and the
  screen follow on time across a process death (QA R2-L3). `TimeRequests.upload` runs in `performMdmSync` right after
  `PowerEvents.upload` and posts only what the pure `uploadStep` says is still open - a request of today, offline all
  afternoon too; another day's, or one an older server's lift answered while it waited, is dropped unsent (Q-3); one
  this server already answered is counted as taken, `COUNT` - its 200 was lost and a parent answered before the next
  sync: ACCEPTED's step, so the day's count follows, code QA L1 -; the answer writes only the record it sent
  (`recordAfterUpload`); the tap asks `SyncRunner.request(.., "time_request")` and arms `TimeRuleAlarm` (Q-4). The
  tracker's minute tick refreshes from the budget it computed (`AnchorNotice.refresh(context, budget, policy, day, now)`,
  no snapshot, Q-5). The policy's keys (`time_policy.request_answers`, `answer`, `lifts[].by`/`at_ms`) are the shared
  contract `time_request.json`'s `policy_*` rows; the launcher claims `time_answer_v1`. Not emulator-checked yet:
  design 100 §11 check 8.
- **Backstop** (`push/BackstopAlarm.kt`): `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`, request code 7, `backstopDelayMs`: 30 min, 15 while SSE is down (on the up->down edge, and only ever moved earlier then - a flapping stream must not push it out; each reconnect syncs once instead), sooner for an interval location policy, floor 9 min. Its sync request also reconnects a stalled or stale SSE stream (`checkStream`, design 19 below).
- **Play** (pure `play/PlayPolicy.kt`, `PlayPolicyTest`; `play/PlayRuntime.kt`): Play services + GSF are in `neverRestrict` (other apps' FCM - Element X; ours is gone since design 19). The Play Store is never hidden but **suspended** while apps are managed (also during a lock, also if allowlisted) except in install mode and the nightly update window (02:00-04:00, only while the screen is off - screen-on ends it at once, screen-off resumes it); never pinned or shown on Home/drawer (`AppFilter`, `playPackageLaunchable`) except in install mode **and, in the drawer only, while the override or the pause is open (design 28: `PlayRuntime.storeListable` feeds `AppFilter`; Home's grid drops the store outside install mode with `playPackageOnHome` in `refreshApps`); a window's edges reload the app list** (`Application.initRest`'s `WindowRelock.addListener { loadApps() }` - not Play's suspension edge, which never comes on a GMS phone). Override/pause lift it like any app restriction. `shouldSuspendNewPackage` leaves Play packages to `apply()`; a vending (re)install re-applies. `AppEnforcer.lastEnforcedPlayState` + `PlayState.key()` make `reevaluateLockReasonFromCache` re-apply on install-mode start/end and window edges (screen on/off, `TimeRuleAlarm` also wakes for `PlayRuntime.nextEdge`).
- **Install mode**: Settings -> "Install from Play" (Settings gate + the PIN again, shared lockout; refused unless managed, with a PIN, no time lock, Play installed - `canStartInstallMode`). 15 min by wall clock, elapsed realtime and boot count (`timedWindowActive`), CE prefs `play_state`; pins only the Play Store, unsuspends it, opens it; ongoing notification with "End now" (`InstallModeReceiver`). Ends on expiry (boundary alarm), "End now", reboot, or a time lock beginning (`apply` cancels it); then Home is brought to front. Reported as `install_mode.until_ms`.
- **Links**: `PlayLinkBlockedActivity` is the persistent preferred activity for `market://` and `http(s)://play.google.com/store*` (declares the same filters), set on every `apply()` and **cleared only by `HomePin`'s re-seat, which adds every PPA of ours back in the same pass (design 59d step M, `PlayInvariantsTest`)** - any other clear would drop the HOME pin (QA #2). When the Play Store isn't suspended it forwards the link to it explicitly. Explicit/component intents into Play are stopped by the suspension (QA #1).
- **Sources/DNS**: `InstalledApp.installer` (`getInstallSourceInfo`), and since 2026-10-25 `versionCode`/`versionName` (`PackageInfo`, the name trimmed and cut to 60 by `installedVersionName`; both left out when unknown - the server's `app_updated` for every checked catalog app, `SyncScheduleTest`); a catalog update for a package Play installed is skipped and reported as failed. New unallowed installs request a sync (reported at once). The DNS filter never blocks FCM hosts (`push/FcmHosts.kt`) - for other apps' FCM (Element X) since design 19 - and, **only on a phone bound to a Spotify account** (the policy's `music.spotify`), never Spotify's hosts (`music/SpotifyHosts.kt`; design 26c - the "**Spotify**" bullet of "Vibb music: the bridge, the courier, own files" below).
- **Fix round (qa-step7-code.md)** (its FCM token parts gone with design 19): a failed anchor start gives back its pending count so the wake lock is released; screen-on suspends the Play Store synchronously in the receiver when the enforced plan had the update window open (`PlayRuntime.suspendStoreAtScreenOn`), before the background apply; an SSE reopen's sync follows `syncOnSseReopen` (since qa-19-code #2: every reopen, at most once per 10 min, always after a deaf stream).
- **Not device-tested**: everything in the 07 doc's device checklist (Play updates while suspended/in the window, lock-task finishing Play's task, the link blocker vs verified app links, battery numbers).

## SSE only and SSE hardening (design 19, `docs/design/19-remove-fcm.md`, at the monorepo root)

- **SSE only**: `PushState` keeps only `sseConnected` (memory). There are no `PushPolicy`/`PushReport` DTOs - a server's `push` is an unknown key, and the cache is re-encoded without it (`PolicyResponseCompatTest`). Status capabilities: `server/StatusCapabilities.kt` (`STATUS_CAPABILITIES`, no `fcm_push_v1`; `SyncScheduleTest` checks the report has neither). One APK works with anyone's server; Play services stays installed and unrestricted for other apps' FCM.
- **Build**: the release build needs no Firebase values. `checkReleaseHasNoGoogleServices` (a dependency of `assembleRelease`) walks `releaseRuntimeClasspath` and fails on any `com.google.firebase`, `com.google.android.gms` or `com.google.android.datatransport` module. `PushManifestTest`: no Firebase element, placeholder or `tools:node="remove"` in the manifest.
- **SSE hardening** (`CommandListenerService`, pure parts in `push/SyncSchedule.kt`, `SyncScheduleTest`, `SseLastByteTest`): (hole 1) `push/SseLastByte.kt`'s `LastByteInterceptor` (network interceptor) stamps elapsed realtime for the headers and every body read - okhttp-sse never shows keepalive comments to the listener; its wrapped body keeps `contentType()` (okhttp-sse 4.12 refuses anything but `text/event-stream`). `checkStream` runs on every sync request (the backstop's included) and at screen-on/unlock (`PinLockRuntime`'s receiver, `CommandListenerService.checkStream`): a stream that is down reconnects now (screen events leave an in-flight attempt alone); one silent for `SSE_STALE_MS` (480 s, ~2 keepalives) is marked down first (`markDown`, like any drop) and reconnected. (hole 3) The up -> down edge takes a 30 s partial wake lock (`kidslauncher:sse_reconnect`, at most every 10 min, released at the next open), so the 5 s/10 s retries run in deep sleep after a server restart. (hole 2, qa-19-code #2) `syncOnSseReopen(sinceLastReopenSyncMs, sinceLastByteMs)`: every reopen after a drop syncs - a nudge sent while no stream was subscribed is lost (a ring right after a server restart) - at most once per 10 min (`SSE_REOPEN_SYNC_EVERY_MS`, so a flapping stream can't loop), and always when the old stream's last byte is >= 300 s ago (every read timeout and staleness drop).
- **Not device-tested**: the 19 doc's Jelly Star run - a ring's latency in Doze, after a server restart and after a Wi-Fi/mobile switch (tsnet gets no network callbacks), and battery at keepalive 120 s vs 240 s; the emulator check is `docs/testing/emulator.md` §5b (`kill -STOP` the server > 300 s).

## The policy's 304 and one shared client (design 95 step 95-T1, `docs/design/95-connectivity-control.md` §3.5)

- **ETag and 304** (`server/PolicyEtags.kt`, `PolicyEtagsTest`): the sync sends `If-None-Match` with the ETag of the
  kept **raw 200 body** (`PolicyEtagStore`: its own prefs file `policy_etag`, one blob sealed under AAD
  `policy_etag/kept` - it holds the PIN hashes), only while its versionCode, server address and device token's SHA-256
  are the phone's now (`KeptPolicy.matches`; a mismatch, a blob that doesn't open or decode drops it). It is not the
  policy cache: `kidModePolicy` is re-encoded and redacted. `fetchRevalidated` decodes a `304` as the 200 it repeats,
  so `judgeFresh`, the PIN vault, `storeAcceptedPolicy`, design 76's flag (from the 304's own handshake) and everything
  else in the sync's fresh block run exactly as for a 200, and the sync counts as having reached the server. A 304 the
  kept body can't answer drops it and asks once more without the header. Kept only after `ACCEPT`, and never a body
  with a `pending_command` (`keepable`: one-shot, and `set_kid_pin`'s carries the Child PIN, design 59 §1); a rejected
  or undecodable 200 drops it (the pure `keptBodyStep`). An older server sends no ETag: nothing is kept, no header is
  sent. Enrollment, and a sync that finds no URL or token, drop it too.
- **One shared client** (`ServerClients` in `server/ServerTransport.kt`): one base `OkHttpClient` built by
  `toServer(path, pinning)`, kept while the path (base URL, `ServerPath`, tsnet's proxy) and the certificate check's
  flag and pins are the same, built again otherwise (the old pool's idle connections closed). `createMdmApi` (so the
  sync, `PowerEvents`' prepared client, the weather, downloads), `CommandListenerService`'s stream and
  `createMusicServer` are `newBuilder()` children with their own timeouts and interceptors, after the base's (the
  closed path's refusal, the origin fence and the pinner stay first). Behind Caddy's HTTP/2 a sync rides the stream's
  connection; on HTTP/1.1 it resumes TLS. A dropped stream evicts the pool's idle connections
  (`ServerClients.streamDropped`). That can't reach the stream's own busy HTTP/2 connection, which a NAT can drop
  silently (code QA M1), so `retryOnStaleConnection` (on `createMdmApi`'s and the music client's children, last) sends
  a GET or the status report once more when it timed out on a **reused** connection: it evicts the idle connections,
  waits `STALE_RETRY_PAUSE_MS` (OkHttp's degraded ping on the timed-out h2 connection goes unanswered, so the pool
  drops it) and proceeds again. A request that opened its own connection is never retried (a slow server), nor any
  other POST. The shutdown's POST (`PowerEvents`, 3 s in all) has no time for that and gets its own pool
  (`createMdmApi(ownPool = true)`, a fresh connection as before design 95). No `pingInterval` (it would cost line bytes).
  The DNS filter's DoH/DoT clients stay separate builders (`buildDohClient`). `ServerTransportTest` checks the reuse
  and that no server client builds its own.

## The heartbeat report (design 95 step 95-T2, `docs/design/95-connectivity-control.md` §3.5)

- `server/StatusReports.kt` (`StatusReportsTest`, contract `status_heartbeat.json`): while the policy says
  `status_heartbeat` and its `status_heartbeat_fields` are this launcher's `HEARTBEAT_FIELDS` (`heartbeatOn`; another
  list: plain full reports), the sync's report goes through `sendReport`; each heartbeat names the list
  (`fields_tag`, `HEARTBEAT_FIELDS_TAG`). It hashes the report's JSON without
  `HEARTBEAT_FIELDS` (`reportHash`) and sends the full report with that `hash`; a 2xx is kept as the `HeartbeatAck`
  (prefs `status_heartbeat`: the hash, when, and a hash of the server URL and token). While the hash is the acked one
  and the acked full report is under `FULL_REPORT_EVERY_MS` (6 h) old, only `{"heartbeat": true, "hash", <the
  heartbeat fields>}` goes. The server's `{"full_report": true}` sends the full report at once in the same sync; a 4xx
  to a heartbeat drops the ack. A report over 1 KiB is gzip'd while the policy says `status_gzip`. An older server
  says neither: no hash, no heartbeat, no gzip, the ack cleared. Enrollment clears the ack. Claimed as
  `status_heartbeat_v1`.

## The stream's `up` event (design 108)

Since design 108 the stream carries a second event, `up` (app push's queue has messages): `onEvent` hands it to
`push/up/UpRuntime.requestPull`, never to `SyncRunner`; every reopen pulls the queue once too. See
`docs/notes/launcher/unified-push.md`.

## A notification channel turned off asks for a sync (design 107 §3)

- `NotificationChannelsWatch.init` (from `Application.initRest`'s tail, once per process) registers one runtime receiver
  (`RECEIVER_EXPORTED`) for `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED`,
  `ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED` and `ACTION_APP_BLOCK_STATE_CHANGED` (Android sends them only
  to the owning package, when a user's change crosses NONE). **Exported on purpose** (gate §6ax): a change from the
  long-press sheet is sent with SystemUI's uid, and Android drops it at enqueue for a not-exported receiver ("Exported
  Denial", in `dumpsys activity broadcasts history`); only Settings' own page (the system uid) reached one. The actions
  are protected broadcasts, and the receiver only asks for a sync - and Snarveier's refresh: `ShortcutsRuntime.init`'s
  receiver is not exported (its other actions) and no longer lists the channel action, so this watch calls
  `ShortcutsRuntime.requestRefresh()` before the sync (code QA L1).
- The app-wide switch turned off revokes POST_NOTIFICATIONS, and Android kills the process for it (exit reason
  `PERMISSION CHANGE`); no `ACTION_APP_BLOCK_STATE_CHANGED` was seen, and the process start's sync carries the report
  (§6ax). Each asks `SyncRunner.request(app,
  "notification_channels")`; `SyncRunner` coalesces the burst of one Apply. The receiver reads no extra (L5: a refused
  change still sends the broadcast); the report's own read is the truth. A missed broadcast waits for the next sync
  (the backstop, an SSE nudge, a process start).
- Two of our own changes send no broadcast (an app's own delete and re-create, design 100 M6), so they ask for the sync
  themselves: Snarveier's "Vis i varsellisten igjen" (`ShortcutsRuntime.showAgain`) and the Settings row's return from
  Android's page when the read changed.
