# QA/security code review: step 7 (FCM, backstop, Play). S = kid-phone-server 1c8f393..fc3d9d5, L = kids-launcher-mdm cd93a3c..c30b260

Tests: S `cargo test` green (133 + unit), L `testDebugUnitTest` green. Cross-repo wire names checked and consistent: policy
`push{fcm_enabled,fcm_ok,fcm_token_hash}` (same SHA-256/16-hex on both sides), status `push{fcm_token,transport,
fcm_configured,gms_available,last_nudge_ms,last_nudge_id,last_priority,last_original_priority,reason}`, `install_mode{until_ms}`,
`play_window_active`, `installed_apps[].installer` (L SnakeCase naming, nonce 16 hex vs L regex `[0-9a-f]{1,32}`). Coalescer
(S and L) never drops a ring/lock/wipe/lift: requests during a run collapse into exactly one more run.

## Findings

1. **should-fix (fail-open) - S play.rs:35-55, device_api.rs:454,467.** `is_fcm_protected` also matches every *parent* of an
   FCM host (`google.com`, `googleapis.com`, `apis.google.com`, `clients.google.com`), and the blocklist endpoint silently
   drops those entries. A parent's custom block of `google.com` (or a list carrying `googleapis.com`) is no longer delivered,
   so all of `*.google.com` opens up while the DNS page still shows the rule. L already exempts the exact FCM hosts before the
   suffix walk (DnsFilterEngine.kt:111, FcmHosts.kt), so the parent rule is not needed for FCM. Fix: S filters only exact
   FCM hosts (or nothing) and keeps parents. Optionally the DNS page says "FCM hosts are always allowed".
2. **should-fix - S push.rs:258 + :356-389 (L PushTransport.kt:100-117).** After FCM rejects a token (404/400/403), S clears
   it, but L keeps reporting the dead token until `tokenAction` says RENEW, which needs 24 h since L's last token request.
   Every status report in that time takes the "token changed" path: S stores the dead token again, resets health, spawns a
   test nudge, gets 404 again and writes another `fcm_token_cleared` security event. That means about 50+ log lines and
   sends a day for a token less than a day old. Fix: S remembers the hash of the rejected token per device and ignores
   re-reports of it, and/or L renews at once when the policy's `fcm_token_hash` is null after it once matched ours.
3. **should-fix (battery) [inferred] - S push.rs:392-400, :489-517.** Health ticks and `Batch::All` select every
   `device_push` row with a token. Nothing keeps a token unique across devices: re-enrolling the same phone (new device row,
   same Firebase install, so the same token) leaves the old row test-nudging it, 1 h while not ok. Each nudge wakes the
   phone for a full sync, and the old row's nonces can overwrite the new row's ack. Fix: when storing a token in
   `record_report`, clear it from other rows (or add a UNIQUE index plus that clear), and skip revoked/unenrolled devices.
4. **should-fix - S config.rs:43-44.** `SSE_KEEPALIVE_SECS` accepts up to 3600, but L's read timeout is 300 s
   (SyncSchedule.kt SSE_READ_TIMEOUT_MS). Any value of 300 or more makes every SSE phone drop the stream every 5 min, and
   each reopen runs a full sync (CommandListenerService.kt:218 `sse_open`). That is a sync loop, the opposite of the
   battery goal. Fix: cap at about 240 (comfortably under 300) and document it next to the L constant.
5. **should-fix (battery) [inferred] - L CommandListenerService.kt:209-219.** Every SSE (re)connect after a drop runs a full
   sync (policy, status, journal, history, tsnet). On a network that drops the stream often (NAT/tailnet churn,
   the 300 s timeout), SSE mode turns into a sync per reconnect on top of the 120 s keepalive. Fix: sync on reopen only if the
   stream was down longer than about one keepalive, or at most once per N minutes. Measure it in the A/B battery test (#12).
6. **low - L KidFcmService.kt:213-217.** If `ensureInitialized` fails (init threw, or the user isn't yet unlocked), the
   nudge is dropped with no sync, so a ring/lock waits for the backstop (up to 30 min) while L may still be on FCM. Fix:
   still `SyncRunner.request(..., "fcm_uninit")` (a sync needs no Firebase); just skip `super.handleIntent`.
7. **low - L SyncRunner.kt:44-49, CommandListenerService.kt:291-299.** `request()` increments `pending` before
   `requestSync`, which swallows a failed `startForegroundService`. `pending` then never drops back, so no later run ever
   releases the wake lock early: every sync holds it for the full 11 min (SYNC_WAKELOCK_MS), and that nudge is lost.
   Fix: have `requestSync` return a Boolean and decrement `pending` (and release the lock if idle) on failure.
8. **low [inferred, device test] - L CommandListenerService.kt:253-266 → MdmSyncWorker.kt:647.** Screen-on ends the
   Play window only through an async `AppEnforcer.apply` on `scheduleScope`. It is `@Synchronized`, so it waits behind
   any running sync's apply (all packages). PlayPolicy.kt:58-60 claims Play is suspended "before the keyguard can be
   passed", but nothing guarantees that. The exposure is small (Play is not on Home; links go to the blocker), but an
   explicit Play intent from an allowed app right after unlock at 02:00-04:00 can win the race. Fix: on SCREEN_ON inside
   the window, call `setPackagesSuspended([vending], true)` synchronously in the receiver, then run the full apply.
   The anchor must be alive for this; if it died, the backstop restarts it within 30 min.
9. **note (regression of latency) - S dns_filter.rs:141-370 (set_upstream, toggle/create/delete blocklist and custom
   domain, device override), also settings/tracked-app releases.** These never send on `command_notify`. Before step 7 they
   reached the phone within the 5-min timer; now they wait up to 30 min (backstop). Fix: notify the affected devices (one
   FCM nudge is cheap), or document the 30 min.
10. **nit - S devices.rs:290,338,350,355,383.** The device-page strings were wrapped without a trailing `\`, so the source
    lines carry runs of embedded spaces. They are invisible in HTML but ugly in the source, and tests can't match the text.

## Checked, no finding
- S sender: client `timeout`+`connect_timeout` 10 s cover the body. 401 gets one re-auth, and 429/5xx retries stay within
  3 tries/120 s. Sends are spawned under an 8-permit semaphore and a supervisor restarts the dispatcher. JWT, access token
  and key are redacted. The key is refused if group/world-readable or under canonical `data/`. A token is only logged as
  its hash.
- S health: a late ack resets it, rapid sends are not counted as misses, and a new token gets an immediate test. The
  `commands_stream` Lagged fix sends a nudge instead of dropping.
- L: the backstop is a while-idle ELAPSED alarm that also restarts the anchor and a stalled SSE loop. No Handler timer is
  left in the FCM path. Only the 02:00/04:00 Play edges are added (exact, 2/day). UnifiedPush stays opt-in.
- L Play: vending is never hidden, is suspended outside install mode/window (also during a lock), and is pinned only in
  install mode. gms/gsf are in `neverRestrict`. Install mode is refused during a lock and ended when one begins (apply).
  It uses wall/elapsed/boot clocks, so a clock change or reboot can't stretch it. The link blocker is never cleared,
  and the HOME pin is kept. The update window needs the screen off, so clock/timezone tricks gain nothing.
- L manifest: KidFcmService is exported=false. The SDK's DBA fallback service and FirebaseInitProvider are removed. The
  SDK receiver is not DBA (checked in firebase-messaging 25.1.3). minSdk 34 covers `getInstallSourceInfo`.
