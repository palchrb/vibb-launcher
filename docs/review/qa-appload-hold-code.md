# Code QA: appload-hold (an `AppEnforcer.apply` holds the app-list loads until its pass ends)

Branch `appload-hold` (045419b2) against main `6ad9b1d7`. Reviewer's branch `qa-appload-hold` = `appload-hold` + this
file. It follows `qa-appload-debounce-code.md` (approved). The jelly36 storm run with the debounce had 0 ANRs of
ours but 59 app-list loads in two minutes: on a loaded emulator, the enforcer's per-package DPM calls came more than
300 ms apart, so most packages opened a burst of their own.

## Setup

- I read `git diff 6ad9b1d7..appload-hold` in full: `AppLoads.kt` (`APP_LOAD_HOLD_CAP_MS`, `APP_LOAD_HOLD_POLL_MS`,
  `appLoadHeld`, `AppLoadHold`, the loop), `AppEnforcer.apply`/`applyHeld`, `Application.kt`'s `heldSince` line, the
  seven new and changed `HomeRound7Test` cases, the PLAN line and the `launcher/CLAUDE.md` bullet.
- Launcher suite (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug
  testDebugUnitTest`): **BUILD SUCCESSFUL in 36s; 1717 tests, 0 failures, 0 errors, 0 skipped.** `HomeRound7Test`
  has 22 tests. The only warning is kapt's `RELEASE_11` line.
  - `compileDebugKotlin` was already up to date from the probe run below, which built the same sources.
  - `testDebugUnitTest` and `assembleDebug` ran.
- Probe: a throwaway `QaHoldChainProbeTest` (not committed, deleted after) drove the real `runAppLoads` on the
  virtual clock for Q-1 and Q-2.
- Mutations: one exact replacement at a time, running `HomeRound7Test`. These runs were without `-PwarningsAsErrors`,
  so a mutation that only leaves an unused variable still compiles. A watchdog killed a test JVM after 120 s. The
  baseline passed first, and the worktree was clean afterwards.
- No emulator, as briefed.

## What was checked and holds

- **The `applyHeld` split is mechanical.** I diffed `AppEnforcer.kt` against main: 12 added lines, nothing removed or
  changed. The old body of `apply(context, policy, fromSync, parentChoice)` is now `applyHeld`'s body, word for word.
  - Its early `return`s (not device owner, and the rest) now return from `applyHeld`, and the `finally` releases the
    hold.
  - The body has no labelled return.
  - The `apply(context, decision)` overload still delegates to the same `@Synchronized` function.
  - `applyHeld` is private, has one caller and runs only under `apply`'s monitor.
- **Depth and reentrancy.** `hold` comes after the `@Synchronized` monitor is taken and `release` before it is let go.
  So threads queued on `apply` hold nothing while they wait, and two threads never hold at once.
  - The only way to depth 2 is a reentrant `apply`. None exists: `enforceOnNewPackage`'s `apply` (`AppEnforcer.kt:1217`)
    isn't reached from `applyHeld`, and `CameraLock`'s apply runs on its own coroutine. So the counter is defensive.
  - `AppLoadHold`'s own `@Synchronized` guards `depth`, and `heldSince` is `@Volatile` for the consumer's lock-free
    read.
- **The hold across an exception.** `hold` can't throw (an increment and a write). The `try`/`finally` releases on any
  `Throwable` from the pass, so an exception never leaves the loads held. It would only leave them held until the 10 s
  cap if the process lived on with a thread stuck forever in `applyHeld`.
- **Phones without an apply running: unchanged.** With `heldSince` null, `held` is false and `wasHeld` stays false.
  The break condition reduces to `appLoadDue(...)` and the wait to `appLoadWaitMs(...)`, exactly the approved
  debounce. The test model's `held = { null }` default checks this. An apply with no pending burst changes nothing
  either: the consumer sits in `next(null)` and reads no hold.
- **The 100 ms poll costs only while held and with a burst pending.** The poll exists only inside the burst loop
  (`AppLoads.kt:131-133`), which is entered only after a request. It is at most ten `withTimeoutOrNull`s a second on
  `Dispatchers.Default`, each a few µs, and it stops at the release or the cap. A request during the hold wakes the
  loop early, the same as before. The ceiling `since + CAP - t` is always at least 1 ms while held (`held` means
  `t - since < CAP`).
- **The first request of the process stays immediate.** `firstOfProcess` is checked before any hold (pinned:
  `loadsFor(listOf(10_000L), held = { 10_000L }) == [10_000]`).
- **The cap's semantics as built:**
  - A burst opened inside a hold waits for the release, and loads at once then: `wasHeld` skips the quiet period,
    and the apply's DPM calls have returned, so `getApps` reads the finished state.
  - A burst opened inside a hold that hangs loads at the hold's start + 10 s.
  - A burst opened after that cap gets the normal debounce. That is fine: a pass that hangs gets the pre-hold
    behaviour, one load per burst, never worse than the approved debounce.
- **The pins.** The wiring is pinned in order: hold before `try`, `applyHeld` in it, release in the `finally`, one
  `applyHeld`, and the consumer's `heldSince` line. The cap's boundary and the nesting are pinned. The loop on the
  virtual clock covers a 5 s apply with 50 callbacks 100 ms apart and 12 callbacks 400 ms apart (one load at the
  release), a hung apply (one load at 10 s), and no apply (the debounce).
- **Docs.** The `launcher/CLAUDE.md` bullet and the PLAN line match the code, except for the "at most 10 s" wording
  (Q-1).

## Mutations (each one alone, `HomeRound7Test`)

H1 and H3 also hang the virtual-clock loop tests (the watchdog killed the JVM after 120 s). Each makes `held` true
while the wait's ceiling `since + APP_LOAD_HOLD_CAP_MS - t` is 0. The boundary test catches both before that.

| # | Mutation | Result | Caught by |
|---|---|---|---|
| H1 | `appLoadHeld` `now - heldSince < capMs` -> `<=` | caught | `an apply holds the loads, at most 10 s` (the boundary); the loop tests hang |
| H2 | `APP_LOAD_HOLD_CAP_MS` 10 000 -> 20 000 | caught | the constant pin, the hung-apply test |
| H3 | `appLoadHeld`'s default cap 20 000 (constant kept) | caught | the boundary test; the loop tests hang |
| H4 | the loop's poll ceiling uses another cap (`since + 20_000L - t`) | **missed** | - (harmless: the 100 ms poll is the binding term; at most one poll late past the cap) |
| H5 | a nested `hold` moves the start | caught | `the hold nests and ends only at the outer release` |
| H6 | the first `release` ends a nested hold | caught | the nesting test |
| H7 | no `depth > 0` guard (an extra release goes negative) | **missed** | - (the test's extra release is its last step; then no later hold would ever set `heldSince`) |
| H8 | `wasHeld = true` removed (a released hold falls back to the debounce) | **missed** | - (Q-3) |
| H9 | the hold's wait is the rest of the cap, not the 100 ms poll | caught | the 5 s apply test |
| H10 | the loop ignores the hold (`held = false`) | caught | the 5 s apply and hung-apply tests |
| H11 | `apply` never calls `hold` | caught | the wiring pin |
| H12 | `apply` never calls `release` | caught | the wiring pin |
| H13 | the consumer reads no hold (`heldSince = { null }`) | caught | the wiring pin, the consumer pin |

## Findings

### Q-1 (Low, behaviour + docs) - back-to-back applies hold a burst past 10 s; the cap is per apply, not per burst

`AppLoads.kt:56`, `:128-133`; the `launcher/CLAUDE.md` bullet ("at most `APP_LOAD_HOLD_CAP_MS` 10 s"), the PLAN
line, and the KDoc ("a hold ends with the apply, or [capMs] after its start").

`heldSince` is the start of the **current** apply. `apply` is `@Synchronized`, so when one pass releases, the next
thread queued on the monitor takes it and calls `hold` within microseconds. The consumer, polling every 100 ms, almost
never sees the `null` in between, and every new apply resets the cap.

Probe: three 8 s applies back to back, with callbacks at 10 050 and 10 400 ms, give the loads `[0, 34 000]`. The burst
is held 24 s.

A chain like that is plausible exactly in the storm this is for. Many callers queue on the lock: 23 `AppEnforcer.apply`
call sites, among them the sync, the device-admin receiver, the camera lock and `WindowRelock`. The provisioning storm
also queues one `apply` per recently sideloaded package (qa-appload-debounce-code Q-1), each taking seconds on a
loaded emulator. So Home could show the pre-enrolment list of every app for the length of the chain: tens of seconds
in the jelly36 run's terms.

That is only cosmetic, since the platform enforces. But it isn't the documented bound, and outside provisioning a
parent's "Allow these apps" could also wait behind a queued sync apply.

What to do:
- Bound the hold per burst as well, for example `val held = appLoadHeld(t, since) && t - firstAt <
  APP_LOAD_HOLD_CAP_MS`, or remember when the burst first saw a hold.
- A chain then gives one load per 10 s.
- Or keep the behaviour and reword the docs to "until no apply runs (each pass's hold at most 10 s)".

### Q-2 (Info) - callbacks that trail the release are a second load

The platform delivers `LauncherApps` callbacks asynchronously, after the DPM call has returned. So the last packages'
callbacks can arrive after `release`. The load at the release is correct, because it reads the finished state, but
the trailing callbacks open a new burst and a second load 300 ms after them.

Probe: a 5 s hold with callbacks at 15 150 and 15 250 ms gives `[0, 15 000, 15 550]`.

On the emulator expect about two loads per apply, not one. That is still far below 59. If the next run shows it
matters: at the release, set `lastAt = t` and go on with the debounce, which absorbs a trailing tail for 300 ms of
latency. No change is needed otherwise.

### Q-3 (Info, test gap) - `wasHeld` is pinned by nothing

H8 removed the `wasHeld = true` assignment and every test still passed. Every hold in the tests lasts 2 s or more, so
`appLoadDue`'s 2 s max wait is already true at the release, and the burst loads at once with or without `wasHeld`.
`wasHeld` matters only for a hold shorter than the quiet period's reach, for example a 500 ms apply with a callback
50 ms before its release. That burst should load at the release, not 300 ms after the callback.

Optional: a case with a 500 ms hold.

### Q-4 (Info) - the loop's poll ceiling spells the cap again

`AppLoads.kt:133` computes `since + APP_LOAD_HOLD_CAP_MS - t` itself rather than from `appLoadHeld`'s `capMs`. With
the default they agree. H1 and H3 show what a mismatch does: `held` true with a 0 ms wait is a busy loop of
`withTimeoutOrNull(0)`, up to 1 ms in the app and a hang on the virtual clock. Only one caller exists, so this is
latent.

Optional: a pure `appLoadHoldWaitMs(now, heldSince, capMs, pollMs)` beside `appLoadHeld`, as `appLoadWaitMs` pairs
with `appLoadDue`. Its own test could then pin "held means a wait > 0", as the debounce's grid does.

### Q-5 (Info) - the nesting test mutates a process-wide singleton without a `finally`

`the hold nests and ends only at the outer release` calls `AppLoadHold.hold`/`release` on the real object. If an
assert failed between the holds, `depth` would stay above 0 for the rest of the test JVM. Today no other test reads
the object.

Optional: release in a `finally`, or reset the object.

## Verdict

**Approve with one Low (Q-1).** The `applyHeld` split is purely mechanical. The hold is exception-safe and
single-holder by construction. Phones with no apply running get exactly the approved debounce, and the poll costs
something only while an apply runs with a burst pending. The first request stays immediate, and the pins catch every
mutation that matters (H4, H7 and H8 are harmless or Info).

Q-1 is the one thing to decide: either a per-burst bound (`t - firstAt < cap` in the held check, a one-line change
plus a chain test) or the docs reworded to say a chain of applies holds the burst until the chain ends. Q-2 to Q-5 are
optional.

The emulator check stays owed: the provisioning run's load count. Expect about two loads per apply (Q-2), fewer with
a per-burst cap under a chain.
