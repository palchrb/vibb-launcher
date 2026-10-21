# Code QA - design 57, the prep release (step 2): branch `worktree-agent-a689b601f638554b8`

- Commit: `6d9fdcd4` (one commit, launcher and server together, on `390be630`). The launcher declares
  `PACKAGE_USAGE_STATS`, reads the `GET_USAGE_STATS` app-op (`lock/UsageAccess.kt`), reports
  `lock_state.usage_access_granted` / `voip_over_lock_unsupported` with capability `voip_usage_v1`, and adds the
  PIN-gated Settings row "Samtaler over låsen" (`lock/UsageAccessRow.kt`). Server 0.34.0 parses the two fields leniently,
  warns above the Child PIN group (`LockNote::VoipOverLock`) and adds the Attention row `voip-over-lock`. The contract
  fixture is `usage_access_status.json`. Docs: `docs/setup/usage-access.md`, `emulator.md` §4, the server notes, and the
  note's "Prep release status".
- Read: root, `launcher/` and `server/` `CLAUDE.md`; design 57 §0, §1, §3.1, §6, §7 (7.1-7.4), §8, §9, §11 and the
  three revision/status sections; `qa-57-design.md` (57-5, 57-15 to 57-18) and `qa-57-revision-check.md` (N7, N9); the
  whole diff. Around the diff I read `BatteryExemption.kt` before and after, the battery row's wiring in
  `SettingsFragmentLauncher.kt`, `ServerJson` (no `explicitNulls`/`encodeDefaults`, so a `null` default is left out),
  `PinLockRuntime.report` and its only caller (`MdmSyncWorker`, a worker thread), `SyncRunner.request`, and
  `Application.initRest` (main process only; the `:bootcover` process returns before it). On the server I read
  `voipCandidates` (what "VoIP can ring" means on the phone), `kid_lock::LockState` (container `#[serde(default)]`),
  `sanitize_lock_state`, `lock_card`, `attention::facts_of`/`items`/`level`/`target`, and the devices list's
  Attention count. I checked `android-36/android.jar` with `javap`: `unsafeCheckOpNoThrow` is `@Deprecated` in SDK 36,
  and `checkOpNoThrow(String, int, String)` is not.
- Ran (in the worktree): `cargo test` **1112 passed, 0 failed**; `cargo fmt --check` clean; `cargo clippy
  --all-targets` has the 13 known warnings (`models.rs`, `dns_engine.rs:54-55`, `storytel.rs:97`, `devices.rs:1300`)
  and none in a touched file. Launcher `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`: BUILD SUCCESSFUL,
  **1506 tests, 0 failures** (`UsageAccessTest` 10). I didn't run `assembleRelease`: there is no `libs/tsnet.aar` on
  this machine and `checkReleaseHasTsnet` fails every release build without it (see Nit 3).
- Mutations: 13 launcher, 12 server (table at the end).

## What was verified

- **Nothing changes for the kid.** The only new code that runs on its own is `UsageAccess.init` from `initRest` (one
  background read and one `startWatchingMode`) and a read in each status report on the sync worker's thread. Nothing
  reads it to decide anything on the phone: no `VoipCalls`, `RefrontPolicy` or `PinLockState` change, and the lock's
  main-thread path has no new binder call. The source scan in `UsageAccessTest` (comments stripped) and my own grep of
  `src/main` (`UsageStatsManager`, `UsageEvents`, `queryEvents`, `USAGE_STATS_SERVICE`, `usagestats`, `StandbyBucket`,
  `isAppInactive`) find only the app-op read in `UsageAccess.kt` and the manifest line. The Settings row is in
  `R.xml.preferences`, which only the PIN-gated `SettingsFragmentLauncher` loads. Declaring `PACKAGE_USAGE_STATS`
  grants nothing by itself: the op stays `default`, and the permission (`signature|privileged|development|appop`) isn't
  granted to a normal app or to a device owner.
- **The app-op rule** (`UsageAccess.kt:31-32`). "ALLOWED, or DEFAULT with the permission" is exactly
  `UsageStatsService.hasQueryPermission` on 14, 15 and 16 (`noteOp` -> DEFAULT -> `checkCallingPermission
  (PACKAGE_USAGE_STATS)`, else `== ALLOWED`). Settings' toggle writes ALLOWED/IGNORED, `appops set` writes ALLOWED, and
  `pm grant` grants the development permission while the op stays DEFAULT: all three come out right.
  `checkOpNoThrow(String, uid, pkg)` and `unsafeCheckOpNoThrow` both go through `checkOpNoThrow(int, uid, pkg)` ->
  `checkOperation`, which maps FOREGROUND to ALLOWED. That mapping is irrelevant here because `GET_USAGE_STATS` has no
  foreground mode, so the test's `MODE_FOREGROUND -> false` row only pins the pure function. Checking our own uid needs
  no permission, and `Process.myUid()` with `packageName` (also `.debug`) always match, so it is never ERRORED for a
  mismatch. A throw reads as `null` (`:59`).
- **The watcher** (`UsageAccess.kt:76-103`). It is registered once (the `synchronized` flag) on the application
  context, so it can't leak an Activity, and it is never unregistered (process lifetime, which is fine). Without
  `WATCH_APPOPS`, Android only calls back for our own uid. The package check uses Kotlin's `!=` (equals, null-safe). A
  throw inside the callback is caught (`:96`), and a binder callback's escaped `RuntimeException` would be logged by
  `Binder.execTransact`, not crash. A sync is asked for only when the read changes (`refresh != before`), so an op
  write that changes nothing can't make a loop. If the watcher and a report race on `granted`, the result is benign:
  either the report already carries the new value, or the watcher asks for one more sync. `SyncRunner.request` from a
  binder thread ends in `startForegroundService`, which a device owner may call from the background.
- **The report.** `usageAccessGranted: Boolean? = null` and `voipOverLockUnsupported: Boolean? = null` are left out
  when null (`ServerJson` has no `encodeDefaults`). Both are tested: the contract test and the leave-out test, plus
  mutations L3 and L5. `voip_over_lock_unsupported` is `true` only below 35, and `minSdk` is 34, so only Android 14
  sends it. Capability `voip_usage_v1` is in `STATUS_CAPABILITIES`, and no `voip_gated*` is.
  `PolicyResponseCompatTest` pins the server's key names.
- **The contract fixture.** The two copies have the same blob (`82bf3ba1`). The launcher encodes exactly it
  (`trimEnd`, single line, no trailing newline). Both sides compare the copies (L10, S11), and the server posts it
  (`step10::usage_access_status_contract`). It is in `launcher.yml`'s and `server-ci.yml`'s path filters and in root
  `CLAUDE.md`.
- **Server parsing** (`kid_lock.rs:116-143`). Container `#[serde(default)]` handles a missing field (`None`).
  `lenient_bool` turns `null`, `"yes"` and `1` into `None` and keeps the rest of `lock_state` (tested: `failures: 3`
  survives). `skip_serializing_if` keeps absent fields out of the stored JSON (S7). The sanitizer re-serializes through
  `LockState`, so nothing else gets in.
- **The warning's conditions** (`kid_lock.rs:478-497`). It needs a Child PIN (`policy_kid_lock`), the reported lock
  `active`, and calls unmanaged or managed and on (QA 57-18; S2, S4). Android 14 comes first (S3). Usage access shows
  only for `Some(false)`, never `None` (S1), so a phone still on launcher 0.34.0, which doesn't send the field, is never
  told to grant before the permission is declared. That is the server half of 57-5's ordering. The prep and gated
  words follow `voip_gated_v1` on the lock page (S5). The note sits after `CallsCantRing` and before `On` (S12). The
  page and the Attention row come from the same function, so they agree. The one way they could differ (a report with
  `lock_state` but without `pin_lock_v1`) can't happen with a real launcher. The four titles are at most 8 words (the
  title-length test now loops over all four).
- **The Attention row.** It is `Level::Action` (S10), right after notification access, targets `/lock#child-pin`, is
  removed on a grant (step10), and creates no parent notification (Attention rows aren't `parent_events`).
- **PWA, never jump to top.** No form, template or handler that saves was touched. The new note goes through the
  existing `warning()` renderer and its ⓘ. The Child PIN form is unchanged.
- **Version.** `Cargo.toml` and `Cargo.lock` are 0.34.0. `server-v0.33.0` is the newest tag, so `server-v0.34.0` fits.
  Nothing else in the server pins the version.
- **The Settings row.** The `resolveSystemOnly` refactor is behaviour-neutral for the battery row: the same
  `resolveActivity(.., MATCH_SYSTEM_ONLY)`, the same flags and `ResolvedDialog`, still inside `dialogIntent`'s `try`,
  and `BatteryExemptionTest` is green. The usage row opens only a `dialogTarget` (system, unsuspended, not ours), tries
  the `package:` page before the list (L7, L9), and opens only in CAN_OPEN. On return it re-reads in
  `onActivityResult`, which syncs on a change, and in `onResume`. Android 14 shows "Needs Android 15"/"Krever Android
  15" first (L6). All six strings are in `values/` and `values-nb/` and match §8 word for word. `TranslationsTest`
  compares the full key sets, so it covers them without a list.
- **The manifest permission.** `tools` is declared on `<manifest>`, and `tools:ignore="ProtectedPermissions"` is the
  standard suppression. ProtectedPermissions has Error severity, not Fatal, so `lintVitalRelease` (fatal checks only)
  would not stop a release even without the suppression, and `lint { abortOnError = false }` applies to the full lint
  run. `UsageAccessTest` pins the line, its `tools:ignore` and the absence of a `maxSdkVersion` (L11, L13).
- **Docs.** `usage-access.md` "Order matters" puts the grant after `launcher-v0.35.0`. Way 1 (during a pause, the row,
  then the restricted-settings order of QA 57-15: tap, OK, App info › ⋮ › Allow restricted settings, then the toggle)
  and Way 2 (adb, the `.debug` package, `pm grant`) match the code and the note. `emulator.md` §4's `appops set` comes
  after the debug build's install, which declares the permission. The launcher and server notes and root `CLAUDE.md`
  are updated.

### The implementer's departures from the note

| Departure | Verdict |
|---|---|
| `checkOpNoThrow` instead of `unsafeCheckOpNoThrow` | **Right.** In SDK 36 `unsafeCheckOpNoThrow` is `@Deprecated` and `checkOpNoThrow(String,int,String)` isn't (`javap` on `android-36`), and `-PwarningsAsErrors` would refuse the former. On 34/35 both call the same `checkOpNoThrow(int, ..)` and give the same result. |
| The watcher reads on its binder thread and only asks for a sync | **Right for this release.** §3.1's "post to main" exists to run `VoipCalls.evaluate`, which doesn't exist yet. The status section says so. The gating release must add the post (keep §3.1's sentence as its spec). |
| The Android 15 note wins over Usage access | **Right.** On 14 the grant can't help, and two notes would contradict each other. The launcher row does the same. The Attention side of this is Low 1. |
| One `LockNote::VoipOverLock(VoipOverLock)` instead of two variants | **Right.** All four (kind × gated) titles and sentences are tested, and the Attention `Kind` reuses the same enum. |
| No nb texts on the server | **Right.** The PWA is English. §7.2 has the nb column only "for the record". |
| A shared fixture `usage_access_status.json` | **Right.** It closes N9's first half and follows the time/DNS/power pattern in full (both copies, both comparisons, CI and `CLAUDE.md`). `voip_usage_v1` staying unread (N9's other half) is a fair call: the fields themselves say whether the launcher reports. |

## Findings

No High, no Medium.

**1 (Low) - On Android 14 the Attention row is permanent and can't be acted on.**
`attention.rs:240-243` adds a row for every `VoipOverLock`, including `NeedsAndroid15`. That row is `Level::Action`
(`:298`), and `devices.rs:118` counts it in the devices list. Failure scenario: a Child-PIN phone on Android 14 with
calls on (`minSdk` is 34, so this is a supported phone). It shows an orange "Calls over the lock will need Android 15"
row and a "1" on the devices list for the rest of its life, and the parent can do nothing about it short of an OS
update the vendor may never ship. An Action row that never clears teaches the parent to skim past the same panel that
carries "Turn on Usage access", the one row this release exists for. §7.2 asks for an Attention row "from the same
notes", so this is a design call, not a code slip. Fix (needs the user's word): raise the row only for
`NeedsUsageAccess` and keep the Android 14 note on Lock Screen & PINs alone. For example, `if let Some(v @
VoipOverLock::NeedsUsageAccess { .. }) = facts.voip_over_lock { add(true, Kind::VoipOverLock(v)) }`, and
`voip_usage_row` asserts that `NeedsAndroid15` gives no row. If the user wants Android 14 phones flagged, a one-off
note would do, not a standing Action.

**2 (Low) - The row can't open Android's page once access is on, so it offers no way to take access back.**
`SettingsFragmentLauncher.kt:204-217` launches only when the state is `CAN_OPEN`, and `usageAccessRowState` returns
`ON` before it looks at the page (`UsageAccessRow.kt:22-28`). `UNKNOWN` can't open the page either. Failure scenario:
the parent wants to withdraw the grant (it is the privacy-relevant one: from the gating release it lets Vibb One read
the call app's usage events). During a pause they tap "On - the call screen can show over the lock" and nothing
happens. The runbook ends with "The parent can take it back any time on the same page" (`usage-access.md:64`), but the
row never leads there again. That leaves Android Settings › Apps › Special app access, which a managed phone hides
outside the pause and which may not be in reach from the kiosk even during one. The battery row behaves the same way,
but a battery exemption carries no privacy. Fix: open the page whenever a `dialogTarget` exists on API 35+, whatever
the grant: in the click handler, `state != NEEDS_ANDROID_15 && intent != null`. Optionally give ON a summary that says
"tap to change". `onActivityResult` already re-reads and syncs, so a revoke reaches the PWA. If the user prefers the
row to stay one-way, change the runbook's last sentence to the real path.

**3 (Low) - Three behaviours the tests don't pin (mutations L4, S6, L12 survive).**
- **(a)** `UsageAccess.read`'s `catch` returning `null` (`UsageAccess.kt:59-61`). If it returned `false` instead
  (L4), every test stays green. Failure scenario: an OEM's `AppOpsManager` throws (a `SecurityException` on a
  restricted profile, a dead binder at boot). The phone would then report `usage_access_granted: false` and the parent
  would get an orange "Turn on Usage access" row although access may well be on, which is exactly what "left out when
  unknown" exists to prevent. Fix: give the read seams that can be tested without Android, for example `internal fun
  readWith(mode: () -> Int, permission: () -> Boolean): Boolean? = try { usageAccessGranted(mode(), permission()) }
  catch (e: Exception) { null }` with `read` calling it, and a test where `mode` throws and the result is `null`.
- **(b)** The Attention row's gated words (`attention.rs:600-604`). With `facts_of` passing `true` for the gated flag
  (S6), the suite is green: `step10` checks only `data-attention="voip-over-lock"`, not the title. Failure scenario:
  after the gating release, a regression here would show the summary in the wrong words, saying "Turn on ..." where
  the lock page says "Calls in apps don't ring ...". Fix: in `step10::usage_access_status_contract`, also assert the
  summary's title in the prep case ("Turn on Usage access for Vibb One") and in the gated case.
- **(c)** The watcher's "only on a change" guard (`UsageAccess.kt:91`, L12). This can't be unit-tested as written.
  Pulling the decision into a pure `fun syncOnChange(before: Boolean?, after: Boolean?) = after != before` would make
  it testable. Optional: it can't loop and costs at most one sync.

## Nits

1. `UsageAccess.init` makes the `startWatchingMode` binder call on the main thread during `initRest`
   (`UsageAccess.kt:84`, `Application.kt:406`), and sets `watching = true` before the registration succeeded, so a
   throw leaves the process without a watcher for good. Both are harmless today, because every report and the row read
   fresh. Move the registration into the `usage-access` thread with the first read, and set the flag after it
   succeeds.
2. `docs/notes/server/hardening-and-pins.md:16` still says the sanitizer keeps "only `active, ..., voip_rang`". Add
   the two new fields (the new bullet at `:18` describes them, but the list in the Wire bullet is now wrong).
3. The note's status (`57-voip-call-screen-over-lock.md:886`) says "`assembleRelease` builds". That can't be
   reproduced without `libs/tsnet.aar`, which `checkReleaseHasTsnet` (`launcher/app/build.gradle.kts:279-302`)
   requires. Say "not run here (no tsnet.aar); CI's `launcher-build` runs it". The manifest line can't break it anyway
   (see the manifest point above).
4. The flag reads in `resolveSystemOnly` (`BatteryExemption.kt:57-67`) have no test. With `suspended = false` (L8) the
   suite is green, and the battery row had the same gap before this commit. It can't be pinned without Robolectric,
   so a device check covers it: §10's check 9 should name "outside a pause the row says *Off - pause the phone
   first*".
5. Bookkeeping before the merge. `PLAN.md` doesn't mark design 57 step 2 built, nor the user's per-phone grant that is
   now owed (the fix backlog rule). The branch is 7 commits behind `main`, but none of them touches a file of this
   commit, so the rebase is clean. Design 58's worktree is still on server 0.33.0: whichever of the two merges second
   takes 0.35.0.

## Mutations

Each mutation was a single change. I ran the relevant tests, then reverted it, and the tree was clean afterwards.
Launcher: `testDebugUnitTest --tests UsageAccessTest PolicyResponseCompatTest BatteryExemptionTest`. Server: `cargo
test -- kid_lock attention step10 usage_access`.

| # | Mutation | Result |
|---|---|---|
| L1 | `usageAccessGranted`: only `MODE_ALLOWED` (DEFAULT + permission dropped) | caught: `access mirrors the service ...` |
| L2 | `usageAccessGranted`: `MODE_DEFAULT` counts without the permission | caught: `access mirrors the service ...` |
| L3 | `voipOverLockUnsupported` sends `false` on 35+ instead of leaving it out | caught: `Android 14 is unsupported, 15 and later are left out`, `the report encodes exactly the contract ...` |
| L4 | `UsageAccess.read`'s catch returns `false` instead of `null` | **survives** (Low 3a) |
| L5 | `LockStateReport.usageAccessGranted` defaults to `false` (a `null` is then encoded) | caught: `... leaves both fields out when unknown` |
| L6 | the row: `ON` before `NEEDS_ANDROID_15` | caught: `the Settings row - Android 15 first ...` |
| L7 | the page choice: the list before the `package:` page | caught: `the row opens only an unsuspended system page ...` |
| L8 | `resolveSystemOnly`: `suspended = false` | **survives** (Nit 4) |
| L9 | the `package:` page taken without `dialogTarget` | caught: `the row opens only an unsuspended system page ...` |
| L10 | the server's fixture copy changed by one byte | caught: `the contract is identical to the servers copy` |
| L11 | the manifest line without `tools:ignore` | caught: `the manifest declares PACKAGE_USAGE_STATS ...` |
| L12 | the watcher asks for a sync on every callback (`if (true)`) | **survives** (Low 3c) |
| L13 | the `<uses-permission PACKAGE_USAGE_STATS>` element removed | caught: `the manifest declares PACKAGE_USAGE_STATS ...` |
| S1 | Usage access warns for `None` too (`!= Some(true)`) | caught: `voip_usage_notes` and four older card/Attention/step10 tests |
| S2 | the calls condition dropped | caught: `voip_usage_notes` |
| S3 | Usage access before Android 15 | caught: `voip_usage_notes`, `step10::usage_access_status_contract` |
| S4 | `!state.active` dropped | caught: `voip_usage_notes` |
| S5 | the lock page passes `gated = false` | caught: `step10::usage_access_status_contract` |
| S6 | `facts_of` passes `gated = true` | **survives** (Low 3b) |
| S7 | `skip_serializing_if` dropped on `usage_access_granted` | caught: `usage_access_fields_round_trip_leniently` |
| S8 | `lenient_bool` made strict (`Option::<bool>::deserialize`) | caught: `usage_access_fields_round_trip_leniently` (the first form, the attribute removed, had rustc killed for memory three times under a parallel build, so it was run in this equivalent form) |
| S9 | the Attention row never added | caught: `voip_usage_row`, `rows_link_to_the_card_that_fixes_them`, `alarms_come_first_...`, `step10::usage_access_status_contract` |
| S10 | the row at `Level::Alarm` | caught: `voip_usage_row`, `alarms_come_first_in_the_specs_order` |
| S11 | the launcher's fixture copy changed by one byte | caught: `step10::the_usage_access_contract_matches_the_launchers_copy` |
| S12 | the lock card's `VoipOverLock` note never pushed | caught: `voip_usage_notes`, `step10::usage_access_status_contract` |

Score: **21 of 25 caught** (launcher 10/13, server 11/12). The survivors are one gap in the report (L4), one in the Attention words (S6), and two that can't be
unit-tested without Android (L8, L12).

## Verdict

**Mergeable.** The prep release does what §7.4.1 asks and nothing more. The kid-facing paths are untouched and no
usage event is read. The app-op rule mirrors the service on 14, 15 and 16, and the contract is pinned on both sides
the way the other three status fixtures are. The server's warning has the right conditions in both word sets, and the
battery-row refactor is neutral. All six departures from the note are sound. Low 1 and Low 2 need the user's word
(both are small changes and fit before the `launcher-v0.35.0`/`server-v0.34.0` tags). Low 3a is worth doing in the
same pass, because it guards the one thing that would show the parent a false warning. The Nits are optional.
