# Step 7: FCM nudges, battery hygiene, Play as an app source

PLAN "Battery" (FCM decision, PLAN.md:202-212) and Play (PLAN.md:146-155). S = kid-phone-server, L = kids-launcher-mdm,
branch `handy`. Steps 1-6 guarantees unchanged; nothing here lifts a restriction (a nudge only triggers a sync; Play
installs stay suspended/hidden until allowlisted). Claims: [verified: source] or [needs device test].

## What runs today (L)

`CommandListenerService` (always-on FGS, type `dataSync`, AndroidManifest.xml:52-53, :318-321; started at
Application.kt:248 and PackageReplacedReceiver) owns: the SSE stream (CommandListenerService.kt:161-207, OkHttp
`readTimeout(0)` :99, so a silently dead stream is never noticed), the 5-min backstop sync of policy, journal and browser
history (:38, :209-220), the screen on/off/user-present receiver for step 6 screen time and lock re-checks (:115-122,
:223-231), and the optional UnifiedPush relay (:127-129; its own WebSocket pings every 20 s, UnifiedPushRelay.kt:82).
S: `commands_stream` (device_api.rs:786-797) with `KeepAlive` every 15 s (:796) fed by `AppState.command_notify`
(main.rs:40, `broadcast::channel(64)` main.rs:110). Senders: locate.rs:213,336; schedules.rs:367,372;
devices.rs:824,1080,1117,1164; calls.rs:341,617,755; lifts.rs:122,153.
Two latent problems: the 5-min timer is `Handler.postDelayed`, i.e. `uptimeMillis`, which stops in deep sleep
[verified: SystemClock docs]; it only fires because the 15 s keepalive keeps waking the phone. And `dataSync` FGS are
capped at 6 h/24 h for apps targeting 35+ on Android 15+ (we target 36) [verified: developer.android.com
fgs/timeout], so the current always-on service would be killed on an Android 15 phone. Which Android the Jelly Star
runs: [needs device test] (`getprop ro.build.version.release`).

## 1. FCM nudge transport

- **Message**: data-only, constant `{"k":"sync"}`, `android.priority: HIGH`, `ttl: "600s"`, no `collapse_key`
  (collapsible: burst 20, refill 1/3 min; any: 240/min, 5,000/h per device [verified: FCM throttling-and-quotas];
  100 stored uncollapsed [verified: FCM collapsible-message-types]). Forged/replayed push = one extra sync.
- **L build config**: no `google-services` plugin or json. `app/build.gradle.kts` reads `handy.fcm.projectId`,
  `.applicationId`, `.apiKey`, `.senderId` (env `HANDY_FCM_*` first, like `releaseSecret()`), into `BuildConfig`
  fields; empty -> FCM off, SSE only (upstream/local builds unchanged). `-PrequireFcm=true` in the tag release job of
  `.github/workflows/android.yml` (like `requireTsnet`), values from CI secrets. Firebase console: Android apps
  `me.vibb.launcher` (+ `.debug`; `com.kidslauncher.mdm` until 2026-10-06), API key restricted to that package + release SHA-1 and to the FCM/Installations
  APIs. Manifest: remove `com.google.firebase.provider.FirebaseInitProvider` (`tools:node="remove"`),
  `firebase_messaging_auto_init_enabled=false`, analytics deactivated. `FirebaseApp.initializeApp(ctx, options)` only
  from `initUnlocked()` (Application.kt:140), never before the first unlock (Firebase keeps its state in CE prefs).
  Dependency: `com.google.firebase:firebase-messaging` only. Check the DNS log for `firelog`/`app-measurement`
  traffic and block it there if the SDK sends delivery metrics [needs device test].
- **`KidFcmService : FirebaseMessagingService`** (exported per SDK, not directBootAware): `onMessageReceived` checks
  `priority == PRIORITY_HIGH` and records `priority`/`originalPriority` (a downgrade is how deprioritisation shows -
  FCM deprioritises high-priority messages that don't lead to a user-visible notification [verified: FCM
  message-priority page]; a content-free nudge never shows one), then starts `SyncRunService`. `onNewToken` stores the
  token (CE prefs) and starts `SyncRunService` so the next status report carries it.
- **`SyncRunService`** (new, FGS `dataSync`, MIN-importance notification, stops itself when the three syncs finish):
  runs `performMdmSync` + journal + browser history (the code now at CommandListenerService.kt:187-193) outside the
  ~10 s `onMessageReceived` window; a sync can download APKs and reconnect tsnet. A device owner may start FGS from the
  background [verified: developer.android.com fgs/restrictions-bg-start, "device owners"]; high-priority FCM is an
  exemption too, a downgraded one is not (same page). Daily `dataSync` use stays minutes, far below 6 h.
- **Token reporting**: status report gets `push: {fcm_token|null, transport: "fcm"|"sse", last_nudge_ms,
  last_priority}` and capability `fcm_push_v1` (next to MdmSyncWorker.kt:192). Every sync reports the token, so S has a
  fresh timestamp; FCM expires registrations inactive for 270 days and calls a token stale after a month
  [verified: FCM manage-tokens]. L calls `getToken()` again at most daily if the policy says the token is unknown.
- **Transport choice (L, pure `decidePushTransport`)**: FCM only if BuildConfig has config, `com.google.android.gms`
  is installed and enabled, a token exists, and the policy's `push.fcm_ok` is true for that token; anything else ->
  SSE. Doubt always lands on SSE (more battery, never less reach).
- **S sender** (`src/fcm.rs`, new): `.env` `FCM_SERVICE_ACCOUNT_FILE` (path to the JSON, mode 600, not in backups'
  web-readable paths); missing -> module off, policy sends `fcm_ok: false`, SSE as today. OAuth2: self-signed RS256
  JWT (`iss`=client_email, `scope`=`https://www.googleapis.com/auth/firebase.messaging`, `aud`=token_uri, 1 h) posted
  to `token_uri` with `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`; access token cached in a
  `tokio::sync::Mutex` until 5 min before `expires_in`. Send: `POST https://fcm.googleapis.com/v1/projects/{id}/
  messages:send` [verified: FCM v1 send docs]. **No new crates**: `reqwest` 0.13 + `serde_json` are direct deps;
  `ring` 0.17.14 (`RsaKeyPair::from_pkcs8`, `RSA_PKCS1_SHA256`) and `base64` 0.22.1 are already in Cargo.lock and
  become direct deps (PEM = strip armour + base64). The base URLs are fields so tests point them at a local axum app.
- **Fan-out without touching the 13 call sites**: one spawned task subscribes to `command_notify` and coalesces per
  device for 1 s (global edits nudge every device). `RecvError::Lagged` -> nudge every device with a token. SSE keeps
  receiving the same broadcast, so a phone on either transport is reached.
- **Failures** (`classify_fcm_error`, unit-tested): 404 `UNREGISTERED`, 400 `INVALID_ARGUMENT` on the token, 403
  `SENDER_ID_MISMATCH` -> clear `fcm_token`, `fcm_ok=false` (phone falls back to SSE, re-registers), security log once.
  401 -> drop cached OAuth token, retry once. 429 `QUOTA_EXCEEDED`, 500 `INTERNAL`, 503 `UNAVAILABLE` -> backoff
  (honour `Retry-After`, 3 tries within 2 min), then give up: the backstop sync covers it. Token endpoint failing ->
  warn at most hourly, device page shows "push: server can't reach FCM".
- **Health check**: sends succeed but no nudge reported for 2 backstop periods, or `last_priority` shows downgrades ->
  `fcm_ok=false` (SSE) for that device, retried after 24 h. Migration `0026_push.sql`: `fcm_token`, `_updated_at`,
  `push_transport`, `last_nudge_at`, `fcm_ok`, `last_fcm_error`; device page warns like step 6.
- **Platform claims**: FCM needs Android 6+ with the Play Store app installed (so Play must never be hidden, see 4);
  the current docs list no Google-account requirement [verified: FCM Android client setup page] - that it works on a
  phone with no account: [needs device test]. High priority wakes a dozing device for limited processing [verified:
  FCM message-priority]; latency after hours screen-off: [needs device test].

## 2. What happens to `CommandListenerService`

- **SSE + its reconnect loop**: only while the transport is SSE. With FCM: no stream, no keepalive traffic.
- **Backstop sync**: leaves the service. `BackstopAlarm`: `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` every 30 min
  (15 min while the transport is SSE but the stream is down), own PendingIntent request code (not TimeRuleAlarm's),
  receiver starts `SyncRunService`; re-armed after every sync, at process start after unlock, `BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED`. Location `interval` mode: next = min(backstop, time to next fix due) so step 6's policy keeps
  its cadence (MdmSyncWorker.kt:172-177). Not WorkManager: it was removed in v0.8.0 after hours-long overnight gaps
  (L CLAUDE.md:242) and jobs only run in Doze maintenance windows; while-idle alarms fire in Doze (at most ~1 per
  9 min per app) [verified: developer.android.com Doze/alarms]. Network inside the alarm's temp allowlist through
  tsnet: [needs device test].
- **Screen receiver (step 6)**: stays, needs a live process; screen time and the user-present re-check must not depend on
  the HOME process surviving. So the service stays as a **quiet process anchor**: no network, no timers, type changed to
  `specialUse` (no 6 h cap; subtype "parental control enforcement"), same MIN notification. An idle FGS costs no
  wakeups. KidVpnService (also `specialUse`) keeps the process up today but is optional and may go (PLAN Tailscale).
- **UnifiedPush relay**: stays in the anchor, opt-in, off by default (PLAN.md:212); its 20 s ping costs if turned on.
  (Removed in the 2026-10-06 cleanup, with the journal and browser-history syncs; use the ntfy app if ever needed.)
- **One sync at process start** after unlock (today the first tick is 5 min later): catches nudges missed before it.

## 3. Battery hygiene and measurement

- SSE keepalive (fallback only): env `SSE_KEEPALIVE_SECS`, default 120 (device_api.rs:796); client `readTimeout` 300 s.
  The stream runs inside tsnet's WireGuard, which keeps its own NAT mapping [needs device test: 2 h idle on mobile].
- Backstop 5 min -> 30 min (PLAN 15-30). Schedule polling: already gone (step 6, one exact alarm,
  CommandListenerService.kt:72-76, TimeRuleAlarm.kt:40-42) - confirmed. Location: already policy-driven (step 6,
  `locationAction`, MdmSyncWorker.kt:172-177; default `on_request`) - confirmed.
- **tsnet idle cost** is the unknown: the embedded tailnet (TsnetClient) keeps DERP/control connections in our
  process. Measure first; if it dominates, a later task connects tsnet per sync (risky: native crash history).
- **Protocol (Jelly Star, release build, SIM in, Wi-Fi on; same with Wi-Fi off)**: switch "Block USB debugging" off in
  the PWA; charge to 100 %; `adb shell dumpsys batterystats --reset`; unplug; screen off 8 h overnight untouched; plug
  in, at once `dumpsys batterystats > bs.txt` and `--checkin`, `adb bugreport` (Historian), `dumpsys alarm`/`deviceidle`.
  Read: drop in %, "Mobile radio active" time and count, wakeup reasons, our UID's wakelocks/alarms/network, Doze
  state transitions. Run before (current build) and after (FCM), twice each. Target: total idle drain <= 1 %/h on
  Wi-Fi and <= 1.5 %/h on mobile, our UID's wakeups <= 4/h, and a clear drop vs. before. Switch debugging back on.

## 4. Play as an app source

- **Never hidden or suspended**: `PLAY_CORE = {com.android.vending, com.google.android.gms, com.google.android.gsf}`
  joins `neverRestrict` (EnforcementPlan.kt:124), so also not by the schedule lock (:148), and is exempt in
  `shouldSuspendNewPackage` (PolicyGate.kt:235). Today the Play Store has a launcher icon, so `controllablePackages`
  (AppEnforcer.kt:63-92) makes it controllable and a managed allowlist hides it (EnforcementPlan.kt:143) - which also
  breaks FCM's "Play Store installed" requirement. Suspending Play may be refused anyway when it is the package
  verifier [needs device test]. Play Games (`com.google.android.play.games`) stays a normal controllable app.
- **Not launchable**: `PLAY_CORE` is removed from `kioskPackages` (EnforcementPlan.kt:150-163) even if allowlisted,
  filtered out of Home/drawer in `AppFilter` (AppFilter.kt:18-33; unsuspended apps are shown today), and S hides them
  from the allowlist UI. Exception: install mode.
- **Install mode** (L, pure `InstallMode` like OfflineOverride's timed window): Launcher Settings (already PIN-gated)
  -> "Install from Play" (15 min, same PIN + lockout). Adds `PLAY_CORE` to `kioskPackages` and Home, lifts the link
  blocker, opens Play. Ends on its exact alarm, "End now" or a reboot; then `apply()` re-pins without
  Play (AOSP finishes tasks no longer allowed [needs device test]) and brings Home to front. Hardening, call rules and
  time rules stay. Installed apps are suspended + hidden by `enforceOnNewPackage` (AppEnforcer.kt:729) until the parent
  allowlists them in the PWA. Reported in status (`install_mode: {until_ms}`), security log on S.
- **Other paths to Play**: `market://` and `https://play.google.com/store/*` links -> `addPersistentPreferredActivity`
  to our `PlayLinkBlockedActivity` (cleared during install mode; whether it beats Play's verified app links: [needs
  device test]); in kiosk, Play activities started in a new task are blocked anyway. In-app review, in-app update and
  billing sheets run inside the calling app's task and can't be blocked without suspending Play: billing -> the account
  has no payment method and "require authentication for all purchases"; reviews -> account named neutrally (they are
  public under it); in-app updates of allowed apps are harmless. Play Games sign-in sheets: gms overlay, profile only.
  Play Protect may warn about our launcher: [needs device test].
- **Runbook (Google account)**. Device-owner provisioning requires an account-free phone, so the account is always added
  after enrolling (PLAN's "before enrolling" isn't possible): 1) create the account on a computer (adult, no payment
  method); 2) PWA: switch "Block account changes" off (Hardening.kt:20 `MODIFY_ACCOUNTS`), sync; 3) phone: install mode,
  sign in from Play (if gms' sign-in screens are blocked by lock task: PIN pause, Settings -> Accounts) [needs device
  test]; 4) Play settings: auto-update apps over Wi-Fi, purchase authentication "all purchases", parental controls
  with a PIN, Play Protect on (updates while not launchable: [needs device test]); 5) end install mode, "Block
  account changes" on again, `dumpsys user` shows `no_modify_accounts`, account kept; re-auth after a password change: [needs device test].
- **One source per package**: `InstalledApp` (dto/InstalledApp.kt:12) gains `installer`
  (`getInstallSourceInfo().installingPackageName`, MdmSyncWorker.kt:480-501). S refuses to scope a catalog app to a
  device that reports it from `com.android.vending`; L skips a catalog update whose installed copy comes from Play
  (other signature; Android 14 update ownership) and reports it. Switching source = uninstall first.

## 5. Safety

FCM, SSE and the alarm only call `performMdmSync`, which keeps last good policy on any error (step 1). FCM absent,
broken or downgraded -> SSE + backstop: staler policy, never fewer restrictions; time rules run locally. Install mode
is PIN-gated, time-limited, ends on reboot and lifts nothing but Play's kiosk exclusion. Emergency calls untouched: no
change to `restrictOutgoingCalls`, the system dialer or the lock screen. Direct boot unchanged: `KidFcmService`,
`SyncRunService`, `BackstopAlarm` and the anchor are not directBootAware, Firebase init waits for the unlock, the call
path still reads its DE copy; a push before the first unlock waits in gms and the unlock sync catches up.

## 6. Tasks (in order)

1. S local: `fcm.rs` (JWT, token cache, send, `classify_fcm_error`), dispatcher on `command_notify` + debounce,
   migration 0026, status `push`/`install_mode`/`installer`, policy `push.fcm_ok`, keepalive env, device page push
   state, catalog one-source check, `PLAY_CORE` hidden from allowlist UI. Tests: JWT with a fixture key, error
   classes, debounce (paused tokio time), lag fan-out, fake FCM server (send, 404 clears token, 429 retry), snapshots.
2. L local: `decidePushTransport`, backstop delay, `InstallMode` window, plan with `PLAY_CORE` (never suspend/hide,
   kiosk only in install mode), AppFilter exclusion, link blocker, status DTO snapshot - all JVM tests.
3. L local: BuildConfig/Firebase wiring, `KidFcmService`, `SyncRunService`, `BackstopAlarm`, anchor service
   (`specialUse`, no SSE when FCM), SSE `readTimeout`, CI secrets; `assembleRelease` with and without FCM config.
4. Device only: batterystats baseline on the current build first; after 1-3, the checklist, then "after" measurement.

## Device checklist (Jelly Star, release; plus 02/04/06 checklists)

1. No Google account: token obtained, device page shows push FCM; PWA policy change applies within seconds, screen on.
2. `dumpsys deviceidle force-idle`, queue `ring`: rings within ~10 s; log shows `priority` HIGH, `originalPriority`.
3. Build without FCM config / gms disabled (test phone): SSE takes over. 4. Invalid token: S clears it, phone -> SSE.
5. Overnight: backstop syncs ~every 30 min in `dumpsys alarm` history; no SSE traffic in FCM mode.
6. Play: with a managed allowlist, Play not hidden/suspended, not on Home, not in kiosk; `market://details?id=...`
   from an allowed app shows the blocker; install mode opens Play, ends after 15 min and on reboot, the new app stays
   suspended+hidden until allowlisted; Play auto-updates an allowed Play app while not launchable.
7. Account runbook steps 1-5; purchase in an allowed app asks for the password; 112 test mode from the lock screen
   during install mode and with FCM on.

## Decisions after QA review (qa-07-design.md), 2026-10-05

QA findings override this doc where they conflict. Binding:

- **Play reachable inside an allowed app's task (QA #1):** the Play Store package
  (`com.android.vending`) is SUSPENDED (not hidden) at all times except (a) parent install mode
  and (b) a nightly update window (default 02:00–04:00, only while the screen is off, ended at
  once on screen-on). Play services and Google Services Framework are never suspended or hidden
  (FCM). Any newly installed app stays hidden/unlaunchable until the parent allowlists it, and
  new installs are reported to the server. [needs device test: Play updates apps during the
  window; FCM still delivered while Play Store is suspended.]
- **Never lift the link blocker** and never clear preferred activities (keeps the HOME pin).
  Install mode pins only the Play Store app.
- **Runbook order:** factory reset → `dpm set-device-owner` → add the Google account and set
  Play options (auto-update, purchase authentication, no payment method) in the normal UI →
  enroll on the server (first policy blocks account changes and starts the kiosk).
- **Syncs run inside the existing foreground service** with a wakelock and timeouts; no separate
  dataSync service; Firebase initialised when the service starts; the FCM service stays
  `exported="false"` as declared by the SDK.
- **FCM health:** defined by a periodic server→device test nudge acknowledged by the next sync;
  fallback to SSE when acks stop; ring/lock/lift nudges are also sent over SSE while FCM health
  is unproven. The DNS filter always allows the FCM hosts; server FCM calls have timeouts; the
  service-account key is FCM-only and stored outside the backed-up data directory.
- Battery is measured per change (tsnet, SSE keepalive, FCM) rather than one before/after.

## FCM setup (for the parent/admin)

Optional: without it everything works over the SSE stream, at a higher battery cost. Nothing
secret is ever committed - no `google-services.json`, no key.

1. **Firebase project**: in the Firebase console create a project used for nothing else (no
   Analytics). Add two Android apps: `me.vibb.launcher` (release) and, only if you test FCM
   with debug builds, `me.vibb.launcher.debug`. (Before 2026-10-06 the package was
   `com.kidslauncher.mdm`: a Firebase app registered under that name doesn't match the renamed
   launcher - add the new package names, put the new apps' "App ID"s into
   `HANDY_FCM_APPLICATION_ID`/`HANDY_FCM_DEBUG_APPLICATION_ID` and restrict the API key to them.) Skip the google-services.json download
   step - we don't use the file, only four values from it (or from Project settings -> General):
   project id, the app's "App ID" (`1:<number>:android:<hex>`), the Web API key, and the
   sender id ("Project number").
2. **Restrict the API key** (Google Cloud console -> APIs & Services -> Credentials): Android
   apps only, package `me.vibb.launcher` + the release certificate SHA-1 (and the `.debug`
   package + debug SHA-1 if added); API restrictions: Firebase Cloud Messaging API, Firebase
   Installations API.
3. **Launcher build** (values are not secrets - they end up in the APK - but they stay out of
   the repo): GitHub repository *variables* `HANDY_FCM_PROJECT_ID`, `HANDY_FCM_APPLICATION_ID`,
   `HANDY_FCM_API_KEY`, `HANDY_FCM_SENDER_ID`. The tag release job builds with
   `-PrequireFcm=true` and fails if one is missing. Local builds: the same names as Gradle
   properties in `~/.gradle/gradle.properties` (`handy.fcm.projectId`, `.applicationId`,
   `.apiKey`, `.senderId`, and `.debugApplicationId` for debug builds) or as env vars.
   Without them the build has FCM off.
4. **Server key**: Google Cloud IAM for that project -> new service account with **only** the
   role "Firebase Cloud Messaging API Admin" (`roles/firebasecloudmessaging.admin`), not the
   Firebase Admin SDK default account -> create a JSON key. On the Pi, outside the backed-up
   data directory: `sudo install -d -m 750 -o kidphone -g kidphone /etc/kid-phone-server` and
   `sudo install -m 600 -o kidphone -g kidphone key.json /etc/kid-phone-server/fcm-service-account.json`,
   delete every other copy, set `FCM_SERVICE_ACCOUNT_FILE=/etc/kid-phone-server/fcm-service-account.json`
   in `.env`, restart. The log says "FCM nudges on" or why not (a group/world-readable key or
   one inside `data/` is refused). Rotation/leak runbook: kid-phone-server `DEPLOY.md`,
   "FCM (optional)".
5. **Check**: device page -> "Push and Play": after the phone's next sync and the first test
   nudge it shows "FCM confirmed working"; until then the phone stays on SSE.

## Implementation status (2026-10-05)

Implemented on branch `handy` in both repos, local tests green; nothing device-tested yet.

- **S** (`kid-phone-server`): `src/fcm.rs` (service account, RS256 JWT via `ring`, OAuth token
  cache, v1 send with 10 s timeouts, `classify_fcm_error`, Retry-After, redacting Debug; key
  refused if group/world-readable or inside `data/`), `src/push.rs` (dispatcher on
  `command_notify` with 1 s per-device coalescing, `Lagged` -> every token, bounded spawned
  sends, supervisor restart; pure health logic: a send is acked when a status report echoes its
  nonce, 2 sends unacked after 60 s -> `fcm_ok=false`; test nudge at once for a new token, then
  every 6 h while ok / 1 h while not), migration `0026_push.sql`, policy `push`, status
  `push`/`install_mode`/`play_window_active`/`installer`, device page "Push and Play" card,
  `SSE_KEEPALIVE_SECS` (default 120) and the `Lagged` fix in `commands_stream`, PLAY_CORE
  hidden from the allowlist UI and never added, catalog one-source check (409), security log
  (install mode, new installs, dead tokens), the exact FCM hosts never in the delivered blocklist.
  Tests via TestApp with a fake sender and a local fake FCM/OAuth server.
- **L** (`kids-launcher-mdm`): pure `push/PushTransport.kt`, `push/SyncSchedule.kt`,
  `play/PlayPolicy.kt` (+ `computeEnforcementPlan(playState)`), JVM-tested; `FcmSupport`/
  `KidFcmService` (manual Firebase init, exported=false, SDK provider and DBA fallback service
  removed), `CommandListenerService` as `specialUse` anchor with SSE only while FCM isn't
  proven, `SyncRunner` (wake lock, timeouts, coalescing), `BackstopAlarm` (30/15 min,
  while-idle, elapsed realtime), install mode (Settings, PIN, 15 min, notification "End now"),
  `PlayLinkBlockedActivity` (never lifted, forwards when Play is open), Play window via the
  boundary alarm + screen events, `installer` + catalog skip, FCM hosts never blocked on-device,
  CI `-PrequireFcm=true` with repository variables. `assembleRelease` checked with and without
  an FCM config.
- **Fix round after `qa-step7-code.md`** (S `9d0be9f`..`58d9997`, L `1c60ccf`, `6204c75`): S exempts
  only the exact FCM hosts (parents like `google.com` are delivered as set; L exempts the exact
  hosts before its suffix walk); a token FCM rejected is remembered by hash (migration `0027`) and
  its re-reports are ignored - no re-store, test nudge or security event - while L renews a
  token at once when the server stops naming one it had confirmed; a token belongs to one
  device (cleared from other rows, unique index), only enrolled devices are nudged;
  `SSE_KEEPALIVE_SECS` is capped at 240 (L read timeout 300 s); an SSE reopen syncs only after a
  gap of >= 150 s; DNS changes nudge the affected devices (hourly blocklist refreshes and new
  catalog releases still wait for the 30-min backstop); L syncs on a nudge even when Firebase
  can't initialise, gives back its wake lock when the anchor can't start, and suspends the Play
  Store synchronously at screen-on when the update window was open.
- **Deviations from the text above** (the binding decisions win): no `SyncRunService`; the Play
  Store is suspended (not only kept out of kiosk) outside install mode / the window; the link
  blocker is never lifted; install mode pins only the Play Store, is refused during a time lock
  and ends when one begins; FCM health is the ack-based check, not "no nudge for 2 periods".
  Worst case with a silent FCM failure: ring/lock/lifts wait for the next backstop sync
  (<= 30 min); the switch to SSE follows after 2 unacked sends plus one sync.

## Device checklist (implementation, Jelly Star release build + emulator with Play image)

Run after the 02/04/06 checklists still pass. `adb logcat -s SyncRunner KidFcmService FcmSupport
CommandListenerService BackstopAlarm PlayRuntime AppEnforcer` shows what happens.

1. Build **without** FCM config: phone on SSE (`reason: no_config` on the card), ring/lock
   within seconds; `dumpsys alarm | grep BACKSTOP` shows the backstop; screen off overnight ->
   syncs every ~30 min (15 while the stream is down).
2. Build **with** config, no Google account: token obtained, card goes "unproven" -> "confirmed"
   after the first test nudge; then no SSE connection (`dumpsys connectivity`/server log), a
   policy change still applies within seconds.
3. `adb shell dumpsys deviceidle force-idle`, queue `ring`: rings within ~10 s; log shows
   priority high (or the downgrade); sync completes under the wake lock.
4. Block `mtalk.google.com` at the router (or disable Play services on a test phone): within
   2 unacked sends + one backstop the card shows SSE and ring works again.
5. Invalid token (server log 404 UNREGISTERED): token cleared, phone on SSE, renews within a day.
6. Android 15+: reboot -> no ForegroundServiceStartNotAllowedException; the anchor runs after
   unlock; `am get-standby-bucket me.vibb.launcher` = EXEMPTED (5).
7. Play with a managed allowlist: Play Store not hidden, **suspended**, not on Home/drawer, not in
   kiosk; Play services/GSF neither. From an allowed app: explicit `setPackage(com.android.vending)`
   and component intents, with and without NEW_TASK, a tapped Play notification, a
   `market://details?id=...` link and a `https://play.google.com/store/apps/details?id=...`
   link: Play UI never usable (suspended dialog or our blocker).
8. Install mode: Settings -> Install from Play (PIN) opens Play, only Play pinned (`dumpsys
   activity activities | grep -A5 LockTaskController`); install an app -> it is hidden and
   suspended at once and shows on the device page; "End now", the 15-min expiry and a reboot each
   end it with Home in front and Play suspended; HOME pin and link blocker still in place
   (`dumpsys package preferred-activities`); refused during a school rule; 112 from the lock
   screen during install mode.
9. Nightly window: at 02:00 with the screen off Play is unsuspended (`dumpsys package
   com.android.vending | grep suspended`), turning the screen on suspends it before unlock;
   an allowed Play app with a pending update gets updated overnight.
10. Account runbook (decisions after QA review): factory reset -> `dpm set-device-owner` -> add
    the Google account and set Play options (auto-update over Wi-Fi, authentication for all
    purchases, no payment method, parental controls PIN) in the normal UI -> enroll; then
    `dumpsys user` shows `no_modify_accounts`, the account is kept, a purchase asks for the
    password, FCM works with the account.
11. Play Protect: no warning about the launcher; catalog app installed from Play is refused on
    the server (409) and skipped on the phone.
12. Battery, per change (QA #15): A/B on the new build - FCM vs forced SSE (build without
    config), with and without tsnet - 8 h screen-off each, same place/SIM/signal; read kernel
    wakeup reasons and mobile radio time, not only per-UID wakeups.
