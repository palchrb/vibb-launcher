# QA: the launcher security wave (2026-10-12)

Scope: branch `launcher-sec` on top of `main` at `c9462785`, judged against
`docs/review/security-review-2026-10-10.md`: the finding tables, "Left for the launcher wave", and "Launcher
security wave status" with its deviations. The M10 hand-off notes in `docs/review/qa-security-wave-server.md` are
part of the spec. The commits:

| Finding | Commits |
|---|---|
| L13 | `a43cdac5` |
| L8 | `1b7cb328` |
| M12 (launcher) | `6c545938` |
| M8 | `3bfe1597`, `c467de52` |
| L12 (both sides) | `ae534585` |
| L7 (both sides) | `663bbd83` |
| M10 (phone side) | `4ce7bd91` |
| L9 (Gradle side) | `4b85993f` |
| docs | `cb1c9b85` |

Paths: `L/` is `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` is `server/`. Line numbers are at the tip of
`launcher-sec` (`cb1c9b85`).

## Build

- Launcher: `testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true`, with a throwaway stub
  `app/libs/tsnet.aar` (removed afterwards). 924 tests, 0 failures; both APKs built.
- L13 by hand: without the aar, `assembleRelease` fails in `:app:checkReleaseHasTsnet`, before any compile.
- Music: `testDebugUnitTest assembleDebug assembleRelease lint -PwarningsAsErrors=true` passed. The test task was
  up to date, so it was run again with `--rerun`: 137 tests, 0 failures.
- Server: `cargo test`: 546 passed. `cargo fmt --check`: clean. `cargo clippy --all-targets`: 12 warnings, the same
  12 as `main` (only line numbers moved). None comes from the wave's code.
- actionlint 1.7.7 on `launcher.yml` and `music.yml`: the same 6 shellcheck style/info notes as on `main` (SC2129,
  SC2012). Nothing new from the wrapper-validation steps.

Checked against upstream, by fetching:
- AOSP: Telecom `CallsManager` and `CallScreeningServiceFilter` (main); `PackageInstallerSession`,
  `PackageManager.getPackageArchiveInfo`, `ParsingPackageUtils` (android15-release); `DevicePolicyManagerService`
  (14/15/16), `ActiveAdmin` and `DevicePolicyData` (14), `DeviceAdminInfo` (14).
- OkHttp 4.12.0's `RetryAndFollowUpInterceptor` and `Util.canReuseConnectionFor`, as bytecode.
- `services.gradle.org`: the distribution checksum, and Gradle 8.12.1's wrapper-jar checksum.
- Google Maven: the x86_64 `aapt2` jar and POM.
- GitHub: the `gradle/actions` v6.4.0 tag.

Probe tests: three temporary Rust tests through the real routers, now deleted. They confirmed High 1, Low 1 and
Low 2.

Not run:
- nothing on an emulator or a phone (no KVM here);
- no GitHub Actions run.

Counts: 1 High, 1 Medium, 6 Low.

## High

1. **High (L12): re-delivery has no age limit, and migration 0065 makes every old, unconfirmed command
   re-deliverable. A wipe the parent pressed weeks ago runs after the update.**
   - Where:
     - `S/migrations/0065_command_receipts.sql:10` sets `deliveries = 1` on every delivered row. `received_at`
       stays NULL unless the row has a result.
     - The pop in `S/src/handlers/device_api.rs:324-331` offers any row with `received_at IS NULL AND
       acknowledged_at IS NULL AND deliveries < 3 AND delivered_at <= now - 2 min`. It sorts by `requested_at ASC`,
       so the oldest row goes first. It has no bound on age.
     - Nothing prunes `device_commands` (`S/src/retention.rs` doesn't touch it).
     - The phone's run-once memory (`CommandAnswers`, `L/server/CommandAnswers.kt:44`) starts empty with the new
       launcher, so it can't recognise an old id.
   - Which rows are affected. An older launcher never confirmed a wipe. Before M8, every wipe also failed silently
     (`wipeData(0)` threw on API 34+). So every Wipe a parent ever pressed is a row with `delivered_at` set and
     `acknowledged_at` NULL. The same holds for any row whose answer never arrived:
     - a result POST that failed (it was best-effort);
     - a policy the phone refused (`REJECT_SUSPECT`, or a decode failure);
     - the phone was offline right after the fetch.
   - Scenario:
     1. Months ago the parent tried "Wipe" on the kid's phone. Nothing happened (M8), and the page said "a wiped
        phone can't confirm".
     2. Server 0.24.0 runs 0065, and the row becomes `deliveries = 1`.
     3. The launcher self-updates in the night window. Its first status report carries `command_receipt_v1`.
     4. The next policy fetch offers the months-old wipe. The new launcher confirms it and calls `wipeDevice(0)`,
        which works now. The kid's phone factory-resets. It would also be offered up to two more times.
     - Other rows: a stale `start_pause` gives the kid 2 hours without restrictions, and a stale `lock` or `ring`
       fires out of nowhere.
     - The status notes say "a launcher from before this wave is never sent a command twice". The phone still is,
       once it runs the new launcher. The same applies to rows delivered to the old launcher after the server
       update but before the launcher update.
   - Probe (confirmed): a wipe row in the post-0065 shape (requested and delivered 90 days ago, `deliveries = 1`) is
     not offered while the reports lack the capability. It is the `pending_command` of the first policy after a
     report with `command_receipt_v1`.
   - Fix:
     - Old rows: everything delivered before 0065 was at-most-once, so set `deliveries = MAX_COMMAND_DELIVERIES` for
       every delivered row. 0065 isn't on `main` yet, so change it in place. If any database already ran it, use a
       new 0066.
     - Bound re-delivery in the pop: re-offer only while the first delivery is recent, for example `requested_at >=
       datetime('now', '-15 minutes')` (three deliveries 2 minutes apart, plus slack). Then nothing older than that
       ever replays.
     - Test: a pre-0065 delivered wipe is never offered to a phone that gains the capability, and an unconfirmed
       command older than the window isn't offered again.

## Medium

1. **Medium (L12): command ids aren't unique across a server restore (or across servers). The phone sends a
   receipt for a new command whose id it already saw, but never runs it. A wipe then reads "the phone is wiping
   now".**
   - Where:
     - `L/server/CommandAnswerPlan.kt:44` (`firstSight`) and `L/server/MdmSyncWorker.kt:341-346` key "ran it
       already" on the id alone.
     - The ids come from `device_commands.id INTEGER PRIMARY KEY AUTOINCREMENT`
       (`S/migrations/0010_find_my_device.sql:24`). Its counter (`sqlite_sequence`) is inside every backup, so a
       restore winds it back.
     - A new enrollment doesn't clear `command_answers`. `L/server/Provisioning.kt:44-47` and
       `L/ui/settings/launcher/SettingsFragmentLauncher.kt:353-356` clear the parent-PIN events on a new enrollment
       for exactly this reason ("they were about the old one"), but not the command answers.
   - Scenario:
     1. The Pi's SD card dies, and the parent restores last night's backup (the supported M4 flow). Device tokens
        are in the backup, so the phone keeps syncing.
     2. Since that backup the phone ran commands 481-490: an "Update location now", a ring, the evening's
        `start_pause`. They are in its run-once list. The restored counter stands at 480.
     3. The parent presses Lock, which becomes id 481. The phone logs "delivered again - confirmed, not run twice",
        sends the receipt, and doesn't lock.
     4. The page shows "Received ..., not finished yet", then "never said it finished". Up to the next ~10 commands
        are swallowed the same way (the list keeps 32).
     5. A Wipe in that range shows "Received ... - the phone is wiping now; it stops checking in" while the phone
        keeps its data and keeps checking in.

     The same happens when a phone is enrolled onto a fresh server (a new Pi without a backup), since ids start
     again at 1. There the outbox can also post an old receipt or result to the new server. It is scoped to the
     phone's own rows, so it can mark one of its new commands as received before delivery, and the pop then never
     offers that command.
   - Before L12 there was no dedup, so this is new with the wave.
   - Fix:
     - Make the run-once key unique per command. The server sends the row's `requested_at` (or a random `nonce`
       column) in `pending_command`, and the phone remembers `(id, requested_at)`. This is a policy-shape change:
       both sides, with `policy_json_keys_snapshot`/`PolicyResponseCompatTest`.
     - Clear `command_answers` on every new enrollment, next to `ParentPin.clearEvents`.
     - Optionally, the restore paths also move `sqlite_sequence` for `device_commands` far ahead.
     - Test: the phone runs a command whose id it saw before when `requested_at` differs; enrollment empties the
       state.

## Low

1. **Low (L12): re-delivery reorders design 24b's own-insert commands. A lost "pause" runs after a later "Lock
   the phone again now".**
   - Where:
     - The pop (`S/src/handlers/device_api.rs:324-331`) makes an unconfirmed command wait 2 minutes while newer
       ones go first.
     - `queue_command`'s supersede (`S/src/handlers/locate.rs:256-268`) skips `end_windows`, `allow_blocked` and
       `start_pause`.
     - `queue_own_command` (`S/src/handlers/window_relock.rs:142`) supersedes nothing.
   - Scenario:
     1. The parent presses "Pause restrictions for 2 hours". The response carrying `start_pause` is lost: the phone
        switched networks mid-fetch.
     2. A minute later the parent changes their mind and presses "Lock the phone again now". `end_windows` is
        delivered and runs first, with nothing to end.
     3. Two minutes after the first delivery, `start_pause` is offered again. The phone pauses for 2 hours, against
        the parent's last action, and both rows read "Done".

     The reverse order ends a pause the parent just granted. Before L12 the lost command was simply lost.
   - Probe (confirmed): `start_pause` is delivered, then `end_windows` is queued and delivered. After 3 minutes,
     `start_pause` is the `pending_command` again.
   - Fix (either):
     - When the pop hands out a command, stop re-offering every older unconfirmed command of that device
       (`deliveries = MAX`), so nothing runs out of order.
     - Or let the window commands supersede each other's unconfirmed rows, as `queue_command` does for the Find My
       Device commands.

2. **Low (L12): a delivered, unconfirmed command goes to the next phone enrolled into the same device row.**
   - Where: `enroll` (`S/src/handlers/device_api.rs:99-110`) only replaces the token. The pop decides whether to
     re-offer by the device's latest status report, which can be the old phone's.
   - Scenario:
     1. The parent wipes a lost phone. The response never reaches it, because the phone is off. `deliveries` stays
        at 1, since nothing polls.
     2. Weeks later the parent sets up a replacement phone on the same device page, to keep its rules and contacts.
     3. Its first policy fetch comes before its own first report, so the old phone's `command_receipt_v1` still
        counts. The fetch carries the old wipe, and the new phone factory-resets during setup. This repeats until a
        receipt gets through.
     - A stale lock or `start_pause` behaves the same.
     - Undelivered commands already followed a re-enrollment before the wave. L12 extends that to delivered ones.
   - Probe (confirmed): after a re-enrollment with a new token, the first policy carries the day-old wipe.
   - Fix: enrollment ends the device's pending commands: delete the undelivered ones, set `deliveries = MAX` on the
     unconfirmed ones, and record it in the security log. High 1's age bound covers most of it too.

3. **Low (M8): a wipe call that returns is reported as success ("wiping"). On a phone, a wipe that works never
   returns.**
   - Where:
     - `L/server/LocateCommands.kt:396-401`.
     - `L/server/WipePlan.kt:48`: no exception is `STARTED`.
     - `L/server/MdmSyncWorker.kt:373-376`: `success = outcome != FAILED`.
   - AOSP, `DevicePolicyManagerService.forceWipeDeviceNoLock` (14/15/16):
     - It catches `IOException | SecurityException` from `FactoryResetter`/`RecoverySystem.rebootWipeUserData`,
       logs "Failed requesting data wipe", writes `TAG_WIPE_FAILURE`, and returns normally.
     - A working reset never returns: `RecoverySystem.bootCommand` reaches `PowerManager.reboot(REBOOT_RECOVERY)`,
       which waits forever.
     - Only automotive's safety checker delays a reset and returns.
   - Scenario: writing the recovery command fails. `wipeDevice(0)` returns, and the launcher reports
     `success=true, "wiping"`. The Locate page shows "Done ... (wiping)" while the phone keeps its data and keeps
     checking in. That is the outcome M8 set out to rule out.
   - Fix: treat a return as "the reset didn't start". Report `success=false` with "the wipe call returned - Android
     didn't start the reset; the phone keeps its data", or wait ~30 s and report that only if the process is still
     alive. Cover it in `WipePlanTest`.

4. **Low (M8): on Android 14 the new `<wipe-data />` only takes effect after the phone restarts once.**
   - Where: `launcher/app/src/main/res/xml/device_admin.xml:10`. In AOSP 14:
     - `wipeDataWithReason` without the policy-engine flag needs `getActiveAdminWithPolicyForUidLocked(...,
       USES_POLICY_WIPE_DATA)`, i.e. `admin.info.usesPolicy`, for a device owner too.
     - The owner's `DeviceAdminInfo` is parsed from the package at boot (`DevicePolicyData.load`, with
       `overwritePolicies` false for the owner).
     - `handlePackagesChanged` doesn't re-read it on an update.
   - Scenario: an Android 14 phone (minSdk 34 allows one) self-updates to this launcher tonight and is lost tomorrow
     before any restart. The wipe is refused (`SecurityException: No active admin ...`). It is reported as
     "Failed", so the reporting works, but the parent can't wipe the lost phone.
   - Not affected: the Jelly Star (Android 16). There a device owner has `MANAGE_DEVICE_POLICY_WIPE_DATA` through
     `DEVICE_OWNER_PERMISSIONS`, without the admin policy (checked in DPMS 16).
   - Fix:
     - Report `dpm.hasGrantedPolicy(admin, DeviceAdminInfo.USES_POLICY_WIPE_DATA)` in the status report. AOSP
       returns `administrator.info.usesPolicy(...)` there.
     - Show "Remote wipe works after the phone restarts once" on the Locate page while it is false.
     - Add to `emulator.md` §6g item 1: on API 34, install the previous build, make it device owner, update in place
       without a reboot, then wipe. The current step installs fresh, so it can't see this.

5. **Low (M12): `TsnetClient.connectFromPreferences` isn't serialized. Two first joins at once can leave a node
   state whose key is now deleted.**
   - Where: `L/server/TsnetClient.kt:146`, with no lock, and `client ?: Tsembed.new_(...)` at `:103-106`. The
     callers:
     - Home's `onResume` launches it on `Dispatchers.IO` on every resume (`L/ui/HomeActivity.kt:413`);
     - the sync calls it (under `syncMutex`, which Home doesn't take);
     - the QR setup calls it.
   - Scenario: at the first start after setup, two calls see `client == null`. Two tsnet servers then share
     `files/tailscale`, and both try to register with the one key. The state file is whole-map last-writer-wins. If
     the losing node writes last, the phone restarts into a state that isn't registered, and the key is already
     deleted.
     - Before M12, a reusable key could re-register the phone. Now nothing can, short of setting it up again.
     - With the one-time keys DEPLOY recommends, this was already unrecoverable, so M12 only widens it. The race
       itself predates the wave.
     - Not verified on a device: it depends on tsnet's write order.
   - Fix: `@Synchronized` on `connectFromPreferences`. It already blocks up to 30 s, and every caller is off the
     main thread.

6. **Low (L9, CI): `launcher.yml`'s change filter misses the music files `BuildChainTest` now reads.**
   - Where: `.github/workflows/launcher.yml:71`. The launcher's `BuildChainTest` reads:
     - `music/gradle/wrapper/gradle-wrapper.properties`;
     - `music/gradle/verification-metadata.xml`;
     - `.github/workflows/music.yml` (its wrapper-validation steps).

     The filter matches none of them. The root `CLAUDE.md` says the filter covers "the music files its tests read".
   - Scenario: a PR that only touches `music.yml` drops the wrapper-validation step, or bumps music's wrapper alone.
     `music-build` passes and `launcher-build` is skipped, so the guard meant to catch it never runs. It fails later,
     on an unrelated launcher change.
   - Fix: add `music/gradle/` and `\.github/workflows/music\.yml$` to the filter.

## Notes (not findings)

- **L7, the warning text.** The calls warning for "screening role, no dialer role" says calls from withheld numbers
  ring through. `KidCallScreeningService`'s comment says Telecom never asks about withheld numbers. In Telecom main,
  neither `setUpCallFilterGraph` nor `CallScreeningServiceFilter.startFilterLookup` checks the handle, so the
  screening service may well see them. The warning errs on the safe side. Worth one withheld-number call in §6g
  item 4 before wording it as fact.
- **M12, DEPLOY.** DEPLOY says a phone whose node was removed from the tailnet "isn't brought back by scanning a new
  QR". That may be too pessimistic, though it wasn't verified:
  - a removed node puts tsnet in `NeedsLogin`;
  - tsnet then calls `StartLoginInteractive` with the `AuthKey` it was started with;
  - a newly scanned key starts a new client (`clientParams` differ).
- **M10, TOFU on the old key.** After a rotation the pin stays the older key. Whoever holds that key (if the rotation
  was because of a leak) can still pass the phone's check for a first install. Android itself blocks it as an update
  unless the lineage grants rollback. Moving the pin forward when the server sees a rotation would close it. That is
  a server-side design question, not a bug in this wave.
- **L12, run-once before running.** The id is marked as run before the command runs. A crash mid-command means it is
  never retried; the page then shows "Received ..., never said it finished". That is the honest at-most-once choice
  for lock and wipe, and fine.

## Missing tests

- Server: a command delivered before migration 0065 (or to an older launcher) is never offered to a phone that later
  reports `command_receipt_v1`, and re-delivery stops after an age bound (High 1).
- Launcher: a command whose id the phone saw on another server or before a restore still runs, and a new enrollment
  empties `command_answers` (Medium 1).
- Server: `start_pause`/`end_windows` keep their order when the first one's answer is lost (Low 1).
- Server: enrollment ends the device's pending and unconfirmed commands (Low 2).
- Launcher: `wipeOutcome` for a call that returned (Low 3).
- The release-build failure (L13) is only checked as text in `BuildChainTest`. A Gradle TestKit run, or a CI step
  that runs `assembleRelease` without the aar and expects failure, would pin the behaviour.

## Verified OK

- **L13:**
  - The stub is in the `debug` source set only.
  - `checkReleaseHasTsnet` is a dependency of every release variant's `preBuild`, and it reads the file, not a flag.
    Run by hand, `assembleRelease` without the aar stops there before compiling.
  - A stale configuration-cache `hasTsnet` can't fail open: the release source set has no stub, so the compile
    would fail instead.
- **L8:**
  - `sameOrigin` compares OkHttp's canonical `HttpUrl`s: lower-case host, punycode IDN, scheme default ports filled
    in. Userinfo isn't part of the origin.
  - A base URL that doesn't parse authorises nothing.
  - An absolute or scheme-relative `@Url` to another host gets no header.
  - OkHttp 4.12.0 (bytecode) removes `Authorization` on a redirect unless host, port and scheme match
    (`canReuseConnectionFor`). The application interceptor runs once per call, so a later hop never gets the header
    added back.
  - Every `createMdmApi`/`createMusicServer` call site uses the interceptor. The SSE request builds its URL from the
    base.
- **M12:**
  - The key is deleted only after `connect` returned an address. `tsnet.Server.Up` waits for `Running`, which comes
    after the login result (node key, `Persist`) is in the state store.
  - The key and the `joined` mark go in one `commit()`.
  - Joined with no key brings the node up with `""`. tsnet ignores a key once state exists, so dropping a key typed
    while connected loses nothing.
  - The key lives in the default prefs, the same file `TsnetClient` edits. Launcher backup is off.
- **M8:**
  - `wipeDevice(0)` on API 34+. In DPMS 14/15/16, the device owner's own `DISALLOW_FACTORY_RESET` doesn't block it
    (`isAdminAffectedByRestriction` is false for the owner).
  - `EXPLICIT_WIPE_BEHAVIOUR` with `factoryReset=true` reaches `forceWipeDeviceNoLock`. Flags 0 keep FRP.
  - The old `wipeData(0)` fails the system-user check: a `SecurityException` in 14, an `IllegalStateException` in 16
    (`checkState`). Both map to FAILED.
  - `DeadSystemRuntimeException` maps to GOING_DOWN.
- **L12:**
  - Receipts and results sit behind the bearer middleware and are scoped `WHERE id = ? AND device_id = ?`. Another
    phone's token changes nothing (test). The first receipt counts (`COALESCE`), and a result implies the receipt.
  - A DB error is a 500, so the phone keeps the answer. `command-receipt` is `RouteClass::Always`.
  - A launcher without the capability stays at-most-once on the server.
  - The phone dispatches only off a fresh, accepted policy. It commits the id before running, so a process restart
    never re-runs a command (within one server's id space).
  - The outbox is capped at 32. A 404 from an older server drops the receipt.
  - `ranCommand` keeps a re-delivered ring/locate from forcing a fresh fix.
  - 0065's `ALTER TABLE`s are valid SQLite. `SELECT *` into `DeviceCommand` reads the new columns.
- **L7:**
  - `CallsManager.setUpCallFilterGraph` binds the `ROLE_CALL_SCREENING` holder unless it is the default dialer, else
    the dialer.
  - `CallScreeningServiceFilter` skips a user-chosen app for numbers in the contacts unless it holds
    `READ_CONTACTS`. The launcher self-grants it.
  - `disallowCall` is honoured for a user-chosen app.
  - `call_state` is stored whole (≤ 4 KB), so `screening_role_held` reaches the warnings. Older launchers (no key)
    get no new warning.
  - `RoleSnapshot` reads both the two-flag and the three-flag form.
- **M10:**
  - `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)` sets `PARSE_COLLECT_CERTIFICATES`, which leads to
    `getSigningDetails(skipVerify = false)`: full `ApkSignatureVerifier.verify`.
  - Each lineage node is signed by its predecessor, so a stranger's APK can't carry the pinned cert in its lineage.
  - `Signature.toByteArray` is the signer's own cert, never a chain cert.
  - Several signers are checked through `apkContentsSigners`. A v1-only APK can't be pinned, so it isn't downloaded.
    An unreadable APK is refused.
  - The lineage check matches what `hasSigningCertificate` (`hasSha256Certificate`, any past cert) does for an
    installed package.
  - `setAppPackageName` is enforced in `PackageInstallerSession.assertPackageConsistentLocked`.
  - The file checked is the file installed: it is app-private (`noBackupFilesDir`), there is one runner, and the
    order is hash, check, then the session copy from the same `File`. A music-playing deferral re-runs hash and
    check.
  - There are only two `installSilently` callers, and the self-update keeps its own stronger check.
  - A refused release isn't downloaded again (`releaseStillWanted` checks `refusedTag`).
  - The refusal states fit the server's 32-character cap and are rendered from a fixed table, so the phone's text
    is never echoed.
- **L9:**
  - `distributionSha256Sum` equals `services.gradle.org`'s checksum for `gradle-8.13-bin.zip`.
  - Both `gradle-wrapper.jar`s equal Gradle 8.12.1's published wrapper checksum.
  - The pinned `wrapper-validation` commit is what the annotated `v6.4.0` tag points to.
  - The x86_64 `aapt2-8.13.0-13719691-linux.jar` and its POM match Google Maven's files, and the `.sha1` matches
    too. It is the only platform-classifier artifact (Room's sqlite-jdbc bundles its natives).
  - There is no blanket trust; sources and javadoc jars are trusted only by file name.
  - Validation runs before Gradle in all four Gradle jobs.
- **Contracts:**
  - `phone_vectors.json`, the music snapshots and the bridge AIDL/contract are untouched.
  - The `pending_command` shape is unchanged.
  - `screening_role_held` is on both sides (`PolicyResponseCompatTest`).
  - Policy keys are unchanged, so `policy_json_keys_snapshot` needs nothing.
