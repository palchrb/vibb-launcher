# QA 38 (code): phone off and on notifications

Code QA, 2026-10-19. Under review: `ad8f4821` (branch `phone-power`, one commit on `b340aab5`), server and launcher. I
read it against `docs/design/38-phone-off-on.md` (§2, §3.3, §4, §8 and its "Implementation status" with the five
deviations), the design 38 sections of `server/CLAUDE.md` and `launcher/CLAUDE.md`, and
`docs/notes/server/notifications.md` (design 35's mechanics). Line numbers are `ad8f4821`'s. No product code was
changed. Every mutation was reverted, and the tree was clean before this file was written.

**Verdict: sound, merge after a small fix round.** The model holds:
- the shutdown is written ahead and posted within 3 s, and the keyguard and boot cover still arm first;
- the boot's start is recorded once per boot, in the main process only;
- a sent shutdown is kept and resent with the next start, so the server pairs the two in one body;
- the server holds "turned off" for 5 minutes, and a restart's start drops it;
- a resent body raises nothing new;
- the sender claims a row before it sends it, so a dropped row never goes out.

What has to change:
- **M1**: the device page's "Off since" line stays while the phone is reporting, whenever the start that should end
  it never arrives.
- **L1**: three test gaps. One launcher mutation survives (`commit()` → `apply()`), and the power route's own test
  doesn't exercise the battery's level range.
- **L2**: the branch has three merge conflicts with `main`, one of which is a content decision, not mechanical.

Counts: **0 High, 1 Medium, 2 Low, 3 nits.** Mutations: **16 of 17 caught.**

## Runs (all local, under `flock ~/build.lock`, ~2-4 GB free)

| Check | Result |
|---|---|
| server `cargo test -q -j 2` | ok, **1043 passed**, 0 failed |
| server `cargo fmt --check` | clean |
| server `cargo clippy --all-targets -q` | 13 warnings, the known ones; no new warning |
| server `node --test jstest/` | not run: no JS changed |
| launcher `./gradlew --no-daemon --max-workers=2 testDebugUnitTest assembleDebug` (tsnet stub; no `tsnet.aar` on the VM) | BUILD SUCCESSFUL, **1371 tests**, 0 failed, 0 skipped (`PowerEventsTest` 13) |

Nothing went through CI.

## Critical paths: what I checked in the code

1. **The shutdown receiver** (`BootCover.kt:94-127`, `PowerEvents.kt:323-348`).
   - It is the process's only `ACTION_SHUTDOWN` registration: one `IntentFilter(Intent.ACTION_SHUTDOWN)` in the
     sources. It is registered unconditionally from `PinLockRuntime.init` (`:504`).
   - `coverAtShutdown` is the old body moved verbatim: `KeyguardCover.onShutdown`, `shuttingDown`, the guard, the
     synchronous `setEnabled` and the `KEY_ARMED_AT` commit. The arming is unchanged. The only difference is that a
     throw is now logged instead of crashing the process mid-shutdown; in both cases the arm after the throw doesn't
     happen.
   - `PowerEvents.onShutdown` runs next, in its own `try`. It reads the property, the battery and `BOOT_COUNT`,
     `commit()`s the record under the lock, and only then calls `goAsync()`. The POST runs on `power-shutdown`, and
     `finish()` sits in a `finally`, plus a catch for an executor refusal.
   - Nothing on the main thread waits for the network: the lock is never held across a POST, and the prepared
     client is read through a `@Volatile` field.
   - Mutation L4 (the parts swapped) is caught.
2. **The prepared client** (design QA N1).
   - `proxyAddress` is written only in `TsnetClient.connect` and `close`, and both now call `proxyChanged(before)` →
     `proxyListener` → `prepareAsync`. `PowerEvents.init` (from `initRest`) sets the listener, then prepares once,
     so a connect that finished before the listener existed is still picked up.
   - `performMdmSync` calls `PowerEvents.prepare` right after `connectFromPreferences`, which also covers a new
     enrollment's URL and token.
   - The SOCKS credential lives in the process-wide `Authenticator`, which `connect` refreshes, so a same-address
     reconnect needs no rebuild.
   - Mutations L5 (the proxy left out of the key) and L7 (`connect` doesn't notify) are both caught.
3. **The record's lifecycle.**
   - Write-ahead off with `off_sent = false`.
   - A 2xx marks the off sent. It is resent with each start until a start of a later boot is accepted (`clearSent`).
   - `stale` drops parts after 7 days.
   - `recordBoot` runs after `BootWitness.record`, after the `:bootcover` return. It needs `newBoot`, `hadLastBoot`
     and a count of 0 or more.
   - The answer table is `pinEventsLeaveQueue` as is: 401/404/408/429/5xx/none keep the record, and any other 4xx
     drops what was sent.
   - A start without network is retried by `sse_open` (`CommandListenerService.kt:235`), else by the backstop.
   - Mutations L1, L2, L3, L6, L9 are caught. L8 (`apply()`) survives (L1 below).
4. **The server's state machine** (`parent_events.rs` `power_events`, `device_api.rs:2028-2120`).
   - Restart is decided by one boot apart plus (`reboot: true`, or unknown and a gap of 3 min or less). It drops the
     off's queued deliveries with reason `restart`, scoped by device and the off's key.
   - Turned on with its off: "off since …", and the known off's never-tried held push is released (deviation 1).
   - Alone: the off is held for `OFF_HOLD`; the on says "no shutdown seen" with the newest `device_status` before
     the boot. Both `reported_at` and the bound value use `db_time`'s format, and the new boot's own report is
     excluded by the `<`.
   - Dedup keys: `phone_off` = the instant, `phone_on` = `boot:<enrolled_at>:<count>`. A resent body changes nothing
     and doesn't wake the sender.
   - The hold is `next_try_at`. `send_due` already skips the row until then, and `run_sender` sleeps until
     `MIN(next_try_at)`. Other kinds' rows are untouched. The TTL counts from `created_at`.
   - `claim` is `WHERE id = ? AND status = 'queued'`, and a refused claim counts no attempt.
   - `delivery_line` drops `restart` drops before the cases, and health ignores them.
   - Folding is unchanged: a released off and its on are both normal and sorted by id, so the off goes first.
   - Switches: `push_phone_off`/`push_phone_on`, default 1, listed and saved through `Kind::ALL`/`switch_names`.
     `strip_secrets_in` is unaffected.
   - The route sits behind the bearer middleware (401 tested), has its own 2 KiB `DefaultBodyLimit` (413 tested),
     returns 400 for no part or a non-object, and reads leniently: a bad battery or reason costs only itself, and a
     future or too-old instant drops that part only.
   - Mutations S1-S8 are all caught.
5. **The contract.**
   - `cmp` confirms `server/testdata/power_report.json` and `launcher/app/src/test/resources/power_report.json` are
     byte-identical.
   - Both sides compare the copies. The launcher encodes exactly the file, and the server posts it with its instants
     moved (deviation 2).
   - The root `CLAUDE.md` line and both CI filters (`launcher.yml`, `server-ci.yml`) are in. `music.yml` is rightly
     untouched.
   - `migrations_73_to_80_in_order` is updated, and `main` still ends at 0079, so 0080 is free.
6. **Privacy and escaping.**
   - Titles are the stored name plus a fixed phrase.
   - Summaries are server-made: times, a level and fixed words. `reason` is matched against known words
     (`off_reason_words`) and never stored or shown; the `<b>x</b>` case is tested.
   - The "Off since" line is rebuilt from the stored summary's digits and goes through askama's escaping. Nothing a
     phone sends reaches the page as text.

## Findings

### Medium

**M1. "Off since …" stays on the device page while the phone is plainly on, whenever its start never arrives.**
- Location: `server/src/handlers/devices.rs:1384-1394`. The line shows while the newest `phone_off` is later than the
  newest `phone_on`, and nothing else ends it.
- Cases where a phone comes back without a `phone_on`:
  - re-enrollment of the same device row after a factory reset or a reinstall. The enroll `UPDATE` keeps the row, and
    the new install has no `last_boot`, so `hadLastBoot` is false and no start is recorded;
  - a phone whose `BOOT_COUNT` reads -1, which records shutdowns but never starts;
  - a start the server dropped: its instant more than 5 minutes ahead, or a 4xx.
- What the parent sees: the header says "Off since 21:14 · battery was 3 %" under a fresh battery line, until the
  phone's next restart.
- Fix: also require that no `device_status` row is newer than the off's `happened_at`. A report after the shutdown
  proves the phone is on. This is one more condition in the same query: `AND datetime(o.happened_at) >= COALESCE((SELECT
  MAX(reported_at) FROM device_status WHERE device_id = ?1), '')`. The `datetime()` is needed because the two
  columns use different text formats: `happened_at` is `instant()`'s RFC 3339 `…T…Z`, while `reported_at` is
  `datetime('now')`'s `… …`, so a raw string compare goes wrong within a day.
- Test: post an off, then a status report, and assert no `data-power-off`.

### Low

**L1. Test gaps.**
- (a) `PowerEvents.update`'s `commit()` (`PowerEvents.kt:268`) is unpinned. Mutation L8 (`apply()`) passes every
  test. At the shutdown that matters: an `apply()` queued in a runtime receiver isn't waited for. Add a source pin
  that `update` uses `editor.commit()`, next to the existing "record committed before goAsync" pin.
- (b) The power route's own test doesn't reach the battery's level range. `the_route_refuses_what_isnt_a_power_report`
  posts `{"level": 140}`, which fails deserialisation for its missing `charging`/`low` before the range is checked.
  Mutation S6 (no range check) is caught only by the shared status test `notifications::the_battery_latches_once`.
  Post `{"level": 140, "charging": false, "low": false}` instead.
- (c) Optional, and older than this step: no source pin covers the boot cover's
  `setEnabled(app, armed, "shutdown", synchronous = true)` inside `coverAtShutdown`. Design 38 moved it, and
  `KeyguardCoverPlanTest` pins only the `KeyguardCover.onShutdown` call.

**L2. Merging into `main` gives three conflicts.**
- `launcher/.../StatusCapabilities.kt` (design 37's `INSTALL_NOW_CAPABILITY`): keep both entries, and run
  `SyncScheduleTest`.
- `docs/testing/emulator.md`: §6o is design 37's and §6p is this step's, so keep both.
- `server/CLAUDE.md`: `main` split it on 2026-10-19 (`b471096d`), and the area notes now live in
  `docs/notes/server/`. The new "The phone turned off and on" section belongs in `docs/notes/server/notifications.md`.
  The design 38 edits to the clock-format and migration lines must go to wherever those lines now live, and
  `server/CLAUDE.md` gets only the core's route/table lines.

### Nits

1. During the ~2 minutes between a restart's off and its start (the off's `reboot: true`, inbox "Anna is
   restarting"), the device page reads "Off since 14:02". It is correct but worded as a power-off. "Restarting since"
   would match the inbox, but this is optional.
2. A body with an absurdly old `on.at_ms` (e.g. 0) is accepted, giving "turned on · 1 Jan 1970". Only an `off` has an
   age limit. The launcher never sends this, so it is lenient by design. A 30-day floor like the off's would be
   symmetrical.
3. Two shutdowns before one start gets through: off(41) sent and held, start 42 unsent, then off(42) replaces off(41).
   This leaves off(41)'s held push to go out as a stray "turned off" before boot 43's "restarted". It is an edge of
   the one-record design, and the class doc already says a replaced record is lost.

## Mutations (each one reverted)

Server mutations ran as the full `cargo test`, in two batches. Launcher mutations ran as `testDebugUnitTest` on
`PowerEventsTest` and `KeyguardCoverPlanTest`, in two batches. Independent mutations were batched so that each one
trips a test the others in its batch don't.

| # | Mutation | Caught by |
|---|---|---|
| S1 | the hold is 0 (`Push::Held` → `Push::Now`) | `parent_events::tests::the_power_words`, `the_power_state_machine`, `tests::power::a_restart_drops_the_held_off_push` |
| S2 | no restart fold (`restart = false`) | `the_power_state_machine`, `power::a_restart_drops_the_held_off_push`, `a_known_restart_pushes_only_the_start` |
| S3 | the `phone_on` key without `enrolled_at` | `power::a_start_without_a_shutdown_switches_and_re_enrollment` (and `parent_events::tests::the_words_and_targets` in the batch) |
| S4 | `claim` without `AND status = 'queued'` | `power::the_sender_claims_only_a_queued_row` |
| S5 | "probably flat" at any level | `the_power_words` |
| S6 | a battery level outside 0..=100 accepted (`lenient_battery`'s range) | `notifications::the_battery_latches_once` only (L1 b) |
| S7 | no release of the known off's held push (deviation 1) | `power::a_quick_start_after_a_power_off_sends_the_off_first` |
| S8 | `delivery_line` counts a `restart` drop | `notifications::tests::the_delivery_line` |
| L1m | a sent off is not resent with the start (`off_sent` resend dropped) | `PowerEventsTest` "the kept shutdown goes with the next start…" |
| L2m | another 4xx keeps the record forever | "the answer table is pinEventsLeaveQueue's" |
| L3m | a 5xx drops the record | "the answer table…" |
| L4m | the POST before the cover's writes (receiver parts swapped) | "one shutdown receiver, the cover's writes first, each part caught" |
| L5m | the proxy left out of the client key (no rebuild on a proxy change) | "the prepared client is rebuilt when…" |
| L6m | a start recorded at boot count -1 | "a boot is recorded only for a new boot…" |
| L7m | `TsnetClient.connect` doesn't call `proxyChanged` | "the boot record, the upload and the prepared client are wired" |
| L8m | `update` uses `apply()` instead of `commit()` | **survives** (L1 a) |
| L9m | `clearSent` drops a newer off written meanwhile | "an answer keeps what was written meanwhile" |

**Score: 16 of 17 caught.** The pure rules on both sides are well pinned, and so is the glue's order.

## The implementer's deviations

1. **A quick start after a real power-off releases the held off**: **right**. Without it, "turned on" would arrive
   before "turned off". The release only touches never-tried queued rows (`attempts = 0 AND next_try_at IS NOT
   NULL`), so a retry keeps its backoff. The sender then sends by id, off first (`send_due`'s sort keeps id order
   within normal urgency). Tested (S7).
2. **The contract test moves the body's instants**: **right**. The file stays exactly §2's body, and the gap is kept,
   so the pairing is the same.
3. **The late suffix also on a restart's new off row**: **right**. That row first reached the server with the start
   and is unpushed either way, and the suffix says so in the inbox.
4. **A kept off of the start's own boot or later is not sent with that start**: **right**. The server would drop it
   as later than the start. The consequence is that boot N's unsent start can be lost when boot N's shutdown exists.
   It is then covered by boot N+1's "turned on · off since …", which pairs with that off. This is documented in the
   class doc and `launcher/CLAUDE.md`.
5. **`clearSent` matches the start by boot count only**: **right**. The sent instant is recomputed at upload, so it
   can differ from the stored one. Within one install, another start always has another count. A factory reset
   restarts the count but also wipes the prefs.

## Owed to device checks

- The emulator run of `docs/testing/emulator.md` §6p, checks 1-4: `svc power shutdown` / `svc power reboot` with
  tsnet on, the server stopped across a shutdown, and `adb emu kill`. In check 1, also look at M1's page line after
  the start.
- On the Jelly Star:
  - the power menu's Power off and Restart;
  - a real flat battery (is the broadcast sent, and at which level?);
  - whether `sys.shutdown.requested` is readable (expected not);
  - Restart timed to the start's arrival on Wi-Fi and on mobile data against `OFF_HOLD`. If it's slower, raise the
    hold, not the gap;
  - the shutdown POST over tsnet inside 3 s on mobile data.
