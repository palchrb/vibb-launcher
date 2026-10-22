# QA 59 step 4 - code (`design-59-step4`, `2a727685..de480163`)

Code QA of the launcher's `set_kid_pin` handler and its reports (design 59 §1, §3.5, §3.6, §3.9, "Step 4 as built"),
checked against `qa-59-design.md` (Q59-1, Q59-5, Q59-7), `qa-59-step1-code.md` (S59-1), `qa-59-step3-code.md` and step
1's server on main (`server/src/kid_lock.rs`: the queue rule, intake, `deliverable_pin`). Reviewed 2026-10-22 on
`59s4-qa` (= `de480163`). No emulator here. AOSP behaviour comes from the sources and is marked [device] where §8 has to
confirm it.

**Run**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL, 1542 tests, 0 failures.

**Verdict**: fit for the RC once S4-3 is pinned. **No wrong PIN is set, and no PIN is kept.**
- Android's PIN only ever becomes a PIN that verifies against the hash of the same fresh answer it came in.
- That answer's hash is the server's current one: the pop hands a row out only at the rev it read (S59-1), and the
  sealed PIN is opened from the same `SELECT`. So a replayed or stale command can't carry an older PIN past the check.
- A repeated id never runs, the cache and the applied policy lose the PIN, and no log line in the three files names it.
- Findings: 0 High, 0 Medium, 4 Low, 2 Nits. 17 mutations: 14 killed, 3 survived (S4-3, S4-4).

## The command, step by step (`AndroidLock.kt:204-238`, `AndroidLockPlan.kt:256-262`, `PinLockRuntime.kt:51-54`)

| Check | Verdict |
|---|---|
| Order `own lock` -> `no_pin` -> `mismatch` -> `no_token` -> set (§1) | holds. The token runs only for a PIN that verified (pinned, M9) |
| `verifyCommandPin` on the **fresh** `kid_lock` (Q59-1) | holds (pinned by value, M1). It reads no `config`, no backoff, counts no failure, and is still the only `PinHash.verify(` file |
| Exact length (deviation 2) | holds and is safe: the server stores `pin_length = pin.len()` (`decide_kid_pin`), so a real PIN always fits. Android's pad and our keypad then take the same digits (M4 killed) |
| Write-ahead, then reset, then the fresh rev (M-1) | holds (M3 killed). For the death window, see S4-1 |
| Give-back on a failed reset of a credential we didn't own | holds (M5 killed). An owned credential keeps its flag and its rev |
| Under `AndroidLock`'s monitor, shared with configure/rollback, migration, `onPasswordChanged` | holds. The migration racing the command finds `deviceSecure` and returns, in either order. `onPasswordChanged` waits until the rev is committed, then consumes the "ours" flag |
| PBKDF2 (~0.5 s) plus the reset under the monitor | no ANR risk. Every monitor taker runs off the main thread: the sync, `AppEnforcer.apply` (all callers are on IO/Default coroutines), `migrateIfDue` (Default) and `onPasswordChanged` (IO after `goAsync`). Lock order is AppEnforcer -> AndroidLock only, so there is no deadlock |
| The PIN as a parameter only | holds. `CommandAnswers` keys on id and requestedAt only. `cacheablePolicy` runs before the cache (M2) and after the dispatch. The dispatch returns the command without it (M14). Decode failures log only the class name, there is no HTTP logging interceptor, and `CrashReports` records throwables whose messages don't carry it. The scan's reach is S4-3 |

**Deviations** (design, "Step 4 as built"):
1. The write-ahead keeps the rev Android holds now. **Accepted**: before the reset and on a failed reset it is exactly
   right. After the reset, the stated heal doesn't happen (S4-1). The outcome is still safe.
2. Exact `pin_length` digits: **accepted** (above).
3. No extra apply after `pin set`: **accepted**. The sync's `AppEnforcer.apply(decision)` follows the dispatch, with the
   fresh policy already cached. The face-on edge while dark is S4-2.
4. `keyguard: "android"` for an unmanaged phone holding our PIN: **accepted**. It reports the current rev, so the intake
   neither queues nor loops, and it clears `prev`. That is better than step 1's assumed `own`.
5. The dispatch returns the command without its PIN: **accepted**.

## Reports in edge states, against step 1's intake (`kid_lock.rs:85-125`, `153-172`)

| Phone state | Report | Server | Loop / heal |
|---|---|---|---|
| Face on, ours, rev R | android, R | R = current: nothing; prev cleared | - |
| Rollback, no token (owned, secure, not requested) | android, R + `inactive: android_credential` | `kid_keyguard` own: the rule doesn't run | no loop (N-C pair) |
| Fallback apply, owned and secure | android, R | as the first row | - |
| Face on, credential not ours | android, `null` | `null` never queues (open question 4) | no loop. The parent's "send again" heals |
| Owned, credential removed, before the apply | own, the hash's rev | queues unless the newest row was answered | at most one row per answer |
| Unmanaged, our PIN set | android, R (deviation 4) | nothing | - |
| No Child PIN | own, `null` (`saveConfig(null)` clears the stored rev) | `kid_keyguard` is own after a clear | - |

The fields are always sent: no defaults, and `ServerJson` keeps explicit nulls. M6, "a default on `keyguard`", is
killed by the key-set test. `reset_token` is `resetTokenActive` in every mode. The relocks counter is an
`AtomicInteger`: `relock` updates it on the main thread and `report` takes it with `getAndSet(0)` (M10). See N2.
Remote lock: `lockAnswer(ok, ours, gateNow)` says `locked (android pin)` for the face and for our credential alone
(M15). Step 2's `locate::reword` and `PinAnswer` already know all seven words.

`unlocked_at_ms`:
- `recordBoot` (in `onCreate`, DE too) runs before `initUnlocked`.
- `decide` sets `firstCeStartOfBoot` once per BOOT_COUNT, and `recordUnlock` follows it before `initRest`, so before any
  sync.
- `update` is `synchronized(lock)`, and DE `commit()` matches `recordBoot`'s.
- A start is uploaded only from a sync, which needs CE, and `clearSent` drops it. So no stamp can come after its upload,
  and nothing stamps twice (M7, M17 killed).

## Findings

**S4-1 (Low): a PIN we set can be reported as "changed on the phone", and nothing heals it.**
- Evidence: `reset()` arms a one-shot boolean "ours" flag that lives only in memory (`AndroidLock.kt:271-276`).
  `onPasswordChanged` without the flag sets `android_pin_rev = null` (`:282-296`, `androidPinRevAfterChange`).
  `ACTION_PASSWORD_CHANGED` goes to the manifest `DeviceAdminReceiver`, so it starts a dead process. The intake never
  queues for `pin_rev: null` (`kid_lock.rs:101`).
- Scenario: the process dies between `resetPasswordWithToken` and the rev's commit. The broadcast then reaches a new
  process with no flag, and the rev becomes `null`, not the older rev that deviation 1 expects the server to heal. The
  same happens when two own resets come within one broadcast delay: a boolean can't count two.
- Result: Android holds the verified current PIN. The command row has no answer, and step 2's card says "It was
  probably changed on the phone". Only "send again" or a new save fixes it. This is not a security issue.
- Fix: make the flag a counter, or a CE marker written ahead with the owned flag. The marker would hold the rev being
  set, and `onPasswordChanged` with a marker adopts that rev. Correct deviation 1's text either way.

**S4-2 (Low) [device]: a command that lands while our lock is LOCKED and dark makes the first wake blink off and counts
a relock.**
- Evidence: at the last screen-off Android's keyguard was disabled (own mode, no credential). The sync's apply turns the
  face on (`AndroidLock.face`), but nothing locks Android. The sleep check runs only 1 s after a screen-off
  (`PinLockRuntime.kt:852-860`).
- Scenario: this is the common case, the phone in a pocket. At the next wake the glance resumes and Android is not
  locked. No `USER_PRESENT` comes: AOSP's `maybeSendUserPresentBroadcast` needs the lock screen disabled, and a
  credential overrides that. So the glance waits 1 s and RELOCKs. The screen goes off under the kid's thumb, the fingerprint waits for the PIN, and step 2's card
  shows "Relocked 1". This is not a leak: there is no LEAVE without the broadcast or the pad.
- Fix: after `pin set`, when our mode is LOCKED and the screen is off, call `dpm.lockNow()` without counting it. §8
  step 8 ("the command with the screen off") confirms it.

**S4-3 (Low): the "never logged" scan misses the PIN's last hop and multi-line calls.**
- Evidence: the test scans only lines that contain `Log.` in `MdmSyncWorker.kt`, `AndroidLock.kt` and
  `PinLockRuntime.kt`.
- Two mutations survived the whole suite:
  - M11: `Log.i(LOG_TAG, "reset to " + pin)` in `WindowRelock.resetCredential`, which receives the PIN.
  - M16: `Log.i(\n TAG, "... $pin")` in `applyPinCommand`.
- Fix: add `WindowRelock.kt` and `PinHash.kt`. Scan each `Log.`/`log(` call up to its closing parenthesis. Also ban
  `pin` in string concatenation (`+ pin`), not only in templates.

**S4-4 (Low): the glue's `requested = androidRequested(kidLock)` is not pinned.**
- Evidence: M13 replaced it with the applied `this.requested`, and the targeted tests passed.
- Scenario: the parent's switch to android queues the row, so the first command comes in the same answer as
  `keyguard: "android"`, before any apply. With the applied flag it would answer `own lock`. That is a success and
  "answered", so the intake never queues again, and the phone migrates only at the kid's next keypad unlock.
- Fix: pin the line by text in `applyPinCommand`'s body, next to the `verifyCommandPin(kidLock, it)` pin.

**Nits**
- N1: `setFromCommand` ignores the final `saveAndroid(rev)` commit (`AndroidLock.kt:233`). A failed commit answers
  `pin set` with the old rev stored, which leaves a stale rev with an answered row and no heal. Log it, or answer
  `refused: failed`.
- N2: `report` takes the relocks, like `exempt_yields`, while building the body. A failed POST loses them. This is
  acceptable as a counter, but the KDoc could say so.

## Mutations (each alone, `testDebugUnitTest --tests AndroidLockPlanTest PolicyResponseCompatTest PowerEventsTest`; M11/M16 the whole suite; reverted)

| # | Mutation | Result |
|---|---|---|
| M1 | dispatch passes the applied policy's `kid_lock` instead of the fresh one | killed (Q59-1 test) |
| M2 | `cacheablePolicy` keeps the PIN | killed (PolicyResponseCompatTest) |
| M3 | the fresh rev written ahead of the reset | killed (write-order test) |
| M4 | no exact-length check in `commandPinVerifies` | killed (Q59-1 test, "another length") |
| M5 | no give-back after a failed reset | killed (write-order + rollback tests) |
| M6 | `keyguard: String = "own"` (default dropped by ServerJson) | killed (lock_state keys) |
| M7 | `withUnlock` stamps again over a stamp | killed (PowerEventsTest) |
| M8 | capability dropped from `STATUS_CAPABILITIES` | killed (Q59-13 test) |
| M9 | the token asked before the PIN verifies | killed (answers test) |
| M10 | relocks read, not reset | killed (gate test) |
| M11 | the PIN logged in `WindowRelock.resetCredential` | **survived** (S4-3) |
| M12 | the android rev reported for a credential not ours | killed (gate test) |
| M13 | `requested = this.requested` (applied) in `applyPinCommand` | **survived** (S4-4) |
| M14 | the dispatch returns the command with its PIN | killed (strip pin) |
| M15 | `lockAnswer`: `ours` before the android gate | killed (remote lock test) |
| M16 | the PIN in a two-line `Log.i` in `applyPinCommand` | **survived** (S4-3) |
| M17 | `withUnlock` stamps on any CE start | killed (PowerEventsTest) |

Held, as specified: the cache strip runs before `storeAcceptedPolicy` writes. The command never runs from the cache.
The token is set for escrow only through `ensureAndroidResetToken`. 24b's step 1 is skipped as soon as the write-ahead
lands. `gateNow` and `report` read CE defensively (unreadable = not ours).
