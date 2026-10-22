# QA (code): design 59 step 1 - the contract, the sealed Child PIN and `set_kid_pin`

Branch `design-59-step1`, commit 2dd80531 (on dd45247c). Server 0.37.0 (migration 0085) and the launcher's DTOs.
Reviewed 2026-10-22 against `docs/design/59-android-keyguard-design.md` (§1, §2.1, §6, §7, "Step 1 as built"),
`qa-59-design.md` and `qa-59-revision-check.md`.

## Summary

The step is sound. The PIN leaves the server in plaintext in one place only: the `pending_command.pin` of one policy
answer. That answer is `no-store`, and the field is filled only behind three gates (the command is `set_kid_pin`, the
keyguard is android, the last report has the capability). The sealed copy is bound to its device row, a missing key
never touches the lock, and the launcher drops the PIN before it caches or applies the policy. M-2's prev semantics
are right, including the legacy, B, C sequence. The pop rewrite matches the old one exactly.

One real gap comes from the L-2 deviation ("any answer blocks the intake"). A save can race a poll that is already in
progress. The row then goes out carrying the older PIN, the phone answers `pin set` for it, and the intake can no
longer heal the stale rev. The newer PIN is lost without anything saying so (S59-1, shown by a probe test). The same
rule never heals a phone re-enrolled into the row (S59-2). Right after a re-enrollment, the delivery gate still reads
the previous install's capabilities (S59-3).

Runs: server `cargo test` 1144 passed, `cargo fmt --check` clean. Launcher `testDebugUnitTest -PwarningsAsErrors=true`
passed (1514 tests, `PolicyResponseCompatTest` 42/42). Mutations: 12 run, 11 killed, 1 equivalent (table below).

Counts: High 0, Medium 1, Low 2, Nit 4.

## The deviations

| # | Deviation ("Step 1 as built") | Verdict |
|---|---|---|
| D1 | L-2: the intake also stops once the newest row has **any** answer, not only a refusal | Right as a loop guard (an unmanaged phone keeps reporting `own` after `pin set`), but too wide: it also blocks the stale-rev heal when the answered row carried an **older** PIN (S59-1) or came from an earlier install (S59-2). Narrow it. |
| D2 | No `Cache-Control` of its own on the policy; `limits` makes every device answer without one `no-store` | Accepted. `limits::device_cache_control(None, _)` = `no-store` (`limits.rs:412`), and `set_kid_pin_carries_the_pin_only_where_it_may` pins the header on the answer with the PIN. |
| D3 | A popped `set_kid_pin` carries the PIN only while the keyguard is still android | Accepted; this is §2.1's delivery rule. M2 is killed. |
| D4 | The launcher strips the PIN for the cache (`cacheablePolicy`) and from the policy applied after the dispatch | Accepted. `cacheablePolicy` returns the same object when there is no PIN; the source test pins both call sites (M11 and M12 are killed). Before the dispatch the PIN is held in memory, which can't be avoided. |
| D5 | `LockStateReport` and the stamping of `unlockedAtMs` stay in step 4 | Accepted; §6 step 4 lists them. The DTO field exists and is pinned by `power_report.json` (byte-identical copies). |

M-1, N-B and N-C are implemented as written: the contract names no launcher flag, the clear cancels the waiting row
(`kid_pin_cleared`), and both `lock_state` fields are kept typed.

## Checks that held

- **Queue SQL** (`kid_lock.rs:82-118`): 8 binds for `?1..?8`, and reusing a numbered parameter inside subqueries is
  fine in SQLite. Unqualified `kid_pin_rev` sits only in the outer `WHERE`, whose sole table is `device_policy`. In the
  subqueries, `device_id` resolves to the innermost table that has it. The capability test is
  `json_each` over the newest `device_status` row, which matches `last_capabilities`'s ordering. A NULL `?6` makes
  `?6 != kid_pin_rev` NULL anyway, so `?6 IS NOT NULL` is redundant but harmless. The rule is one `INSERT ... SELECT`
  and SQLite serializes writers, so a save and a report can't both insert.
- **Pop** (`device_api.rs:353-387`): `models::COMMAND_OPEN` is, word for word, the old
  `received_at IS NULL AND acknowledged_at IS NULL AND cancelled_at IS NULL`, in the same position, with the binds
  unchanged. `last_capabilities` and the `receipts` derived from it are equivalent to the old `sends_command_receipts`.
- **Save** (`devices.rs:2286-2320`): in an UPDATE, every `SET` expression reads the old row, so both CASEs are keyed
  on the old `prev_rev`. The legacy case becomes (prev NULL, rev 0) and stays that way through C, and a confirmed
  sealed PIN survives two saves (M4 is killed). The UPDATE runs first and the queue second, in one write transaction.
  On `NeedsOverride` (0 rows) the queue is skipped and the transaction is dropped. Every other refusal returns before
  the transaction. The clear writes and cancels in one transaction (M8 is killed).
- **Intake** (`device_api.rs:1203-1219`): it acts on the report's own capabilities. If the status INSERT failed
  (errors are ignored), the stored "last report" can be an older one, and the SQL gate then reads that row: no row is
  queued, or the queue follows the previous report. Neither case leaks anything.
- **PIN exposure**: every `tracing` call in the diff takes only `device_id`/`%err` (`deliverable_pin`'s warn, the
  intake's error, the save's error). `KidPin` has a redacted `Debug`, and `form` is never logged. `DevicePolicy` is
  not `Serialize`, `build_policy` has one caller, and `KidLock` carries no sealed field. The test asserts the sealed
  text is absent from the policy.
- **Key and AAD**: `data/keys/kid-pin.key` lives under `server/.gitignore`'s `/data`. Backups are `VACUUM INTO` of the
  database alone, so a backup holds the sealed text without the key. A mangled key is refused, not replaced, and the
  error leaves `kid_pin_key = None`: the save still writes the hash. The AAD binds both the fingerprint and the device
  id, so a value copied into another row opens to `None` (M3 is killed, and so is the delivery test's foreign-row
  case). The AAD doesn't bind the rev, so swapping prev into current within a row needs database write access, which
  is outside the threat model.
- **Compatibility**: 0085 only adds columns with defaults. Old launcher + new server: the new keys are ignored, and
  no row is queued without the capability. New DTOs + old server: `keyguard` null = own, `pinRev` 0.

## Findings

**S59-1 (Medium) A save racing a poll loses the new PIN, and the "any answer" rule stops the heal.**
`build_policy` reads `device_policy` at `device_api.rs:230` and pops at `:353`, with a dozen queries in between. A
save that commits in that gap still sees the row as waiting, so it queues nothing (`kid_lock.rs:95`). The pop then
hands that row out with the PIN opened from the earlier read (`:421`). That PIN is consistent with the answer's hash,
as designed, but it is the older PIN. The phone answers `pin set` and reports the older `pin_rev`. The intake sees a
stale rev, but the newest row is answered (`kid_lock.rs:100`), so nothing is queued. Android keeps the older PIN
while the server's hash and reveal move on, until the parent presses "Send again" (step 2). This breaks §2.1's
promise that no save is lost.
*Evidence:* a probe test (not committed) saved 2468 while a row waited, popped it, set the row to rev 3 with a new
sealed PIN as the racing save would, answered `pin set`, then reported `android`/rev 2. Still one row.
*Fix (either one):*
- (a) The pop skips a `set_kid_pin` when `device_policy.kid_pin_rev` no longer equals the rev it read
  (`AND (command != 'set_kid_pin' OR (SELECT kid_pin_rev FROM device_policy WHERE device_id = ?) = ?)`). The row stays
  waiting, and the save's nudge brings the next poll.
- (b) Write the delivered rev into the row at the pop, and let an answered row block the intake only when its rev is
  the current one.

Add the probe as a test.

**S59-2 (Low) A phone re-enrolled into the row is never healed by the intake.**
`end_pending_commands` (`device_api.rs:1504`) cancels only undelivered rows, so the previous install's answered
`set_kid_pin` stays the newest row. Take a factory-reset phone re-enrolled while the keyguard is android. Its new
launcher reports the capability with `keyguard: own`, and `kid_lock.rs:98-101` finds the answered row and queues
nothing. The phone stays on Vibb One's lock (safe, but not what the parent chose) until the parent presses "Send
again" or saves a PIN.
*Fix:* in the newest-row test, count only rows requested at or after `devices.enrolled_at` (this fits with S59-1 (b)).

**S59-3 (Low) Until a re-enrolled phone's first report, the delivery gate reads the previous install's
capabilities.** The launcher fetches the policy before it posts status (`MdmSyncWorker.kt:100` vs `:212`), and
`last_capabilities` (`device_api.rs:1622`) and the queue SQL take the newest `device_status` row whatever the
enrollment. A save or switch between the enrollment and the first status report queues a row (the old install was
capable), and the first poll delivers `pin` to whatever launcher the new install runs. An older launcher drops the
unknown key and answers "unknown command", so this is exposure in one TLS answer, not a stored leak. It contradicts
§1's "a row's `pin` is filled only for such a phone".
*Fix:* both gates read only reports with `reported_at >= devices.enrolled_at`.

**N-1** The first save without the key shows `saved_safe_boot` (`devices.rs:2325`) rather than `saved_not_kept`, so
the "not kept" notice is lost on exactly the first PIN. Combine the two notices, or let `saved_not_kept` win.

**N-2** A delivered `set_kid_pin` that a later pop retires (`deliveries` maxed, `cancelled_at` NULL,
`device_api.rs:393`) still counts as in flight at `kid_lock.rs:100` for up to 15 minutes, so the intake heal waits
that long. Count `deliveries >= MAX_COMMAND_DELIVERIES` as not in flight.

**N-3** In `set_kid_pin_carries_the_pin_only_where_it_may`, "Confirmed: never handed out again" (`kid_keyguard.rs:386`)
is vacuous, because the next fetch comes before `REDELIVER_AFTER_MINUTES`. M7 (the pop ignoring `received_at`)
survives it and is killed only by `command_receipts`. Backdate `delivered_at` before the fetch.

**N-4** `server/DEPLOY.md:270` still calls the keys volume "the Storytel and Web Push keys". Name `kid-pin.key`, and
say that losing it means setting each Child PIN again. This could go with step 2's privacy text.

## Mutations

| # | Mutation | Test | Result |
|---|---|---|---|
| M1 | `deliverable_pin` without the capability gate | `kid_keyguard::set_kid_pin_carries_the_pin_only_where_it_may` | killed |
| M2 | `deliverable_pin` without the android-mode gate | same | killed |
| M3 | AAD without the device id | `kid_pin::tests::a_sealed_pin_opens_only_for_its_phone_and_key` | killed |
| M4 | prev = `COALESCE(kid_pin_sealed, prev)` (the pre-M-2 form) | `kid_keyguard::saves_seal_the_pin_and_keep_the_one_the_phone_confirmed` | killed |
| M5 | intake ignores "in flight" | `kid_keyguard::a_status_report_sends_the_pin_to_a_phone_that_needs_it` | killed |
| M6 | intake ignores an answered row | same | killed |
| M7 | pop condition without `received_at` (differs from `COMMAND_OPEN`) | `command_receipts::an_unconfirmed_command_is_offered_again_to_a_phone_that_confirms` (the keyguard test misses it: N-3) | killed |
| M8 | clear doesn't cancel the waiting row | `kid_keyguard::clearing_forgets_the_pin_sets_own_and_cancels_the_waiting_command` | killed |
| M9 | queue ignores a waiting row | `kid_keyguard::a_save_queues_set_kid_pin_only_for_a_phone_that_can_take_it` | killed |
| M10 | `after_lock_report` without the `capable` early return | `kid_keyguard` | survived, equivalent: the SQL gate reads the same non-capable report, and an older launcher sends no `pin_rev`/`keyguard` |
| M11 | launcher: the policy after the dispatch keeps the PIN | `PolicyResponseCompatTest` (59) | killed |
| M12 | launcher: `cacheablePolicy` returns the policy unchanged | same | killed |

Every mutation was reverted with `git checkout`. The probe for S59-1 was reverted too.
