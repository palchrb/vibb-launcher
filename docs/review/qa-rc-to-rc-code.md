# QA (code): an RC-to-RC launcher update installs

Branch `rc-to-rc-update`, commit 510001b1 (on `main` d594eadc). Server and launcher. Reviewed 2026-10-23 on branch
`qa-rc-to-rc`.

## Summary

The fix does what it says on both sides, and the two sides agree exactly. A release counts as "ahead" when its code is
higher, or when the code is equal and its name is newer. Three places use that rule: the launcher's
`launcherUpdateStep`, the pending-APK check and Install Now's settle. The server's `install_now::release` uses it too.

No downgrade path opens:

- A lower code is still `DOWNGRADE` before any name logic.
- An equal code with an older, equal, unreadable or missing name is `SAME_VERSION`, or "not behind".
- The size, hash, package and signer checks run first and are unchanged.
- A stable release over its own RC is unchanged (higher code).
- The contract files are untouched.

The full suites pass on both sides. I compared the name parsers differentially (1,470 cases): the outputs are
identical.

Two things are worth fixing before the merge:

- **R2R-1.** Phones on a build from before the fix, which today means 0.36.0-rc.1 and rc.2, cannot take rc.3 by
  themselves. With this server they get an Install Now button that answers "Already on 0.36.0-rc.2".
- **R2R-2.** The rule that an RC sorts before its own release is not pinned on either side. Mutating it survives.

The rest are doc nits.

Counts: Blocker 0, Should 2, Nit 4.

## Runs

| Side | Command | Result |
|---|---|---|
| server | `cargo fmt --check` | clean |
| server | `cargo clippy --all-targets` | 13 warnings, the same 13 as `main` (`models.rs`, `dns_engine.rs`, `devices.rs:1303`, `storytel.rs:144`); none in `install_now.rs` |
| server | `cargo test` (full) | **1204 passed**, 0 failed, 0 ignored |
| launcher | `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL. **1594 tests**, 0 failed, 0 skipped (161 suites; `SelfUpdatePlanTest` 36, `InstallNowPlanTest` 18) |

## Mutations

Each mutation was applied alone and reverted afterwards. The tree is clean at the end. The server ran
`cargo test install_now` (19 tests). The launcher ran `testDebugUnitTest` on `SelfUpdatePlanTest` and
`InstallNowPlanTest` (54 tests).

| # | Side | Mutation | Result |
|---|---|---|---|
| S1 | server | `release()` ignores `app_version` (`build_ahead(.., running, None)`) | killed: `an_rc_over_an_rc_of_the_same_version_is_behind` |
| S2 | server | an RC after its release (`None => -1` in `version_order`) | **survived** (R2R-2) |
| S3 | server | "differs" instead of "newer" (`ours != running`) | killed: `a_build_is_ahead_by_a_higher_code_or_the_same_code_under_a_newer_name`, `an_rc_over_an_rc_of_the_same_version_is_behind` |
| S4 | server | RC numbers compared by their first digit (rc.10 < rc.9) | killed: `a_build_is_ahead_..._newer_name` |
| K1 | launcher | rc.10 < rc.9 (`rc.take(1).toLong()`) | killed: `a build is ahead by a higher code, or by the same code under a newer name` |
| K2 | launcher | an RC after its release (`-1L` for no RC) | **survived** (R2R-2) |
| K3 | launcher | the pending-APK check accepts an unreadable name (`archiveVersionName != null && ..`) | killed: `a pending APK of the same code installs only under a newer name, every other check stays`, `a downgrade is refused before fencing, the same version is taken as installed` |
| K4 | launcher | Install Now's record drops the name (`fromVersionName = null` in `installNowOnReceipt`) | killed: `an rc over an rc of the same version is installed, the same name is already running` |
| K5 | launcher | "differs" instead of "newer" (`return true` in the order loop) | killed: 3 tests (`a build is ahead ..`, `an rc over an rc .. is downloaded ..`, `a pending APK of the same code ..`) |
| K6 | launcher | the glue drops `runningVersionName = BuildConfig.VERSION_NAME` in `handleLauncherUpdate` | killed: `an rc over an rc of the same version is installed, ..` (its source assertion) |

## Checks

- **No downgrade.**
  - `pendingApkCheck` still runs MISSING, CORRUPT, UNPARSEABLE, NOT_OURS, WRONG_SIGNER, then DOWNGRADE (lower code)
    before the new `SAME_VERSION` line (`SelfUpdatePlan.kt:201-208`). The signer and package lines are byte-for-byte
    unchanged, and so is `deterministic`.
  - An equal code is OK only when the archive's name is newer than ours. An older, equal, unreadable (`null`) or
    unknown own name gives `SAME_VERSION`. That path records the tag installed, drops the APK, and lets Install Now's
    settle answer "already running"; nothing is installed.
  - `launcherUpdateStep` keeps a lower code, or an equal code under an equal, older or unknown name, as
    `ALREADY_RUNNING`.
  - The server's `release` keeps an equal code with an equal or older name as `UpToDate`. Its test covers rc.2 and
    rc.3 running under an rc.2 tag, 0.36.0 under 0.36.1-rc.1, and a report without a name.
- **A stable release over its RC.** The code is higher, so the name is never read. Covered on both sides: 0.36.0 over
  rc.2, and `release(launcher-v0.36.0@6)` on rc.2 is `Behind`.
- **The server never offers what the phone runs.** An equal tag name gives `build_ahead == false`, then `UpToDate`,
  then `refusal == "up_to_date"`. A `pending_tag` still makes the phone behind, as before. The changed old test
  (`refusal` with code 1_004_000) now reports the name "1.4.0" as well. The old fixture said "1.3.2" under 1.4.0's
  code, which the new rule rightly calls behind, so the change is right.
- **"installed: X" vs "already running: X".**
  - rc.1 to rc.2 by Install Now: the record has `fromVersionName = "0.36.0-rc.1"`. After the replace,
    `installNowOutcome` finds rc.2 ahead and answers `installed: 0.36.0-rc.2`, ahead of a failure the dying process
    recorded.
  - Tapping on a phone that already runs the tag: `ALREADY_RUNNING`, then `RUNNING`, then `already running: X`.
  - A record from before the field (`fromVersionName == null`) falls back to the code. Such a record can only lead to
    a higher-code install, because a pre-fix build never installs an equal code, so the fallback is exact.
- **Contract files.** No file under `server/testdata/`, `launcher/app/src/test/resources/` or `music/` changed. The
  policy shape, `PendingCommand`, the status body and `InstallNowRecord`'s wire are untouched. The record is local DE
  prefs, and its new field has a default, so an older stored shape still decodes (`the record keeps its fields and
  reads an older shape`).
- **The parsers agree.** I added a throwaway test on each side (not committed). Both dumped, for 26 tags, the tag's
  code and its name, the Kotlin side through `launcherTagVersionCode`/`launcherTagVersionName` and the Rust side
  through `launcher_tag_version_code`/`release_name`. Both also dumped `build_ahead` for all 37 x 37 name pairs, plus
  the null cases. The two outputs are **identical** (1,470 lines). The cases included:
  - `-rc.01` (= rc.1: equal order, so not ahead);
  - `-rc.` with no number (no code);
  - `@`, `@x@y` and a trailing `@`;
  - manual-upload labels, `v0.36.0`, a leading space, a trailing space and a trailing `\n`;
  - `RC` in capitals, `-rc.2-rc.3`, `-beta`, `+1` and `-1`;
  - Arabic-Indic digits (not digits on either side);
  - `0.0036.0` (no code), a 10-digit RC (a code, but no order) and `10000.0.0`.

  `launcher_tag_version_code` and `launcherTagVersionCode` are unchanged by the diff and agree on every tag in the
  dump.
- **launcher.yml.** The name is `${tag#launcher-v}`, exactly what `launcherTagVersionName` and `release_name` take.
  An RC's versionName is therefore "0.36.0-rc.2", and the running name, the tag name and the APK name always match
  for CI builds.

## Findings

### R2R-1 (Should): phones on a pre-fix build get a button that cannot work

**Where:** `server/src/install_now.rs:243-245`.

The rule only helps from the first launcher build that carries it. Builds from before the fix decide by code alone:
0.36.0-rc.1 and 0.36.0-rc.2, both 35_999, are out today. Such a phone records rc.3 as installed (`ALREADY_RUNNING`),
because it cannot know the name rule.

The server now calls that phone `Behind`: rc.3's name is newer under the same code, and the phone has
`install_now_v1`. So the PWA shows "0.36.0-rc.3 not downloaded yet" for good, with an Install Now button. A tap
answers "Already on 0.36.0-rc.2", and the button comes back.

Before the fix the row said "Up to date", which was also wrong but quiet. Now it is wrong and offers a button that
does nothing.

**Fix**, either of these:

- (a) **Preferred.** Gate the equal-code branch of `release` on a new status capability that the fixed launcher
  claims, for example `install_now_rc_v1` in `STATUS_CAPABILITIES`. Without it, an equal-code-newer-name case is
  `TooOld`. Its sub-line "0.36.0-rc.3 is waiting. Install Now works from this update on." is exactly true then.
- (b) Accept it and write it down in `play-and-updates.md`, `launcher/CLAUDE.md` and PLAN: a phone on 0.36.0-rc.1 or
  rc.2 needs rc.3 by hand once (`adb install -r` works: same code, same key) or the next stable release.

Whichever is chosen, the user should know before rc.3 that the test phones won't pull it by themselves.

### R2R-2 (Should): "an RC before its release" is not pinned on either side

**Where:** `server/src/install_now.rs:77`, `SelfUpdatePlan.kt:76`; tests `install_now.rs:622`,
`SelfUpdatePlanTest.kt:259`.

Mutations S2 and K2 (an RC ranked after its own release) survive. Every test case that reaches the name order either
differs in X.Y.Z first or compares RC with RC.

The rank matters only when X.Y.Z and the code are both equal, which no pair of CI builds produces (the code differs
by 1). So it is dead for real releases today, but the doc comments on both sides promise it.

**Fix:** one line each side:

- Server: `assert!(build_ahead(5, Some("0.36.0"), 5, Some("0.36.0-rc.2")))` and
  `assert!(!build_ahead(5, Some("0.36.0-rc.2"), 5, Some("0.36.0")))`.
- Launcher: the same two cases in `a build is ahead by ..`.

### R2R-3 (Nit): design 37 needs a one-line "superseded" note

**Where:** `docs/design/37-install-now.md:95`, `:162` (and `:31`).

§2.2's table says `UpToDate` = "tag code <= running `app_version_code`" and `Behind` = "tag code > running". §3 says
`installNowOutcome`: "running > `fromVersionCode` -> `Installed`". Both are now the code-and-name rule. The other
design docs carry notes of the form "superseded by …".

**Fix:** under §2.2's table and next to the §3 bullet, add something like "Since the RC-to-RC fix (2026-10-23):
ahead = a higher code, or the same code under a newer name (`build_ahead` / `launcherBuildAhead`; play-and-updates.md,
launcher/CLAUDE.md)". The non-goal at :31 ("newer versionCode") can say "ahead of the running build" instead. The
fix relaxes nothing: a same-code APK of our own key under a newer name is not a weaker check.

### R2R-4 (Nit): two stale sentences in launcher/CLAUDE.md's Install Now section

**Where:** `launcher/CLAUDE.md:339`, `:344`.

- :339: "it holds ids, a tag and versionCodes only". The record now holds a versionName too.
- :344: "the new record stores the running versionCode". It now stores the code and the name.

**Fix:** add "and versionName" to both.

### R2R-5 (Nit): two docs leave out the "can't be ordered = ahead" half of the rule

**Where:** `play-and-updates.md:149-153`, `launcher.yml:179-184`.

Both state the rule as "another, newer one" and leave out what the code does with two differing names that can't be
ordered: it counts them as ahead. That happens for a local build's name, or for an odd tag CI still accepts, such as
an RC number of 10+ digits or a 4-digit MINOR.

`launcher/CLAUDE.md` and both KDoc and rustdoc comments state it.

**Fix:** add "(two names that can't be ordered count as ahead)" to both.

### R2R-6 (Nit, outside the diff): same-code updates are invisible to two version-keyed records

**Where:** `lock/BootCoverPlan.kt:126`, `crash/CrashReports.kt:42`.

- **Boot cover guard.** A tripped boot-cover guard gets "one more try after a launcher update" by comparing
  `trippedVersion` with `VERSION_CODE`. An rc.2 that fixes an rc.1 cover crash will not re-arm it; only the switch's
  off -> on edge will.
- **Crash reports.** Crash reports carry only the code, so rc.1 and rc.2 crashes read as one build on the server.

Neither is wrong today, because RC-to-RC never installed before. Both become reachable with this fix.

**Fix (optional):** key the cover's try on `VERSION_CODE` plus `VERSION_NAME`, or note it in the 59 doc.

## Verdict

**Merge after fixes.** R2R-1 needs a decision: the capability gate (a), or the documented one-time manual step (b).
R2R-2 needs two asserts per side. R2R-3 to R2R-5 are doc lines. The code itself is correct, safe against downgrades
and identical on both sides.
