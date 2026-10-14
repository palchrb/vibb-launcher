# QA 34 step 13 (code): `time_state.zone`

Branch `design-34-s13`, head `ae7b3319` (on `e17949e3`). Reviewed: the diff (launcher `TimeState.zone`,
`reportedZone`, `TimeRulesRuntime.report`, `TimeStateZoneTest`; server `devices_list::a_launcher_report_with_its_zone_shows_the_rules_end`,
comment/doc updates), against design 34 P13 / §5 step 13 / §7 L8, `server/CLAUDE.md` (the lock badge's "only when
provably right" rule) and `launcher/CLAUDE.md` (Time rules › Status).

## Summary and verdict

**Ship, with three Low follow-ups.** The step is small and does what P13 asks: the launcher sends its
`ZoneId.systemDefault().id`, a `null` zone is left out of the JSON (`ServerJson` doesn't encode defaults), old servers
ignore the key (`TimeState` on the server is read as an untyped `serde_json::Value`, no `deny_unknown_fields`), no
capability or policy change. The server's provable-end rule needed no change and the new end-to-end test is sound:
deterministic at any time of day, the `Etc/GMT` sign inversion is right, and it covers the Devices list, the
summary's header and Device › Time. Removing the zone check (S1) or relaxing it to case-insensitive (S2) is caught.

What's left: the launcher's "the zone read once is the zone the day is counted in" is not true in the code (L1); an
end inside a DST gap is shown as a clock time that never occurs (L2, pre-existing code now reachable); the
launcher/server literal is kept equal by hand where the repo has a contract-file pattern for exactly this (L3). The
`reportedZone` guard is unreachable and unpinned (N1).

## The provable-end rule with a real zone

| Case | Result | Why |
|---|---|---|
| Zone equal, report fresh, today, inside one window | "until HH:MM" | `provable_end` (`server/src/time_state.rs:297`) |
| Travel: the phone's zone changes mid-day | no end | the reported id is no longer Phone Setup's (exact string compare, `time_state.rs:304`); back home the ledger's `day` may stay ahead (set-back ignored), then `current_day` fails: "at last report", still no wrong end |
| Stale report | "at last report", no end | `lock_badge`'s staleness before `provable_end`; `end <= now_local` marks it stale afterwards |
| Zone equal, phone clock off | at most the network-time error | automatic time is forced (`AppEnforcer.kt:995`) and the server judges by `reported_at` (its own clock), not the phone's |
| An alias (`Europe/Kiev` on old tzdata vs Phone Setup's `Europe/Kyiv`) | no end | exact compare - safe, only a missing end |
| Fall-back hour (rule ending 02:30 on 2026-10-25, Oslo) | right | probe: "until 02:30" for the first 02:30; after it, "at last report"; in the repeated 02:xx the old report reads "until 02:30" again, which is right (the phone relocks then) |
| Spring-forward gap (rule ending 02:30 on 2026-03-29, Oslo) | **"until 02:30"**, a time that never shows | L2 |

## Findings

### L1 (Low) - the zone is read once, but the day and the rule are counted in two other reads

`launcher/.../timerules/TimeRulesRuntime.kt:169` reads `val zone = zone()` "once" (design doc and KDoc: the zone the
report's `day` and the rules' windows are counted in), but `snapshot` then calls `zone()` again for the ledger's day
(`:89`, via `ledger`) and for the active rule (`:144`). A `TIMEZONE_CHANGED` between the reads gives a report whose
`zone` isn't the one its `day`/`active_rule_id` were counted in. The window is tiny and the server's own window check
usually refuses a mismatched end, so Low - but the stated guarantee isn't what the code does, and the test pins only
the spelling: mutation L1b (moving the read **after** `snapshot`) survives; the source-text test
`the_runtime_reports_the_zone_it_counts_in` (`TimeStateZoneTest.kt:81`) only catches `reportedZone(zone())`.

**Fix**: thread one `ZoneId` through: `snapshot(context, policy, overrideActive, zone: ZoneId = zone())` passes it to
`ledger(..., zone)` and the `at` computation, and `report` does `val zone = zone(); val snap = snapshot(context,
policy, zone = zone)`. Then replace the source-text test with nothing (the signature says it), or drop the local and
the "same read" claim from the docs if the race is judged irrelevant.

### L2 (Low) - an end inside a DST gap shows a clock time that never occurs

`provable_end` (`server/src/time_state.rs:297-328`) returns the window's naive end without checking it exists in the
zone. Probe (scratch unit test, rule 00:00-02:30 every day, Oslo, reported 2026-03-29 00:30 UTC = 01:30 CET): **"Leggetid
until 02:30"**. 02:30 doesn't exist that night; the phone's lock (`decideTimeLock` on `LocalDateTime`) lifts at the jump,
when the clock reads 03:00 (01:00 UTC) - the badge implies 30 minutes more than the phone gives. Pre-existing design-31
code, but unreachable until this step sent a zone. One night a year, only for windows ending 02:00-02:59, so Low; it
still breaks "only when provably right".

**Fix**: refuse an end that isn't exactly one instant - a `Zone::single_instant(&self, t: NaiveDateTime) -> bool`
(`self.tz.from_local_datetime(&t).single().is_some()`) in `display_time.rs`, and in `provable_end` `if
!zone.single_instant(end) { return None; }` before `Some(end)`. (The repeated hour is right today, as above; refusing
`ambiguous` too is simpler to reason about and costs one night's badge.) Test: the spring case gives "Leggetid" alone,
2026-10-25 too if ambiguous is refused.

### L3 (Low) - the shared report literal is kept equal by hand

`LAUNCHER_TIME_REPORT` (`server/src/tests/devices_list.rs:426`) and the expected string in
`TimeStateZoneTest.the_zone_is_in_the_encoded_json` (`TimeStateZoneTest.kt:43`) are the same bytes "kept equal by
hand". The server test overwrites `day`, `active_rule_id` and `zone`, so if the launcher renamed or dropped a key and
updated its own literal, the server test would still pass - the drift the end-to-end test exists to catch. The root
`CLAUDE.md` has the pattern for exactly this (`phone_vectors.json`, the `music_*.json` snapshots, `bridge_contract.json`:
byte-identical copies, checked on both sides). "`server/testdata/` pins no status shape" is the reason to add one, not
to skip it.

**Fix**: `server/testdata/time_status_report.json` (the body) + a byte-identical
`launcher/app/src/test/resources/time_status_report.json`; the server test `include_str!`s it, the launcher test
compares `encoded("Europe/Oslo")` with the resource; add the pair to the root `CLAUDE.md`'s contract list and to
`launcher.yml`'s path filter (it already lists the shared snapshots). Both tests already exist, so it's ~10 lines.

### N1 (Nit) - `reportedZone`'s guard is unreachable and unpinned

`TimeRules.kt:309-315`: a `ZoneId` from `systemDefault()` is a region id (`[A-Za-z][A-Za-z0-9~/._+-]+`, the longest IANA
id ~32 characters) or an offset - never blank or non-ASCII, and the server already drops anything over 64 bytes or
non-string. Mutations L2 (refused id sent as `""`) and L3 (the guard removed, `zone.id` as is) both survive. **Fix**:
drop `reportedZone` and `MAX_REPORTED_ZONE_LENGTH`; `zone = zone.id`. Less code, nothing lost (the server's
`TimeReport::parse` is the guard).

### N2 (Nit) - the padded-zone case doesn't test the 64-byte cap

`devices_list.rs:535` pads Phone Setup's zone past 64 bytes, but any unequal string already gives "Leggetid" alone, so
mutation S3 (the cap removed from `TimeReport::parse`) survives. The cap isn't load-bearing for correctness (only
equality is). **Fix**: drop the case, or drop the claim that it exercises the cap from the docs.

### N3 (Nit) - clippy count

`cargo clippy --all-targets --locked` gives 15 warnings here (the implementation status says 13); none in the touched
files, so likely the local toolchain, not this diff. Just don't quote the number as the diff's.

## Checks

- **Launcher**: `zone: String? = null`, left out when null (pinned by `no_zone_is_left_out`), an older body decodes.
  No capability needed (the server needs none to read an optional key). Fine.
- **End-to-end test**: `offset = 3 - utc_hour` folded into -12..11, `Etc/GMT{-offset:+}` - POSIX sign inversion right
  (UTC+3 is `Etc/GMT-3`); `Etc/GMT+0` at 03 UTC parses and names itself (probe: `Some("Etc/GMT+0")`). Local time is
  always 03:xx, so a 01:00-07:00 window on today's weekday (yesterday null - no wrap) covers it with no midnight edge;
  an hour rollover during the test stays inside the window. All three views asserted, both the end and the
  name-alone cases, and back again. Good.

## Runs

- Server: `cargo fmt --check` clean; `cargo clippy --all-targets --locked` 15 warnings, none in the touched files;
  `cargo test --locked` **951 passed**; node jstests **30/30**.
- Launcher: `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true`
  BUILD SUCCESSFUL, **1259 tests, 0 failures**.

## Mutations

| # | Mutation | Result |
|---|---|---|
| S1 | `provable_end` ignores the zone check | killed (4 tests, incl. the new e2e) |
| S2 | zone compared case-insensitively | killed (the new e2e) |
| S3 | `TimeReport::parse` keeps a zone of any length | **survived** (N2) |
| L1 | `report` passes `reportedZone(zone())` (a second read) | killed (source-text check only) |
| L1b | `report` reads the zone after `snapshot` | **survived** (L1) |
| L2 | a refused zone sent as `""` | **survived** (N1; harmless, the server drops `""`) |
| L3 | the `reportedZone` guard removed | **survived** (N1) |
| DST | probe, not a mutation: spring gap / autumn repeat | spring shows "until 02:30" (L2); autumn right |

## Counts

0 High, 0 Medium, 3 Low, 3 Nits.
