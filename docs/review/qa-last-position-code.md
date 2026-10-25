# QA (code): a last position at shutdown and at low battery

Code QA, 2026-10-25. Under review: branch `last-position` at `6772f229`, three commits on `main` (`0643fd82` the
shutdown's fix, `c39d04ac` low battery, `6772f229` docs), launcher and server. Read against PLAN's queue line "A last
position at shutdown and at low battery" (the user's scope), `docs/design/38-phone-off-on.md` (§2, §4, and the new
"Last position (2026-10-25)" section with its deviations), design 35's battery edge, and design 64's mock rule and
`zones::intake`. Line numbers are `6772f229`'s. No product code was changed. Every mutation was reverted, and the
tree was clean before this file was written.

**Verdict: sound, merge after a small fix round.** The scope is built as decided:
- **The shutdown sends a stored fix only.** The `off` carries the newest fix the phone already holds
  (`LocateCommands.newestKnown`). It never takes a fresh one. It sends nothing under "Off", without a policy, or for a
  mock fix.
- **The fix stays out of DE storage.** It is kept in CE prefs with `commit()` before `goAsync`, and the DE record is
  untouched. At upload it is joined back to its own shutdown by instant and boot.
- **The server dedupes it.** It checks the fix the same way it checks a status report's, then stores it through
  `zones::intake`, so a fix the phone already reported stays one row. It then marks that row `shutdown_at`.
- **Locate's line and the footer sentence are in**, and the line goes away after the next report.
- **Low battery takes one balanced fix per episode.** It is taken in the edge's own sync, with a 30 s cap, never
  under "Off", and never with the Location switch off.
- **No coordinates appear in any text** (logs, push, inbox).

What has to change:
- **Q-1**: migration number 0097 collides with `pwa-fixes-b`'s `0097_push_words.sql`.
- **Q-2**: the code around the low-battery episode is untested. Three mutations survive the whole launcher suite,
  and each one allows one active fix per `ACTION_BATTERY_LOW` broadcast. That episode check is the battery-drain guard.
- **Q-3**: no test covers `retention::prune` clearing a `phone_off`'s position. That mutation survives the whole
  server suite. A scratch test written during QA showed the SQL works.

Counts: **0 High, 3 Medium, 4 Low, 4 nits.** Mutations: **13 of 19 caught** (launcher 7/10, server 6/9).

## Runs (local, under `flock ~/build.lock`, no emulator up)

| Check | Result |
|---|---|
| server `cargo test` | ok, **1314 passed**, 0 failed |
| server `cargo fmt --check` | clean |
| server `cargo clippy --all-targets` | the 13 known warnings (`dns_engine.rs`, `models.rs` dead fields, `devices.rs`, `storytel.rs`), none in new code |
| launcher `./gradlew -PwarningsAsErrors=true assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL, **1786 tests**, 0 failures |
| `cmp server/testdata/power_report.json launcher/app/src/test/resources/power_report.json` | identical |
| scratch retention test (QA only, not committed, see Q-3) | passes on the branch |

## Findings

### Q-1 (Medium): migration 0097 collides with `pwa-fixes-b`

`server/migrations/0097_shutdown_fix.sql`. Branch `pwa-fixes-b` (in build) has `server/migrations/0097_push_words.sql`.
Two migrations with the same version break `MIGRATOR` at start-up.

**Fix (coordinator, at the merge):** renumber whichever branch merges second to 0098. That covers:
- the file name;
- `music_spotify::migrations_73_to_97_in_order` (rename it and add the tuple);
- `server/CLAUDE.md` ("next free number");
- the migration's own comment, design 38's "Last position" section, `docs/notes/server/notifications.md` and PLAN's
  Built line, all of which say "0097".

### Q-2 (Medium): the low-battery episode's code is untested, and it is the drain guard

The pure rules are tested: `lowBatteryFixDue`, `withLowBatteryFix` and `lowEpisodeAfterReading`. The code that feeds
them is not, and each of these mutations passes the full launcher suite (1786 tests):
- **L3**: `take` passes `false` for `taken`. The fix is then taken at every LOW edge, not once per episode.
  `LowBatteryFix.kt:80`.
- **L6**: `ACTION_BATTERY_OKAY` no longer ends the episode (`LowBatteryFix.kt:60-63`). Here `observe` happens to
  cover it, so the impact is small.
- **L9**: `observe` ends the episode on any reading (`if (taken && battery != null)`). The next LOW broadcast then
  takes another fix. `LowBatteryFix.kt:95`.

L3 and L9 are exactly the battery drain the episode exists to stop. Android resends `ACTION_BATTERY_LOW` every time
the phone is unplugged at a low level (`BatteryService.shouldSendBatteryLowLocked`: `oldPlugged`). With the
mutations, plugging and unplugging at 14 % costs one active fix each time.

**Fix:** pin the code around the rules, either with pure steps or with source pins in `LowBatteryFixTest`:
- `lowBatteryFixDue(true, taken, deviceOwner, policy, systemLocationOn)` inside `take`;
- `if (taken && !lowEpisodeAfterReading(taken, battery)) endEpisode(context)` inside `observe`;
- `Intent.ACTION_BATTERY_OKAY -> {` followed by `endEpisode(context)` inside `onBatteryEdge`.

A small pure `episodeAfterEdge(action, taken)` would make the OKAY case a value test.

### Q-3 (Medium): the retention of the shutdown's position is untested

`server/src/retention.rs:154-166`. Turning that `UPDATE` into a no-op (`SET params = params, detail = detail`)
passes the full server suite (1314 tests). The brief asked for this rule to be tested. A scratch test written during
QA passes on the branch, so the SQL itself is right. Proposed, for `src/tests/power.rs`:

```rust
/// The off's position goes with the location retention; the event stays its 90 days.
#[tokio::test]
async fn the_shutdowns_position_goes_with_the_location_retention() {
    let (app, id, token) = setup().await;
    let now = Utc::now();
    let location = json!({ "lat": 59.9139, "lng": 10.7522, "accuracy_m": 25.0,
                           "at_ms": ms(now - Duration::minutes(10)), "provider": "fused" });
    let body = json!({ "off": off(now - Duration::minutes(1), 41, json!({ "location": location })) });
    assert_eq!(post(&app, &token, body).await, StatusCode::NO_CONTENT);
    sqlx::query("UPDATE parent_events SET created_at = datetime('now', '-8 days') WHERE kind = 'phone_off'")
        .execute(&app.db).await.unwrap();
    sqlx::query("UPDATE device_policy SET location_retention_days = 14").execute(&app.db).await.unwrap();
    crate::retention::prune(&app.db).await.unwrap();
    assert_eq!(off_params(&app, id).await.0["location"]["lat"], 59.9139);
    sqlx::query("UPDATE device_policy SET location_retention_days = 7").execute(&app.db).await.unwrap();
    crate::retention::prune(&app.db).await.unwrap();
    assert_eq!(off_params(&app, id).await, (Value::Null, None));
    assert_eq!(events(&app, id).await.len(), 1, "the event stays");
}
```

### Q-4 (Low): an off the plan drops still has its fix stored (untested)

`server/src/handlers/device_api.rs:2265`. The code is correct: the fix is stored and marked only when the plan keeps
a `phone_off`. Mutation S5 stores it whenever a valid fix came (`off_at.unwrap_or_else(Utc::now)`), and it passes
every `power::` test.

**Fix:** add one case to `a_bad_shutdown_position_costs_the_position_only` (or a sibling test): an `off` older than
30 days, or one later than its `on`, sent with a valid `location`. It should add no `device_locations` row and no
`shutdown_at`.

### Q-5 (Low): Locate's line shows during a known restart and during the push hold, unlike the device page

`server/src/handlers/locate.rs:975-990`. The device page's "Off since" line (`parent_events::off_since_line`, qa-38-code
Nit 1) stays silent in two cases, so that the page says no more than the pushes:
- for "Anna is restarting";
- while the off's push is still held (5 min).

Locate's "Last position before the phone went off" appears at once in both cases. After a `svc power reboot` it
stays up for the 1-2 minutes until the start's report.

**Fix (coordinator's choice):**
- **(a)** Apply the same two exclusions to `before_off`: the newest `phone_off` for that `happened_at`, with its title
  and `created_at`.
- **(b)** Keep the current behaviour and say so in design 38's section. The line is informational, and a restart
  really did switch the phone off.

### Q-6 (Low): `PowerEvents.init`'s policy read can overwrite a newer policy from the sync

`launcher/.../server/PowerEvents.kt:386-395` against `:316-319`. The first sync is started just before `init`
(`Application.kt:432` `CommandListenerService.start`, then `:437` `PowerEvents.init`). The executor checks
`!locationPolicyNoted`, reads the policy cache, then writes `locationPolicy`. If the sync calls `noteLocationPolicy`
in between (for example with a policy just switched to "Off"), the older cached value overwrites it until the next
sync. The window is a prefs read (milliseconds) against the sync's network fetch (seconds), so the race is unlikely.
But "Off" is exactly the privacy case.

**Fix:** make `noteLocationPolicy` and the `init` write `synchronized` on one lock. Compute the cached value first,
then inside the lock assign it only `if (!locationPolicyNoted)`.

### Q-7 (Low): the low fix may be redundant with a fix taken seconds earlier

`MdmSyncWorker.kt:226-235`, `LowBatteryFix.kt`. Inside the sync there is no double: `withLowBatteryFix` keeps a fix
the sync takes anyway, and `stampBeat` counts the low fix as the heartbeat. Outside the sync there can be one:
- the zones' moving-series check runs on its own (`LocateCommands.kt:211`);
- a heartbeat may have fixed a minute before the edge.

In both cases the low-battery sync still takes a 15-30 s active fix.

**Fix (optional, within scope):** when the phone's own last active fix (`lastActiveLocationFetchAtMs`) is younger
than about 2 minutes, report `CACHED` and still mark the episode taken.

### Nits

- **N-1** `LowBatteryFix.kt:43` and `LowBatteryFixTest.kt:45`: "while charging Android never says low" is inaccurate.
  - `EXTRA_BATTERY_LOW` is "whether `ACTION_BATTERY_LOW` has been sent" (`mSentLowBatteryBroadcast`). It stays `true`
    while charging until `ACTION_BATTERY_OKAY`, which comes at the close-warning level (low + 5 %), not at the low
    level.
  - The rule (discharging and not low) doesn't depend on this, so only the comment and the test message need
    rewording. The class doc's "charged above Android's low level" should say "up to Android's okay level".
- **N-2** Test gap (mutation S9, survives). The line keys off the newest fix's `shutdown_at`, which is correct. Taking
  `MAX(shutdown_at)` over all rows passes too. In practice a newer fix only arrives with a report, which ends the line
  anyway. One assertion with a newer fix inserted and no report would pin it.
- **N-3** The `phone_off` event's `params` carry the phone's `provider` string raw. The store passes it through
  `models::location_provider`. It is never rendered (`told` ignores a `phone_off`'s params), and the 2 KiB body limit
  bounds it. Storing the cleaned word would keep the params consistent with the trail.
- **N-4** Docs: `docs/testing/emulator.md` §6p doesn't carry the new checks yet; design 38 lists them as owed. Add
  them when they are run, and note that the shutdown check needs a non-mock fix (`adb emu geo fix`, not a test
  provider: on the debug build a test provider's fix is mock, so the shutdown sends nothing - by design).

## Checked, no finding

- **Contract.** `power_report.json` is byte-identical in both directories. `PowerEventsTest` encodes exactly it, and
  `power::a_launcher_report_raises_both` posts it with the fix's instant moved along with the off's. Both sides
  compare the copies, and both CI filters already list the files.
- **An old launcher.** Its `off` has no `location`, and the many existing `power::` bodies without one still pass
  (`#[serde(default)]`).
- **An old server.** `PowerOff` has no `deny_unknown_fields`, so `location` is ignored. The body grows by about 120 B,
  well inside 2 KiB.
- **Shutdown window.**
  - The DE record is committed first, then the CE `power_off_location` with `commit()`, both before `goAsync`
    (pinned; mutations L5a/L5b caught).
  - `encodePowerRecord` has no location key (L7 caught).
  - `clearSent`/`dropSent` compare the off by instant and boot (`sameOff`), so the joined `location` never makes
    a 2xx miss its record.
  - On a resend with the next boot's `on`, `withOffLocation` joins only the same instant and boot (L8 caught). The CE
    copy is pruned once the record no longer holds that off.
- **CE locked at the resend.** It can't happen. `PinLockRuntime.init` (the receiver) and the sync both run from
  `initRest`, after the component factory's proof that CE is unlocked. If it did, `loadOffLocation` catches the error
  and the off goes without a position, which is a loss, not a fault.
- **Dedupe.**
  - `checked_location` normalises `captured_at` to `%Y-%m-%dT%H:%M:%S%.3fZ`, so the power path's
    `as_report` (Millis) and a report's own timestamp produce the same `store_fix` identity (S1 caught).
  - The zones' cursor is `(at, id)`, so a shutdown fix older than one already judged is stored for the trail but never
    judged out of order.
- **Privacy.**
  - No coordinates in any launcher log (`LowBatteryFix`, `PowerEvents`; no HTTP body logging) or any server
    `tracing` line.
  - The push and the inbox words are unchanged, and the detail has no place (S4 and S8 caught).
  - A mock fix is never sent (L1 caught).
  - A position that doesn't parse or check costs the position only (S6 and S7 caught).
- **Low battery.**
  - `ACTION_BATTERY_LOW` is not sticky, and the receiver is registered once per process. Even a redelivery would hit
    the CE episode flag.
  - A LOW that arrives during a running sync gets its own rerun (`SyncRunner`'s coalescer).
  - The Location switch is checked before `currentLocation`, which would otherwise switch it on.
  - `FRESH_BALANCED` is 15 s fused plus 15 s GPS, then last-known.
  - "Off" and no policy give no fix (L4 caught).
- **The deviations are all sound:**
  - the CE copy instead of the DE record (better than the design: nothing about the user before the unlock);
  - no mock fix at shutdown (the body has no mock word, so a debug build's fix would reach the trail unflagged);
  - the fix inside the edge's own sync (no second report, reported in that same report);
  - the footer sentence instead of an ⓘ (the group has none);
  - the battery event unchanged.

## Mutations

| # | Side | Break | Tests run | Result |
|---|---|---|---|---|
| L1 | launcher | `shutdownLocation` sends a mock fix | PowerEvents/LowBatteryFix/BatteryReport tests | caught |
| L2 | launcher | a fresh `currentLocation(FRESH_BALANCED)` at shutdown | same | caught (source pin) |
| L3 | launcher | `take` ignores `taken`: a second fix in the same episode | same | **NOT CAUGHT** (Q-2) |
| L4 | launcher | `lowBatteryFixDue` allows "off" | same | caught |
| L5a | launcher | CE save with `apply()` instead of `commit()` | same | caught |
| L5b | launcher | CE save moved after `goAsync` (into the executor) | same | caught |
| L6 | launcher | `ACTION_BATTERY_OKAY` doesn't end the episode | same | **NOT CAUGHT** (Q-2) |
| L7 | launcher | the fix written into the DE record | same | caught |
| L8 | launcher | `withOffLocation` ignores the boot count | same | caught |
| L9 | launcher | `observe` ends the episode on any reading | same, then **full suite** | **NOT CAUGHT** (Q-2) |
| S1 | server | the shutdown fix stored as a new row (raw INSERT, no intake dedupe) | `power::` | caught |
| S2 | server | `before_off` always true (line after a newer report) | `power::`, `locate` | caught (2 tests) |
| S3 | server | retention keeps `params`/`detail` | `power::`, retention, cleanup, zones, notifications, locations, then **full suite** | **NOT CAUGHT** (Q-3) |
| S4 | server | coordinates in the inbox detail | `power::`, `parent_events` | caught |
| S5 | server | fix stored and marked although the plan dropped the off | `power::` | **NOT CAUGHT** (Q-4) |
| S6 | server | an invalid location kept in the event's params | `power::` | caught |
| S7 | server | strict `location` parsing (a bad one refuses the body) | `power::` | caught |
| S8 | server | "last position on Locate" in the pushed summary | `power::`, `parent_events` | caught (2 tests) |
| S9 | server | Locate's line from any row's `MAX(shutdown_at)`, not the newest fix's | `power::`, `locate` | **NOT CAUGHT** (N-2) |

13 of 19 caught. One full run per side was done with a surviving mutation in place: S3 gave 1314 passed, and L9 gave
1786 tests with 0 failures. Everything was reverted (`git checkout -- launcher server`; the tree was clean).

## Emulator checks to add (`docs/testing/emulator.md` §6p)

1. **Shutdown with a stored fix.**
   - Setup: `adb emu geo fix 10.7522 59.9139` (a GPS fix, not mock), then an Update Location, so the phone has a
     reported fix.
   - Run `adb shell svc power shutdown`.
   - Expected:
     - logcat shows the POST's 204;
     - Locate's newest fix reads "Last position before the phone went off", with exactly one row in the trail for it;
     - the inbox's off row says "Last position from HH:MM: shown on Locate";
     - after 5 min the push "Anna was turned off · …" has no place in it.
   - Start the AVD: the line is gone after the start's report.
2. **The kept, unreported fix (zones on).** With a fence, a passive fix lands in the kept slot unreported. Then
   `svc power shutdown`: a new trail row, marked as the shutdown's.
3. **Location "Off"** on the phone's Location Settings, after a sync: `svc power shutdown` sends an `off` with no
   `location`, and Locate shows no line.
4. **A known restart.** `svc power reboot`: note whether Locate's line appears during the 1-2 min before the start's
   report (Q-5's decision).
5. **Low battery, once per episode.**
   - Run `adb emu power ac off`, then `adb emu power capacity 10`.
   - Expected: logcat `LowBatteryFix: Battery low: one fix (balanced)`, and one new fix on Locate within about 30 s.
   - Then `adb emu power ac on`, then `ac off` at the same level: logcat `Battery low: no fix (taken true, …)` and no
     new fix.
   - Then `adb emu power capacity 30` with `ac on` (OKAY), then `ac off` and `capacity 10`: one fix again.
6. **Low battery with Location off.** With the quick-settings Location toggle off, the LOW edge gives
   `no fix (… location false)`, and the switch stays off.
7. **Low battery under "Off":** `no fix (… mode off)`.
8. **Jelly Star (device).**
   - A real LOW edge with Battery Saver auto-on at 15 %: does the balanced fix still get a position with the screen
     off? Battery Saver's location mode may be foreground-only or no GPS with the screen off.
   - A real flat-battery shutdown: does it carry the position?

## Fix round (2026-10-25, branch `last-position`)

The coordinator's decisions, every finding taken. Launcher fixes in `c9ecea5c`, server fixes in `d4cef477`, docs in the
commit that adds this section.

- **Q-1** (migration 0097): nothing to do here. The coordinator's decision: this branch merges first and keeps 0097;
  `pwa-fixes-b` already renumbered itself 0098/0099.
- **Q-2**: fixed in `c9ecea5c`. The episode's steps moved into `LowBatteryEpisode`, over a `LowEpisodeFlag` (CE prefs
  in the app, a field in the tests). The glue `LowBatteryFix` only binds it, which a source pin in the test checks. The
  OKAY case is the pure `lowEpisodeAfterEdge`. New `LowBatteryFixTest` cases:
  - `one fix per episode, however often the edge comes, and again after the battery was okay`: LOW, a charging reading,
    LOW again, then LOW at 13 % gives no second fix; OKAY or a discharging not-low reading ends the episode.
  - `no fix under Off or with Location off, and the episode stays open for a later edge`.

  Re-run mutations, each against `LowBatteryFixTest`, all **caught** by failing tests (not compile errors):
  - **L3** (`take` ignores `taken`): fails 2 tests.
  - **L6** (OKAY doesn't end the episode): fails 2.
  - **L9** (`observe` ends the episode on any reading): fails 1.
  - **L4** ("Off" allows a fix): fails 2.
  - **Q-7** (no recent-fix window): fails 2.
- **Q-3**: fixed in `d4cef477`. Added `power::the_shutdowns_position_goes_with_the_location_retention` as proposed:
  14 days of retention keeps the params, 7 days clears params and detail, and the event stays.
- **Q-4**: fixed in `d4cef477`. `power::a_bad_shutdown_position_costs_the_position_only` now also sends two offs the
  plan drops, each with a valid position: one older than 30 days, one later than its start. Neither adds a row to
  `device_locations`.
- **Q-5**: fixed in `d4cef477`, option (a), the coordinator's decision. `parent_events::off_said(title, created_at,
  now)` is the device page's two "Off since" exclusions, now shared: `off_since_line` calls it. `locate::before_off`
  also asks it about the off's event, found by its dedup key (the shutdown's instant). An event already gone with the
  inbox's 90 days leaves the line to the other two checks.
  - Unit table: `locate::tests::the_last_position_before_the_phone_went_off` (a known restart, a held push, the hold's
    end, no event).
  - `power::the_shutdowns_last_position_is_on_locate`: the line is absent during the hold and present after it.
  - New `power::a_known_restart_says_no_last_position`.
- **Q-6**: fixed in `c9ecea5c`. `noteLocationPolicy` and the new `noteCachedLocationPolicy` (init's cached read, which
  sets the policy only while no sync has noted one) both run inside `synchronized(locationPolicyLock)`. Pinned by
  source in `PowerEventsTest`: both writers, the guard, init's call, and exactly two assignments.
- **Q-7**: fixed in `c9ecea5c`. A fix of the phone's own under `LOW_FIX_RECENT_MS` (2 min) is reported instead of a new
  one. The age is `LocateCommands.cachedQueued()`'s, the reported fix (`lowFixKind`), and the result is `LowFix.CACHED`:
  `withLowBatteryFix` turns NONE into CACHED, so "Only when asked" still reports it. The episode is still marked. Test:
  `a recent own fix is reported instead of a new one`, plus the table in the action test.
- **N-1**: fixed in `c9ecea5c`. The comment and the test message now say Android's okay level: `EXTRA_BATTERY_LOW`
  stays set while charging until `ACTION_BATTERY_OKAY`.
- **N-2**: fixed in `d4cef477`. In `power::the_shutdowns_last_position_is_on_locate`, a newer fix inserted with no
  report ends the line, and deleting it brings the line back.
- **N-3**: fixed in `d4cef477`. `device_api::power` stores the location's provider through
  `models::location_provider` before the plan, so the params carry the cleaned word. Test: `<b>GPS</b>` leaves no
  provider in the params or the row.
- **N-4**: fixed in this commit. `docs/testing/emulator.md` §6p checks 5-12 are this review's eight checks. They state
  that the shutdown check needs a non-mock fix, that the shell can't send `BATTERY_LOW`, and that Locate shows no line
  during the hold.

Runs after the fix round, local:
- **Server**:
  - `cargo fmt --check`: clean.
  - `cargo clippy --all-targets`: the 13 known warnings.
  - `cargo test`: 1316 tests. The full run had 1315 passed and 1 failure: `admin::every_written_event_has_a_label`
    took the new `let (…, off_event)` and the `phone_off` literal next to it for an event writer. The variables were
    renamed, and the full suite run again after that.
- **Launcher**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true` succeeded, 1789 tests, 0 failures.
- **Contract**: `cmp` of the two `power_report.json` copies: identical.
