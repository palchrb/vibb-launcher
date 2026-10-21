# Code QA - launcher fix: fewer persistent notifications (branch `lfix-notifs`)

- Commit: `303cc9c8` on branch `lfix-notifs`, on top of main `c5650324` (`git diff c5650324..lfix-notifs`).
- Read:
  - root and launcher `CLAUDE.md`: the VpnService/on-device DNS sections, "Push, battery and Play" with the new
    "Notifications in the shade" bullet, the foreground-service gotcha, direct boot (task 15), the boot-lockout
    incidents, the always-on/lockdown writeup, design 36's protect rule;
  - PLAN.md's "Fewer persistent notifications" entry;
  - the full diff, and around it all of `KidVpnService`, `AppEnforcer.apply`/`applyVpnRestrictions` (`@Synchronized`,
    the override/pause path), `Application.initRest`'s and `PackageReplacedReceiver`'s `start()`, the manifest entry,
    `Notifications.kt`, `VibbOneNameTest`, `TranslationsTest`.
- AOSP, fetched from `aosp-mirror/platform_frameworks_base` (`android14-release`, `android15-release`,
  `android16-release`): `connectivity/Vpn.java`, `VpnManagerService.java`, `am/ActiveServices.java`,
  `am/OomAdjuster.java` (15), `am/ActivityManagerConstants.java` (15), `net/VpnService.java` (14),
  `net/NetworkPolicyManager.java` (16).
- Ran:
  - launcher `testDebugUnitTest -PwarningsAsErrors=true`: **BUILD SUCCESSFUL, 1486 tests, 0 failures, 0 errors, 0
    skipped**;
  - 12 one-change mutations against the tests that read `KidVpnService`/`VpnForeground` (`QuietNotificationsTest`,
    `VpnMeteredInvariantTest`, `ProtectedSocketTest`, `CustomUpstreamPrefTest`, `CustomDnsGuardsTest`,
    `PublicResolverTest`, `BootstrapResolverTest`, `VibbOneNameTest`, `TranslationsTest`,
    `DirectBootComponentsTest`), each reverted. `git status` was clean afterwards.

## Findings

**1 (Medium) - after a dead read loop the filter stays down until the next `apply()`, and the notification that comes
back says it is on.** `KidVpnService.kt:264-273` (`tunnelLost`), `strings.xml:289-290` / nb `:232-233`.
- `tunnelLost` closes the tunnel, nulls `pfd` and takes the foreground state back, but nothing re-establishes. The
  next `establish()` comes only from the next `onStartCommand`: our `start()` in `applyVpnRestrictions`, or the
  system's always-on start that `VpnManagerService.setAlwaysOnVpnPackage` -> `startAlwaysOnVpn` sends in the same
  `apply()` (it skips only while the VPN network is connected - `Vpn.java` android16 `:1126`, and closing our fd makes
  `interfaceRemoved` unbind and disconnect, `:2170-2186`).
- So DNS goes unfiltered over the underlying network until the next sync: an SSE event, or the backstop (30 min, 15
  while SSE is down; later in deep Doze). Before this branch the filter stayed down for good (`pfd` never went back
  to null), so this isn't a regression. But the round says it self-heals, and the delay has no bound the service
  controls.
- The notification that comes back is "Content filter active" / "Innholdsfilteret er på - Blokkerer reklame..."
  while nothing is filtered. It's the only visible sign, and it says the opposite of the truth.
- Fix: a bounded retry inside the service.
  - In `tunnelLost`, after `settleForeground()`, post `main.postDelayed(::retryTunnel, retryMs)`, with `retryMs`
    doubling from 5 s up to a 5 min cap.
  - `retryTunnel()`: `if (stopped || pfd != null) return; startVpn(); settleForeground(); if (pfd == null)`
    reschedule with the next backoff.
  - Reset the backoff once a tunnel has lived for 10 min (`SystemClock.elapsedRealtime()` stamped in `startVpn`).
  - That can't loop tightly: at worst one `establish()` every 5 min. `stopSelfCleanly`'s
    `main.removeCallbacksAndMessages(null)` already cancels it.
  - Optional: while `foreground` is held with `pfd == null`, use a neutral title ("Vibb One-filter starter" / "Vibb One
    filter starting") rather than the "active" one.

**2 (Low) - a `startForegroundService` can still race the drop, and a stop in that window crashes the launcher once.**
`KidVpnService.kt:410-421` (`start`), `:123-130` (`settleForeground`).
- `start()` reads `runningWithoutForeground` on the `apply()` thread. If it reads `false` just before the main thread
  drops the foreground state, AMS's `sendServiceArgsLocked` sees `!r.isForeground` and keeps `fgRequired` with a
  timeout (`ActiveServices.java` android16 `:6199-6211`). `onStartCommand`'s `enterForeground()` then answers it,
  which is correct.
- But if the service is brought down before that `onStartCommand` runs, `bringDownServiceLocked` posts
  `SERVICE_FOREGROUND_CRASH_MSG` and the app crashes (`:6359-6383`). Two ways that can happen:
  - `stop()` from the next `apply()`. `apply()` is `@Synchronized`, but `start()` returns as soon as AMS has queued
    the start.
  - `stopSelf()` from `onRevoke`.
- The window is a main-thread hop wide, and it crashes the launcher once, not in a loop. Still, it's the one
  remaining way this round can produce the crash it was written to avoid.
- Fix: decide on whether an instance runs, not on whether it holds the foreground state.
  - `@Volatile var instanceRunning`, set in `onCreate`, cleared in `stopSelfCleanly`.
  - `start()` sends a plain `startService` whenever it is set. A plain start on a started record skips the
    background check (`!r.startRequested && !fgRequired`, `:1083`), so it never throws there and never carries
    `fgRequired`.
  - `startForegroundService` + `EXTRA_FOREGROUND` stays only for "no instance", where `onCreate`'s `startForeground`
    answers first, and as the `IllegalStateException` fallback.
  - This also stops a notification being re-posted at every sync while the filter is held in the foreground (tunnel
    down, or not always-on yet).

**3 (Low) - `onRevoke` runs on a binder thread, and the new fields assume the main thread.** `KidVpnService.kt:341-375`,
`:82`, `:85-86`, `:264-273`.
- `VpnService.Callback.onTransact(LAST_CALL_TRANSACTION)` calls `onRevoke()` on the binder pool (`VpnService.java`
  android14 `:445-454`). So `stopSelfCleanly` writes `runningWithoutForeground`, `stopped` and `pfd` while the main
  thread may be in `settleForeground`/`tunnelLost`/`onStartCommand`. `stopped` isn't volatile and the main thread
  reads it without the lock.
- Possible outcomes:
  - `settleForeground` sets `runningWithoutForeground = true` after the revoke cleared it (`onDestroy` clears it
    again);
  - `tunnelLost` or `enterForeground` re-posts the notification on a revoked instance until `onDestroy`.
- Nothing lasts, but the "(main thread only)" comment on `foreground` doesn't hold for the state it depends on.
- Fix: `override fun onRevoke() { main.post { stopSelfCleanly(); stopSelf() } }`. Don't call `super.onRevoke()` on
  the binder thread. All state transitions then happen on main. Or at least mark `stopped` `@Volatile` and set it
  first in `stopSelfCleanly`.

**4 (Low) - the self-heal only sees a thrown read.** `KidVpnService.kt:256`.
- `if (length <= 0) continue`: a `-1` (EOF) spins the IO thread forever and never reaches `tunnelLost`.
- On a tun fd this is unlikely (a detached tun returns `-EBADFD`, which throws), and it predates this branch. But
  it's one line next to the new code.
- Fix: `if (length < 0) { main.post { tunnelLost(fd) }; return }`. Keep `continue` for `0`.

**5 (Medium) - the tests pin the pure rule and the crash-relevant lines, not the state that ties them together.**
`QuietNotificationsTest.kt`.
- Caught: mutations 1 and 2 (the pure `vpnHoldsForeground`, `EXTRA_FOREGROUND` first in `onStartCommand`). The source
  pins also hold the extra on the only `startForegroundService` and `stopForeground` only in `settleForeground`.
- 10 of 12 survive (table below):
  - `runningWithoutForeground` set without a drop, or never cleared by `enterForeground`/`stopSelfCleanly`;
  - the read loop no longer posting `tunnelLost`;
  - `tunnelLost` keeping `pfd`;
  - the `pfd !== fd` guard;
  - `settleForeground` gone from `onStartCommand`;
  - the `isAlwaysOn` failure default flipped to `true` (the unsafe direction: the notification drops on a VPN we
    can't confirm is always-on).
- Medium because three of them (7, 8, 9) are exactly the "filter down without the notification" class this round
  must never allow.
- Fix, kept lean: either move the flags into a small pure holder (`VpnForegroundState` with `onEstablished`, `onLost`,
  `onStop`, `startKind()`), unit-tested, or extend the source pins:
  - `runningWithoutForeground = true` occurs once, in the `!hold && foreground` branch next to `stopForeground`;
  - `runningWithoutForeground = false` occurs in `enterForeground` and as `stopSelfCleanly`'s first statement;
  - the read loop's `catch` posts `tunnelLost(fd)`;
  - `tunnelLost` starts with `if (stopped || pfd !== fd) return` and sets `pfd = null`;
  - `onStartCommand` ends with `settleForeground()`;
  - the `isAlwaysOn` catch yields `false`.

**6 (Low, docs) - two small inaccuracies.**
- `VpnForeground.kt:5` says "Pure, `VpnForegroundTest`". The test is `QuietNotificationsTest`.
- `launcher/CLAUDE.md` "Notifications in the shade" (and PLAN): "Setting always-on while our tunnel already runs
  restarts nothing (`setAlwaysOnPackageInternal` only updates its notice)". That's true, but the reason is
  `VpnManagerService.setAlwaysOnVpnPackage` calling `startAlwaysOnVpn` afterwards, which skips only because the VPN is
  connected. While the tunnel is down, every `apply()` therefore also gets a plain system start (a second heal path,
  finding 1). Worth one clause.

## The checks, with the evidence

- **The AOSP claims, each verified.**
  - `Vpn.establish` binds with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE`: android14 `:1897`, 15 `:1758`, 16
    `:1766`.
  - From a persistent client that gives `PROCESS_STATE_BOUND_FOREGROUND_SERVICE` (`OomAdjuster` 15 `:2884-2885`).
  - That passes Doze/power-save/restrict-background: `FOREGROUND_THRESHOLD_STATE = PROCESS_STATE_BOUND_FOREGROUND_SERVICE`,
    `procState <= FOREGROUND_THRESHOLD_STATE` (`NetworkPolicyManager` 16 `:188`, `:831-838`).
  - Moot while the anchor runs: its FGS keeps the uid at `FOREGROUND_SERVICE` anyway.
  - Clearing always-on: `setAlwaysOnPackageInternal(null)` -> `LEGACY_VPN` -> `prepareInternal`. That calls
    `jniReset`, the oneway `LAST_CALL_TRANSACTION` (= `onRevoke`) and `unbindService` (16 `:1279-1301`).
  - `startAlwaysOnVpn` is a plain `mUserIdContext.startService` with a temp allowlist, skipped while connected (14
    `:1261`/`:1292`, 15 `:1118`/`:1149`, 16 `:1126`).
  - A `startForegroundService` without `startForeground` crashes: `serviceForegroundTimeout` (16 `:7597`; 30 s timeout +
    10 s ANR delay, `ActivityManagerConstants` 15 `:301-303`), and `bringDownServiceLocked` with `fgRequired` (finding 2).
  - A `startForegroundService` on a service that is already foreground clears `fgRequired` at once (16 `:6206-6210`), so
    the old "every `apply()` calls `startForegroundService`" was safe only because the service never left the
    foreground.
  - Going foreground again from the background: a second `startForeground` recomputes the restriction with our own uid
    (`mStartForegroundCount >= 1`, 16 `:2510-2516`). `shouldAllowFgsWhileInUsePermissionLocked` returns
    `REASON_DEVICE_OWNER` (`:8535-8540`) and that feeds `shouldAllowFgsStartForegroundNoBindingCheckLocked` (`:8747`).
    `REASON_OP_ACTIVATE_VPN` (`:8883-8886`) and the anchor's FGS procstate with `PROCESS_CAPABILITY_BFSL`
    (`:8757-8765`) would each allow it too.
- **Every path into `onCreate`/`onStartCommand`.**
  - `onCreate` still calls `startForeground` first (pinned), whatever the start was.
  - `onStartCommand` answers an `EXTRA_FOREGROUND` start with `enterForeground()` before anything else (pinned). That
    covers a redelivered pending start too, because the extra rides on the intent.
  - A sticky restart (`null` intent), the system's always-on start and our plain start carry no `fgRequired`.
  - Two `EXTRA` starts in a row: each answered.
  - A plain start after an `EXTRA` start overwrites `r.fgRequired = false` (`:1202`), so it can only relax.
  - A start while the instance is stopping: `runningWithoutForeground` is cleared in `stopSelfCleanly`. A stale `true`
    sends a plain start that creates a fresh record. That passes the background check while the anchor or the VPN
    binding keeps the uid active, else the caught `IllegalStateException` (`BackgroundServiceStartNotAllowedException`
    extends it) falls back to `startForegroundService`.
  - The one remaining crash window is finding 2.
- **`isAlwaysOn` on 34-36.** `VpnService.isAlwaysOn()` -> `isCallerCurrentAlwaysOnVpnApp` -> `getVpnIfOwner` (needs
  `getUnderlyingNetworkInfo()`, i.e. `mNetworkAgent != null && mInterface != null`, owned by us) `&& getAlwaysOn()`
  (`VpnManagerService` 15 `:818-822`, `:673-682`; `Vpn` 16 `:2284`, `:1827`). `establish()` creates the agent
  synchronously (`startNewNetworkAgent`), so it is true straight after `establish()`. It is false once the interface
  is gone, before the first `setAlwaysOnVpnPackage`, and after a clear. A throw is treated as `false` (the safe side).
- **Never down without the notification.** It drops only with `pfd != null` and `isAlwaysOn`, so the VPN network is
  running and owned by us. A failed `establish()`, a dead read loop and a not-yet-always-on VPN all hold it. A
  revoke ends the service; the pause/override and filter-off paths clear always-on and `stop()`. Ending the pause
  sets always-on, which has the system start the service, then our `start()` follows. The VPN tears down only through
  `prepareInternal` (with `onRevoke`) or the interface going away (our read fails). Weak spots: findings 1 and 4.
- **Self-heal loop.** No tight loop is possible. `tunnelLost` only re-takes the foreground state, and at most each
  `apply()` re-establishes. A tunnel that dies at once flips the notification once per `apply()`: `settleForeground`
  drops it, then `tunnelLost` takes it back. That is bounded and silent (MIN channel, FGS notification deferral).
  Finding 1's retry must keep a backoff.
- **Reboot / direct boot.** Nothing new runs before unlock. `KidVpnService` isn't `directBootAware` (manifest
  `:499-510`). `VpnManagerService` starts always-on at `ACTION_USER_UNLOCKED` (`onUserUnlocked`, 15 `:913-921`), and
  our `start()` stays in `initRest`/`PackageReplacedReceiver`. After unlock: `onCreate` goes foreground, establishes,
  then `onStartCommand` drops (PLAN's device check 3 covers it).
- **Created by the system's binding without a start** (e.g. `BIND_AUTO_CREATE` after a process death): `onCreate`
  holds the foreground state and no `onStartCommand` drops it. The notification stays, which is the safe side, until
  the sticky restart or the next `start()`.
- **Thread safety.** `pfd`, `foreground` and `tunnelLost` are on main except for `onRevoke` (finding 3).
  `runningWithoutForeground` is `@Volatile`. The read loop passes its own `fd` and `tunnelLost` ignores a stale one
  (`pfd !== fd`). A loop killed by `stopSelfCleanly`'s close posts after `removeCallbacksAndMessages` and finds
  `stopped`. Double-closing a `ParcelFileDescriptor` is harmless. In-flight `handlePacket`s on the dead stream fail
  inside their `catch`.
- **The anchor string.** `notification_listener_text` is gone from both `values` and `values-nb`, and no reference is
  left. `TranslationsTest` (key parity) and `VibbOneNameTest` (`notification_listener_title` = "Vibb One", en and
  nb) pass. `QuietNotificationsTest` pins no body.
- **Design 36's protect rule.** Untouched. `custom`, `providerDoh` and the network callback outlive a lost tunnel
  (their sockets stay `protect`ed; we stay the prepared package). The next `startVpn` reuses them: `syncCustom` sees
  no change, and `watchNetwork` is already registered.

## Mutations

| # | Mutation (one change, reverted) | Result |
| --- | --- | --- |
| 1 | `vpnHoldsForeground` = `!tunnelUp` (always-on ignored) | caught - `QuietNotificationsTest` (pure rule) |
| 2 | `onStartCommand`: the `EXTRA_FOREGROUND` answer moved after `startVpn()` | caught - `QuietNotificationsTest` (first statement) |
| 3 | `start()`: `if (true)` - always a plain `startService` first | **NOT caught** (finding 5) |
| 4 | `settleForeground` drops without `runningWithoutForeground = true` | **NOT caught** (finding 5) |
| 5 | `enterForeground` without `runningWithoutForeground = false` | **NOT caught** (finding 5) |
| 6 | `stopSelfCleanly` without `runningWithoutForeground = false` | **NOT caught** (finding 5) |
| 7 | the read loop's `catch` without `main.post { tunnelLost(fd) }` (the self-heal and the notification's return gone) | **NOT caught** (finding 5) |
| 8 | `tunnelLost` without `pfd = null` (the tunnel never re-established, the notification stays dropped) | **NOT caught** (finding 5) |
| 9 | `isAlwaysOn` failure defaults to `true` (drops on an unconfirmed VPN) | **NOT caught** (finding 5) |
| 10 | `settleForeground` without `if (stopped) return` | **NOT caught** (finding 5) |
| 11 | `tunnelLost` without the `pfd !== fd` guard (a stale loop closes the new tunnel) | **NOT caught** (finding 5) |
| 12 | `onStartCommand` without `settleForeground()` (the notification never drops) | **NOT caught** (finding 5) |

2 caught, 10 survive (3-12). The crash-relevant lines are the ones caught, along with the source pins that have no
mutation of their own here (`startForeground` first in both `onCreate`s, the one `stopForeground`, the extra on the
one `startForegroundService`, the anchor's strings). 7, 8 and 9 are the ones that would let the filter go down, or
the notification go away, unseen.

## Verdict

**Approved with fixes.** The AOSP basis holds on 14, 15 and 16. Every start path either calls `startForeground` in
time or carries no foreground requirement. The safety-critical lines (`startForeground` first in `onCreate`, the
`EXTRA_FOREGROUND` answer first in `onStartCommand`, the extra on the only `startForegroundService`) are pinned. No
path drops the notification while the filter is down, and nothing runs before unlock.
- Before the merge:
  - finding 1: a bounded in-service retry after `tunnelLost`, so the self-heal doesn't wait for the next sync;
  - finding 5: pins (or a pure holder) for the flag and the self-heal, at least for mutations 7, 8 and 9.
- In the same fix commit (each small):
  - finding 2: plain start whenever an instance runs - this removes the last crash window;
  - findings 3 and 4;
  - finding 6's two doc lines.
- Owed on the Jelly Star: PLAN's five device checks, unchanged.
