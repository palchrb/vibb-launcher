# Launcher notes: push, battery, Play, FCM removed

Moved from launcher/CLAUDE.md on 2026-11-01.

## Push, battery and Play (handy step 7, design `docs/design/07-battery-fcm-play.md`, at the monorepo root; FCM removed in design 19)

- **Anchor** (`CommandListenerService`, `specialUse` with subtype - `dataSync` is capped at 6 h/day and can't start from BOOT_COMPLETED on Android 15): all background syncs and the SSE stream, held whenever it runs (since design 19; read timeout 300 s vs. the server's keepalive, 240 s by default). It syncs once at start and arms the backstop. `SyncRunner.request` takes a timed partial wake lock, then `startForegroundService(ACTION_SYNC)` (also revives a dead anchor - device owners may start FGS from the background); runs coalesce (`SyncCoalescer`: a burst = one extra run), 10 min/5 min timeouts (APK downloads run inside; `CancellationException` is rethrown in the update loop), 11 min wake lock cap, released only when no run and no undelivered request is left. Every launched `apply()` is wrapped - an exception reaching the crash handler would end the call path. A re-check re-applies until the enforced Play key equals the current one (screen on right after screen off).
- **Notifications in the shade** (PLAN "Fewer persistent notifications", 2026-10-21, branch `lfix-notifs`, code QA
  `qa-lfix-notifs-code.md`): the anchor's is the phone's one standing notification - "Vibb One"
  (`notification_listener_title`, en/nb), no body, MIN channel `launcher:command_listener`, id 1002; NMS shows a
  foreground service's at LOW at least, so its status-bar icon stays while the anchor runs (Android has no hidden FGS).
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
  `timerules/TimeLeftNotice.kt` (`timeLeftNotice(budget, windowOpen, request)`, `leftMinutes` rounded up;
  `TimeLeftNoticeTest`); glue `server/AnchorNotice.kt` (`build` - silent, ongoing, no time, only-alert-once; the action
  `NotificationCompat.Action.Builder(..).setAuthenticationRequired(true)` to the explicit, non-exported
  `timerules/TimeAskReceiver`; "Spurt kl. 14:02" (`anchor_asked_at`) is the content text, never a button, and there is
  no action while a request is pending, taken or capped - R13-8). `refresh` posts `notify(1002, ..)` only while the
  anchor runs (`CommandListenerService.isRunning`) and only a notice or locale other than the last posted
  (`lastPosted`, reset in the anchor's `onCreate`). `onCreate`: `startForeground` with `AnchorNotice.plain` literally
  first, then the reset and `refreshNow` (CE prefs only after it). Refreshed from every `TimeRulesRuntime.recheck` with
  its snapshot (counting or not, R13-3), from `ScreenTimeTracker.update`'s counting branch (the checkpoint's delay is
  then `checkpointDelayMs(remaining)` - the next minute flip + 200 ms; else `CHECKPOINT_MS`), the tap, the server's
  answer and the pause/override start (`AnchorRefresh.request`). **The request** (`timerules/TimeRequests.kt`,
  `TimeRequestsTest`): DE prefs `time_request` (`record_v1`: `TimeRequestRecord(id 32 hex, askedAtMs, openUntilMs
  +30 min, day = the ledger's, liftIdAtAsk, PENDING|ASKED)`, `count_v1`: `TimeRequestCount(day, accepted)` - the
  phone's own three a day, D5), one `commit()` under its lock; pure `requestState` (an open record of today first -
  Pending/Asked -, then Capped at three, else Idle: another day, its 30 minutes over, or a lift newer than the one at
  the ask), `askStep` (enrolled, a budget today, outside the pause, Idle), `timeRequestAnswer` (§2.4's table: accepted,
  capped - the count set to three -, stale/other 4xx/unparsable dropped, 404 dropped silently, 401/408/429/5xx/none
  kept and retried until `openUntilMs`) and `afterTimeRequestAnswer`. `TimeRequests.upload` runs in `performMdmSync`
  right after `PowerEvents.upload` and posts only what the pure `uploadStep` says is still open (a request a lift answered
  while it waited, another day's or an expired one is dropped unsent, Q-3); the answer writes only the record it sent
  (`recordAfterUpload`); the tap asks `SyncRunner.request(.., "time_request")` and arms `TimeRuleAlarm` (Q-4). The
  tracker's minute tick refreshes from the budget it computed (`AnchorNotice.refresh(context, budget, policy, day, now)`,
  no snapshot, Q-5). The stored `openUntilMs` is
  one more `TimeRulesRuntime.nextBoundary` instant, so "Spurt" ends on time across a process death. Not
  emulator-checked yet: design r13 §2.9.
- **Backstop** (`push/BackstopAlarm.kt`): `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`, request code 7, `backstopDelayMs`: 30 min, 15 while SSE is down (on the up->down edge, and only ever moved earlier then - a flapping stream must not push it out; each reconnect syncs once instead), sooner for an interval location policy, floor 9 min. Its sync request also reconnects a stalled or stale SSE stream (`checkStream`, design 19 below).
- **Play** (pure `play/PlayPolicy.kt`, `PlayPolicyTest`; `play/PlayRuntime.kt`): Play services + GSF are in `neverRestrict` (other apps' FCM - Element X; ours is gone since design 19). The Play Store is never hidden but **suspended** while apps are managed (also during a lock, also if allowlisted) except in install mode and the nightly update window (02:00-04:00, only while the screen is off - screen-on ends it at once, screen-off resumes it); never pinned or shown on Home/drawer (`AppFilter`, `playPackageLaunchable`) except in install mode **and, in the drawer only, while the override or the pause is open (design 28: `PlayRuntime.storeListable` feeds `AppFilter`; Home's grid drops the store outside install mode with `playPackageOnHome` in `refreshApps`); a window's edges reload the app list** (`Application.initRest`'s `WindowRelock.addListener { loadApps() }` - not Play's suspension edge, which never comes on a GMS phone). Override/pause lift it like any app restriction. `shouldSuspendNewPackage` leaves Play packages to `apply()`; a vending (re)install re-applies. `AppEnforcer.lastEnforcedPlayState` + `PlayState.key()` make `reevaluateLockReasonFromCache` re-apply on install-mode start/end and window edges (screen on/off, `TimeRuleAlarm` also wakes for `PlayRuntime.nextEdge`).
- **Install mode**: Settings -> "Install from Play" (Settings gate + the PIN again, shared lockout; refused unless managed, with a PIN, no time lock, Play installed - `canStartInstallMode`). 15 min by wall clock, elapsed realtime and boot count (`timedWindowActive`), CE prefs `play_state`; pins only the Play Store, unsuspends it, opens it; ongoing notification with "End now" (`InstallModeReceiver`). Ends on expiry (boundary alarm), "End now", reboot, or a time lock beginning (`apply` cancels it); then Home is brought to front. Reported as `install_mode.until_ms`.
- **Links**: `PlayLinkBlockedActivity` is the persistent preferred activity for `market://` and `http(s)://play.google.com/store*` (declares the same filters), set on every `apply()` and **cleared only by `HomePin`'s re-seat, which adds every PPA of ours back in the same pass (design 59d step M, `PlayInvariantsTest`)** - any other clear would drop the HOME pin (QA #2). When the Play Store isn't suspended it forwards the link to it explicitly. Explicit/component intents into Play are stopped by the suspension (QA #1).
- **Sources/DNS**: `InstalledApp.installer` (`getInstallSourceInfo`), and since 2026-10-25 `versionCode`/`versionName` (`PackageInfo`, the name trimmed and cut to 60 by `installedVersionName`; both left out when unknown - the server's `app_updated` for every checked catalog app, `SyncScheduleTest`); a catalog update for a package Play installed is skipped and reported as failed. New unallowed installs request a sync (reported at once). The DNS filter never blocks FCM hosts (`push/FcmHosts.kt`) - for other apps' FCM (Element X) since design 19 - and, **only on a phone bound to a Spotify account** (the policy's `music.spotify`), never Spotify's hosts (`music/SpotifyHosts.kt`; design 26c - the "**Spotify**" bullet of "Vibb music: the bridge, the courier, own files" below).
- **Fix round (qa-step7-code.md)** (its FCM token parts gone with design 19): a failed anchor start gives back its pending count so the wake lock is released; screen-on suspends the Play Store synchronously in the receiver when the enforced plan had the update window open (`PlayRuntime.suspendStoreAtScreenOn`), before the background apply; an SSE reopen's sync follows `syncOnSseReopen` (since qa-19-code #2: every reopen, at most once per 10 min, always after a deaf stream).
- **Not device-tested**: everything in the 07 doc's device checklist (Play updates while suspended/in the window, lock-task finishing Play's task, the link blocker vs verified app links, battery numbers).

## FCM removed, SSE hardening (design 19, `docs/design/19-remove-fcm.md`, at the monorepo root)

- **SSE only**: `push/FcmSupport.kt`, `KidFcmService.kt` and `PushTransport.kt` are gone (transport decision, token upkeep, `SseReason`, FID/hash helpers, `fcm_push_v1`); `PushState` keeps only `sseConnected` (memory). DTOs `PushPolicy`/`PushReport` are gone - a 0.19 server's `push` is an unknown key, and the cache is re-encoded without it (`PolicyResponseCompatTest`). Status capabilities: `server/StatusCapabilities.kt` (`STATUS_CAPABILITIES`, no `fcm_push_v1`; `SyncScheduleTest` checks the report has neither). One APK works with anyone's server; Play services stays installed and unrestricted for other apps' FCM.
- **Build**: no `firebase-messaging`, `HANDY_FCM_*`, `requireFcm`, `FCM_*` BuildConfig or `fcmEnabled`; the release build needs no Firebase values. `checkReleaseHasNoGoogleServices` (a dependency of `assembleRelease`) walks `releaseRuntimeClasspath` and fails on any `com.google.firebase`, `com.google.android.gms` or `com.google.android.datatransport` module. `PushManifestTest`: no Firebase element, placeholder or `tools:node="remove"` in the manifest.
- **Leftovers**: design 19's one-shot cleanup of the FCM era's data (`FirebaseCleanup`) ran on every phone and was deleted in round 0b (2026-10-26); a phone may still hold its marker prefs `firebase_cleanup`, which nothing reads.
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
  or undecodable 200 drops it. An older server sends no ETag: nothing is kept, no header is sent.
- **One shared client** (`ServerClients` in `server/ServerTransport.kt`): one base `OkHttpClient` built by
  `toServer(path, pinning)`, kept while the path (base URL, `ServerPath`, tsnet's proxy) and the certificate check's
  flag and pins are the same, built again otherwise (the old pool's idle connections closed). `createMdmApi` (so the
  sync, `PowerEvents`' prepared client, the weather, downloads), `CommandListenerService`'s stream and
  `createMusicServer` are `newBuilder()` children with their own timeouts and interceptors, after the base's (the
  closed path's refusal, the origin fence and the pinner stay first). Behind Caddy's HTTP/2 a sync rides the stream's
  connection; on HTTP/1.1 it resumes TLS. A dropped stream evicts the pool's idle connections
  (`ServerClients.streamDropped`), so a sync never waits on a dead one; no `pingInterval` (it would cost line bytes).
  The DNS filter's DoH/DoT clients stay separate builders (`buildDohClient`). `ServerTransportTest` checks the reuse
  and that no server client builds its own.
