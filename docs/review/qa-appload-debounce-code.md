# Code QA: appload-debounce (the app list loads once per burst)

Branch `appload-debounce` (e90e012d) against main `6011c024`. Reviewer's branch `qa-appload-debounce` =
`appload-debounce` + this file. The item: PLAN "Provisioning storm: debounce the app-list reload"
(qa-home-round-code Q-4). Also read: `docs/testing/2026-10-24-home-round-run.md` (the two ANRs),
`docs/review/qa-home-round-code.md` ("The first-frame question", Q-4), `launcher/CLAUDE.md` (round 7 §4 bullet), and
`qa-r11-glance-code.md` for the format.

## Setup

- I read `git diff 6011c024..appload-debounce` in full: `ui/home/AppLoads.kt` (new), `Application.kt`'s consumer,
  the `HomeRound7Test` additions, the PLAN entry and the `launcher/CLAUDE.md` bullet. I also read the code around it:
  every `LauncherApps.Callback` method, `evictIcons`/`KidAvatars.evictPackage`/`appIconKeyApp`, `initRest`'s first
  `loadApps()` and the `WindowRelock` listener, `AppDisplay.refresh`, `WindowRelock.onPackageInstalled` and
  `blockAfterInstall`, `CameraLock.ownChange`, `MusicSessions.packagesChanged`, and Home's `render`/`refreshApps`/
  `renderGrid`. I also traced every reader of `Application.apps`: Home's observer, the drawer's
  `AppsRecyclerAdapter` and `AppIconLoader.key`.
- Launcher suite (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug
  testDebugUnitTest`, `local.properties` copied in): **BUILD SUCCESSFUL in 2m 5s, 54 actionable tasks: 54 executed;
  1710 tests, 0 failures, 0 errors, 0 skipped.** `HomeRound7Test` has 15 tests. No new warning: the only warning
  is kapt's `RELEASE_11` line (twice).
- Mutations: a script made one exact replacement at a time, ran `testDebugUnitTest --tests
  com.kidslauncher.mdm.ui.home.HomeRound7Test` and put the file back. A baseline run with no change passed first.
  The worktree was clean (`git status` empty) before this file.
- No emulator, as briefed.

## What was checked and holds

- **The rule.** `appLoadDue` is `now - last >= 300 || now - first >= 2000`. `appLoadWaitMs` is the time to the
  earlier of the two deadlines, floored at 0. The test's grid shows the two agree exactly: "not due" always means a
  wait greater than 0. So the burst loop never spins, and `withTimeoutOrNull(0)` (which returns null without
  receiving) is never reached while the burst isn't due.
- **The loop.** One coroutine runs it. Each REQUEST inside a burst moves `lastAt`. The burst always ends in exactly
  one `load()`, whether by the due check or by CLOSED. The next burst opens only at the next REQUEST after that
  load. Every request is folded into a load that starts after it. A storm gives a load every 2 s plus the load's own
  time, then one more 300 ms after the last request.
- **The first-request rule and Home's cold start.**
  - The process's first `trySend` is always `initRest`'s `loadApps()` (`Application.kt:357`). It runs on the main
    thread right after `registerCallback`, and the callbacks reach the main looper only after `initRest` returns.
    `profileAvailabilityBroadcastReceiver` is no earlier, and `appLoads` is created lazily by that first call.
  - No request is lost to conflation before the consumer starts. Conflation folds every send made before the first
    receive into the one element. The first `next(null)` takes it and loads at once, and that load reads the
    platform after all of those sends.
  - The process may be started by a receiver with no Home (an alarm, `PACKAGE_REPLACED`, the anchor). The first load
    still runs at `initRest`, so `apps` already holds a list when Home is created later, and Home's
    `observeForever` delivers it at once.
  - Before the first unlock `initRest` is deferred (`ensureUnlockedInit`), and so is Home. `KidAppComponentFactory`
    runs `initRest` before Home's `onCreate`, so Home's very first list is still the undelayed first load.
- **A timeout racing a request: nothing is missed. Proven, as the report claims:**
  1. Kotlin channels' `receive` has had prompt cancellation since 1.4. A sender can hand its element to a suspended
     receiver whose `withTimeoutOrNull` cancels it before it resumes. With no `onUndeliveredElement`, that element is
     dropped, and `nextAppLoad` returns TIMEOUT. So a loss can happen, but only in a timed receive.
  2. Timed receives happen only inside a burst (`AppLoads.kt:83`). Every way out of the burst loop runs `load()`
     (`:87`, `:92`). After the TIMEOUT the burst is due, because the deadline passed on a clock that runs no faster
     than `elapsedRealtime`. Even if it weren't, the loop would only wait again and still end in `load()`.
  3. The dropped element's `trySend` happened before the hand-off, and the hand-off happens before the receiver's
     cancelled resumption. The `load()` (`getApps`, the `iconsToEvict` snapshot) starts after that.
  4. Every sender changes its state before it sends. The platform changes before LauncherApps calls back.
     `evictIcons` adds to `iconsToEvict` before `loadApps()`. `WindowRelock` writes its flags before it notifies.
     `AppDisplay.refresh` sets `map` before `reloadApps()` (`AppDisplay.kt:163-165`).
  
  So the load that follows a lost request always reads that request's change. The only effect is that a lost request
  doesn't push the quiet period on: the load comes up to 300 ms sooner. The opposite race is also harmless: an element
  buffered just after the timeout gave up is received at the next `next(null)` and costs one redundant load.
- **CLOSED.** The app's channel is never closed. In the loop, CLOSED at the top returns, and CLOSED inside a burst
  loads, then returns (but see the M12 test gap in the mutation table).
- **Exceptions in `load`.** The loop doesn't catch them itself. Application's load lambda catches `Exception`
  (`Application.kt:526`) as before, so a failed load is logged and the loop drains on. A `Throwable` that isn't an
  `Exception` ends the consumer coroutine. It reaches the thread's uncaught handler, which is the process-wide crash
  handler, and the process exits. The old `consumeEach` behaved the same, so nothing changed there (see Q-3).
- **The coroutine clock and `elapsedRealtime` across deep sleep: harmless, as the report says.** `now()` is
  `CLOCK_BOOTTIME`, and the timeout's delay is `CLOCK_MONOTONIC` (`System.nanoTime` on ART). Monotonic time
  doesn't advance in suspend. So a burst that began just before the CPU slept loads within the rest of its monotonic
  wait after the next wake: at most 300 ms (or the rest of the 2 s), with `appLoadDue` true by then. Nothing holds a
  wake lock for it, and nothing needs one, because the list only matters while the screen is on. At a screen-on, Home
  shows the old list for at most that remainder. Before this branch the same reload ran during the callback's own
  wake.
- **Cancellation.** The consumer's scope (`SupervisorJob() + Dispatchers.Default`) is never cancelled, and process
  death ends it. No path cancels it mid-load.
- **The callbacks are untouched.** The diff changes only the consumer. Each callback still does its other work at
  once: icon eviction, `clockAppChanged`, `enforceOnNewPackage`, `WindowRelock.onPackageInstalled` and its
  `apply`/sync, the music nudge, `MusicSessions.packagesChanged` and `CameraLock.ownChange`. I traced what else
  reacts to a single package change, and only the list itself is delayed:
  - **The music app's appearance.** `package_added` asks for a sync at once, and the courier and nudge are
    unchanged. Only its Home tile waits 300 ms. The music card comes from the session, not the list.
  - **The update fence.** It suspends through `apply`/`UpdateFence`, which never read `apps`. Its
    `onPackagesSuspended` callbacks only reload.
  - **The relock block path.** `onPackageInstalled` -> `apply` -> sync runs per callback as before. The window
    listener's reload (Play in the drawer during a window) waits 300 ms.
  - **`AppIconLoader.key`** (the glance and the time-rule screen) falls back to `PackageManager.getApplicationIcon`
    when `apps.value` doesn't know the package yet. A newer app is still drawn.
  - **No enforcement reads `apps`.** Hidden and suspended apps are enforced by the platform. A stale tile can't open
    anything the platform blocks. A removed app's tile can stay up to 300 ms longer, and a tap there gets the
    inherited "can't open" dialog, which only closes. Before, that window was as long as the load.
- **The 300 ms on non-storm reloads is acceptable.**
  - "Allow these apps" (`allow_blocked`) -> `apply` unhides and unsuspends one to a few packages. Each unhide is an
    `onPackageAdded`, and the unsuspends come as one batched `onPackagesUnsuspended`. The load comes 300 ms after the
    last of those callbacks, then the render. 2 s is reached only if callbacks keep coming for 2 s, which in `apply`
    means a long hide/unhide loop. Even then the kid sees a list at 2 s and the rest 300 ms after the loop. A first
    list with only part of the apps shown is possible only in such a long apply.
  - Window edges (Play in the drawer), `AppDisplay` name/icon changes and a single install or uninstall: each is
    300 ms later plus the load, against a sync round-trip or a parent's action. Not perceptible as a regression.
- **The ANR's main-thread cost is now small. Estimate per callback on the main thread:**
  - `evictIcons`: a `synchronized` add, then `KidAvatars.evictPackage` takes `LruCache.snapshot()` (a copy of the
    map). Per key it does a `Regex.replace` and a kotlinx.serialization decode of the key (`packageOf`), about
    10-30 µs a key on ART.
  - The cache holds every rendered size. At provisioning on the emulator the unmanaged Home had about 35 tiles, so
    about 35 keys, about 0.5-1 ms. The worst case is a full 8 MB cache of 90 px icons (about 250 keys), about 3-8 ms.
  - `loadApps` is a `trySend` (about a µs). `clockAppChanged` is one string compare. `CoroutineScope(IO).launch` is an
    allocation and a dispatch (about 10-20 µs). `CameraLock.ownChange` and `MusicSessions.packagesChanged` are set
    checks.
  - **100 callbacks** come to about 50-100 ms of main-thread work in all at the provisioning size, and well under 1 s
    in the worst case. It arrives as 100 separate messages of at most a few ms each, so input is never held for
    anything near the 5 s dispatch timeout.
  - What made the storm heavy was the back-to-back loads. Each one posted a list, and Home's observer ran
    `render()` on the main thread (`renderWindowBanner`'s prefs read, `renderCallParts`, `refreshApps`, which
    cancels the previous render job), then `renderGrid` with `DiffUtil` on the main thread. The drawer adapter
    rebinds too while it is open. That is now once per burst: at most one per 2 s plus the load's time.
  - Whether this ends the ANRs on a starved emulator is the owed emulator check (PLAN says so).
- **Docs.** `launcher/CLAUDE.md`'s round 7 §4 bullet and `AppLoads.kt`'s header match the code: the constants, the
  first-request exception and the callbacks' own work. The PLAN entry is marked done with the emulator check owed.
  See Q-1 and Q-2 for two wording points.

## Mutations (each one alone, `HomeRound7Test`)

A baseline run passed first. Every file was put back afterwards (`git status` clean except this file).

Three mutations hang the virtual-clock loop tests: M1, M2 and M6. Each makes `appLoadWaitMs` 0 at a moment
`appLoadDue` still says "not due". The model's clock then never moves, so a watchdog killed the test JVM after
120 s. All three are caught by `a burst loads after 300 ms of quiet...` (its boundary asserts and its due-vs-wait
grid). In the app the same mismatch would be a busy loop of `withTimeoutOrNull(0)` until the next millisecond (M1, M2)
or for a whole second (M6). The grid is the right pin for that.

| # | Mutation | Result | Caught by |
|---|---|---|---|
| M1 | quiet `now - lastEventAt >= quietMs` -> `>` | caught | boundary `appLoadDue(1_300, 1_000, 1_000)` and the grid; the loop tests hang |
| M2 | max `now - firstPendingAt >= maxWaitMs` -> `>` | caught | the grid; the loop tests hang |
| M3 | `APP_LOAD_QUIET_MS` 300 -> 250 | caught | the constant pin, the storm, 50-event, single-event tests |
| M4 | `APP_LOAD_MAX_WAIT_MS` 2000 -> 3000 | caught | the constant pin, the storm test |
| M5 | `appLoadDue`'s default quiet 250 (constant kept) | caught | the boundary/grid test |
| M6 | `appLoadDue`'s default max 3000 (constant kept) | caught | the grid; the loop tests hang |
| M7 | `appLoadWaitMs` `minOf` -> `maxOf` | caught | the boundary test, the storm, 50-event, single-event tests |
| M8 | `appLoadWaitMs` without the max term | caught | the boundary test (`appLoadWaitMs(2_950, 1_000, 2_900) == 50`) only |
| M9 | the first request opens a burst (`firstOfProcess = false`) | caught | the first-request test, the storm, 50-event, single-event tests |
| M10 | a request in a burst doesn't move `lastAt` | caught | the storm and 50-event tests |
| M11 | `firstAt` pinned to 0 (the burst start never moves) | caught | the storm, 50-event, single-event tests |
| M12 | CLOSED inside a burst returns without its load | **missed** | - (Q-3: the model answers CLOSED only to an untimed wait; the app never closes the channel) |
| M13 | `nextAppLoad`: a closed channel reads as TIMEOUT | caught | the real-channel test |
| M14 | wiring: the consumer's waits never time out (`nextAppLoad(null)`) | caught | the consumer source pin |

M8 is caught by one assert only. Without the max term, the loop still loads at the max deadline whenever a request
lands after it, which is every 20 ms in the storm model. So the loop tests can't tell, and the wait value is what pins
it.

## Findings

### Q-1 (Low, docs) - `onPackageInstalled`'s apply isn't "never for a hide"

`PLAN.md:1133-1135` and the commit message. The PLAN line reads: "`apply` only when a block changes (never for a
hide, unhide or suspend - an old update time or a system app)". `blockAfterInstall` (`WindowRelockPlan.kt:356-373`)
doesn't know why the callback came:

- A package installed in the last 10 minutes from an installer other than Play or us gets a block on its **first**
  `onPackageChanged` or `onPackageAdded`, whatever that callback was for. That includes the provisioning storm's own
  hide or suspend. The block is followed by a full `AppEnforcer.apply` and a sync.
- This happens only while no window is open and no relock marker is pending.

At provisioning on the emulator that is every app adb-installed just before `set-device-owner`. On a real phone it is
about none, because catalog apps come from us.

It is bounded. The second callback for the same package finds it blocked, `blockStays` holds, and the answer is
`null`. So the count is "at most one apply per recently sideloaded package", not one per callback. The implementer's
decision not to coalesce stands.

What to do: reword the PLAN line to "apply only when a block changes - at most once per package installed in the last
10 min from elsewhere than Play or us; never for an older app's hide, unhide or suspend".

### Q-2 (Low, docs) - the burst starts when the consumer takes the request, not when it was sent

`AppLoads.kt:12-13`, `:20`, `:80`, and the `launcher/CLAUDE.md` bullet.

The docs say "at most 2 s after the burst's first request". A request sent while a load runs waits in the conflated
channel. The consumer takes it only when that load ends (`:74`), and `firstAt` is taken then (`:80`). So that
request's list comes after the rest of the running load plus 300 ms, or plus up to 2 s in a storm. That is up to
2 s + L after it was sent, where L is the load's time.

The behaviour is right: it is what spaces the loads in a storm, and the `loadMs = 400` test case relies on it. Only
the sentence overstates the bound, and on a starved CPU at provisioning L can be hundreds of ms. The request carries no
timestamp (`Channel<Unit>`), and a conflated channel would keep only the newest one anyway, so the code can't do
better cheaply.

What to do: say "300 ms with no new request, at most 2 s after the consumer takes the burst's first request (a request
made during a load counts from the load's end)".

### Q-3 (Info, test gap) - no test pins the load body's `try`, or a pending load at CLOSED

The loop survives a failing load only because Application's lambda catches `Exception`. `HomeRound7Test` pins the
lambda's `getApps` line and the eviction order, but not the `try`/`catch`.

A future edit that dropped the `try` would end the consumer at the first exception. The crash handler would then end
the process, as with `consumeEach` before, so it wouldn't fail silently.

M12 shows that the "CLOSED inside a burst still loads" branch isn't exercised either. The virtual clock answers
CLOSED only to an untimed wait. The app never closes the channel, so this matters only for the KDoc's promise.

Optional: assert `loads.contains("} catch (e: Exception) {")` in the consumer pin, and let `loadsFor` answer CLOSED
to a timed wait once the requests run out.

### Q-4 (Info, optional) - `evictIcons` decodes every cache key per callback, also for a hide or suspend

`KidAvatars.kt:153-161`. A hide, unhide or suspend doesn't change an app's icon. Still, every
`onPackageChanged`/`Added`/`Removed` decodes every cached key with kotlinx.serialization to find the package's own.
The estimate above puts it at about 1 ms a callback at provisioning and a few ms at worst. That is no ANR cause, and
the eviction must stay for real updates.

Cheap if ever wanted: skip keys that don't contain the quoted package name before the decode.

## Verdict

**Approve.** The loop is correct:

- one load per burst;
- the first request at once, which covers Home's cold start in every way the process can start;
- no request ever lost in effect, timeout race included (proven above);
- deep sleep and cancellation harmless;
- exceptions handled as before.

Nothing else that reacts to a package change was delayed. The 300 ms on the quiet paths is not a perceptible
regression. The main thread's per-callback cost is now small, and Home renders at most once per 2 s in a storm.

Q-1 and Q-2 are wording fixes in PLAN/KDoc/CLAUDE.md. Q-3 and Q-4 are optional. The emulator check of a
provisioning run (no "no focused window" ANR, one or two app-list loads in the log) stays owed, as PLAN says.
