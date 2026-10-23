# QA (code): every release build has its own versionCode

Branch `versioncode-rc`, commits 5abadf31 (code) and cce19898 (docs), on `main` 1ef36d09. Script, workflows,
launcher and server. Reviewed 2026-10-23 on branch `qa-versioncode-rc` (`git diff 1ef36d09..versioncode-rc`).

## Summary

The new rule is implemented the same way in all four places: the script, the launcher's `launcherTagVersionCode`,
the server's `launcher_tag_version_code` and `version_of_code`.

- **The cutover is the same in all of them.** For the launcher it is `launcher-v0.36.0-rc.4`, and that tag is read by
  the new rule everywhere. The comparison is lexicographic over X, Y, Z, then the RC number, with a release sorting
  after its RCs. For music it is `music-v0.5.0-rc.4` in the script and code 50_004 in the server.
- **Every existing tag still reads as it was built.** Below the cutover, both parsers give the old rule's code for all
  16 launcher tags.
- **The codes only go up.** The old launcher codes end at 35999 and the new ones start at 360004. For music, the old
  codes end at 4999 and the new ones start at 50004.
- **The script is strict.** It rejects leading zeros, so it needs no `10#` and the shell never reads a number as
  octal. It writes exactly the three output lines the workflows read, and its self-test runs in both always-on jobs.
- **All suites are green.** Every mutation I tried was caught.

One finding needs fixing before the merge, because the merge re-opens qa-rc-to-rc-code R2R-1:

- **Q-1 (Should).** Phones on 0.36.0-rc.1 or rc.2 judge a tag with their own old parser, which reads 0.36.0-rc.4 to
  rc.8 as 35999. That is their own code, so they never take those releases by themselves. The new server reads the
  same tags as 360004 and more, so it shows these phones an Install Now button. The phone can only answer "Already on
  0.36.0-rc.2". Before this branch the server showed TooOld for them.
- **Q-2 (Should).** Several docs state the wrong reason, or a wrong conclusion. PLAN says "nothing is owed by hand
  any more", which is false. PLAN and the script say "an installed rc.3 takes rc.4 by the code alone": rc.3 does take
  rc.4, but through the legacy name rule.

The rest are nits.

Counts: Blocker 0, Should 2, Nit 4.

## Runs

| Side | Command | Result |
|---|---|---|
| script | `bash scripts/release-version.sh --selftest`, from the repo root and from a scratch directory | all ok, 31 cases + 28 contract rows, exit 0 |
| launcher | `./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL, **1631 tests**, 0 failed, 0 skipped |
| server | `cargo fmt --check` | clean |
| server | `cargo test` (`CARGO_TARGET_DIR=~/.cache/handy-target/qavc`, deleted afterwards) | **1207 passed**, 0 failed, 0 ignored |
| contract | `cmp server/testdata/launcher_tag_codes.json launcher/app/src/test/resources/launcher_tag_codes.json` | identical |

The self-test's output:

```
tag                                code        result
launcher-v0.36.0-rc.4              360004      ok
launcher-v0.36.0                   360009      ok
launcher-v0.36.1-rc.1              360011      ok
launcher-v0.36.0-rc.3              -           ok
launcher-v0.35.9                   -           ok
launcher-v1.2.3-rc.8               10020038    ok
music-v0.5.0-rc.4                  50004       ok
music-v0.5.0                       50009       ok
music-v0.5.0-rc.3                  -           ok
music-v0.4.9                       -           ok
music-v1.0.0                       10000009    ok
launcher-v199.999.999              1999999999  ok
launcher-v199.999.999-rc.8         1999999998  ok
launcher-v200.0.0                  -           ok
launcher-v1.1000.0                 -           ok
launcher-v1.0.1000                 -           ok
launcher-v1.0.0-rc.9               -           ok
launcher-v1.0.0-rc.0               -           ok
launcher-v1.0.0-rc.10              -           ok
music-v1.0.0-rc.9                  -           ok
music-v200.0.0                     -           ok
launcher-v01.0.0                   -           ok
launcher-v1.0                      -           ok
launcher-v1.0.0-rc.                -           ok
launcher-v1.0.0-beta               -           ok
launcher-v1.0.0-rc.1-rc.2          -           ok
launcher-v1.0.0@5                  -           ok
v1.0.0                             -           ok
server-v1.0.0                      -           ok
music-1.0.0                        -           ok
                                   -           ok
contract: 28 launcher rows of server/testdata/launcher_tag_codes.json checked
all ok
```

I also ran some tags by hand:

| Tag | Result |
|---|---|
| `launcher-v0.36.0-rc.4` | `name=0.36.0-rc.4`, `code=360004`, `prerelease=true` |
| `launcher-v0.36.0` | 360009, false |
| `launcher-v0.37.0-rc.1` | 370001 |
| `music-v0.5.1` | 50019 |
| `launcher-v0.36.0-rc.08` | refused (malformed) |
| `launcher-v0.36.0-RC.4` | refused (malformed) |
| `launcher-v1.0.0` followed by a newline and `x` | refused (malformed) |
| `launcher-v0.36.0-rc.3`, `music-v0.5.0-rc.3` | refused ("older than ... the first tag under this versionCode rule") |
| `launcher-v0.36.0-rc.9`, `launcher-v1.0.0-rc.999999999` | refused (range) |
| `launcher-v999999999.0.0` | refused (range) |
| no argument | usage, exit 2 |

## 1. The cutover

| | Boundary | Rule at the boundary | Comparison |
|---|---|---|---|
| script | `LAUNCHER_FIRST=(0 36 0 4)`, `MUSIC_FIRST=(0 5 0 4)` | new | first differing of X, Y, Z, rc (a release = 1e9); lower = refused |
| launcher | `NEW_RULE_FROM = listOf(0, 36, 0, 4)` | new (`first == null`) | first differing pair; lower = old rule |
| server | `NEW_RULE_FROM = [0, 36, 0, 4]` | new (`<` is strict) | array `<`, lexicographic, a release = `i64::MAX`; lower = old rule |
| server, music | `MUSIC_NEW_RULE_FROM = 50_004` | new (`>=`) | by code; every old music code is <= 4999 |

The music boundary is a tag in the script and a code in the server. Nothing ties the two together except the literal
50004 in each side's tests (Nit N-3).

The old rule (`X*1_000_000 + Y*1_000 + Z`, an RC minus 1) applied to every existing tag (`git tag -l`, the same list
on origin):

| Tag | Old code | Contract row |
|---|---|---|
| launcher-v0.2.7 | 2007 | - |
| launcher-v0.25.0-rc.1 | 24999 | - |
| launcher-v0.26.0 | 26000 | - |
| launcher-v0.27.0 | 27000 | - |
| launcher-v0.28.0 | 28000 | - |
| launcher-v0.29.0 | 29000 | - |
| launcher-v0.29.1 | 29001 | - |
| launcher-v0.30.0 | 30000 | - |
| launcher-v0.31.0 | 31000 | - |
| launcher-v0.32.0 | 32000 | - |
| launcher-v0.33.0 | 33000 | - |
| launcher-v0.34.0 | 34000 | 34000 |
| launcher-v0.35.0-rc.1 | 34999 | 34999 |
| launcher-v0.36.0-rc.1 | 35999 | (35999 as `rc.1@70`) |
| launcher-v0.36.0-rc.2 | 35999 | - |
| launcher-v0.36.0-rc.3 | 35999 | 35999 |
| music-v0.0.1, music-v0.0.2 | 1, 2 | (no music contract) |
| music-v0.4.0-rc.1 to rc.4 | 3999 | |
| music-v0.4.0 | 4000 | |
| music-v0.5.0-rc.1 to rc.3 | 4999 | |

Every launcher tag has X = 0 and sorts below 0.36.0-rc.4, so both parsers take the old branch, which is the old
formula unchanged. The leading-zero rule is new, but no existing tag has a leading zero. The contract names only
three of the 16 real tags (Nit N-1).

The codes only go up:

- Launcher: the highest old code is 35999 and the lowest new one is 360004.
- Music: the highest old code is 4999 and the lowest new one is 50004.
- The launcher's next steps are 0.36.0 (360009), then rc.4 (360004), then rc.3 (35999, old rule).

The server's old-rule thresholds stay correct, because they compare with `>=`: `VIBB_ONE_EXTRA_BYTES_FROM` is 25_999
and `MUSIC_APP_EXTRA_BYTES_FROM` is 3_999. No other code threshold or decoder exists on either side (grep over
`server/src`, `launcher/app/src/main` and `music/app/src/main`).

## 2. Install Now and the self-update with mixed rules

**An old-rule phone uses its own parser.** Phones on 0.36.0-rc.1 to rc.3 carry the old `launcherTagVersionCode`, so
they read `launcher-v0.36.0-rc.4` to `-rc.8` as **35999**: the old formula, which is their own code. Only the APK's
real code (360004) comes from the new rule, and only the pending-APK check sees it. Each phone then behaves as
follows:

| Phone | Release | Self-update on the phone | Server `release()` (this branch) | Right? |
|---|---|---|---|---|
| rc.3 (35999, `same_code_update_v1`) | rc.4 | old parser 35999 = own code, newer name -> DOWNLOAD (the legacy name rule); pending check 360004 > 35999 -> OK | 360004 > 35999 -> Behind | yes |
| rc.1/rc.2 (35999, `install_now_v1` only) | rc.4 | old parser 35999 = own code, no name rule -> ALREADY_RUNNING (recorded installed) | 360004 > 35999 -> **Behind** (an Install Now that answers "Already on 0.36.0-rc.2") | **no, Q-1** |
| rc.1/rc.2 | 0.36.0 | old parser 36000 > 35999 -> DOWNLOAD | Behind | yes |
| 0.34.0 (34000) | rc.4 | old parser 35999 > 34000 -> DOWNLOAD | Behind | yes |
| rc.4 (360004) | `launcher-v0.34.0` ("latest") | 34000 < 360004 -> ALREADY_RUNNING | UpToDate | yes |
| rc.4 (360004) | rc.3 row | 35999 < 360004 -> ALREADY_RUNNING (tested) | UpToDate | yes |

Before this branch, the rc.1/rc.2 row was TooOld ("Install Now works from this update on"), which qa-rc-to-rc-code
R2R-1 made sure of. The settle after the install runs in the new build and compares 360004 with 35999 by code, which
is right.

Tests that exist for this:

- Launcher: rc.4 over rc.3 downloads (`runningAs("launcher-v0.36.0-rc.4@5", 35_999, "0.36.0-rc.3")`).
- Launcher: an rc.4 phone with the rc.3 row is ALREADY_RUNNING.
- Launcher: an rc over the last rc downloads by code (10_030_003 to rc.4).
- Server: 0.36.1-rc.1 (360011) with the 0.36.0 row is UpToDate.
- Server: the R2R-1 tests, still with old-rule tags only.

Tests that are missing (I added none):

1. Server `release()`: an rc.3 report (35999, "0.36.0-rc.3", same-code capable) with `launcher-v0.36.0-rc.4@N` is
   Behind.
2. Server `release()`: an rc.1/rc.2 report without `same_code_update_v1`, with `launcher-v0.36.0-rc.4@N`, is TooOld.
   This test fails today (Q-1).
3. Server `release()`: an rc.4 report (360004) with the `launcher-v0.34.0` row is UpToDate. Launcher: the same row
   gives `running("launcher-v0.34.0@5", 360_004)` = ALREADY_RUNNING.
4. Launcher `pendingApkCheck`: an archive of 360004 / "0.36.0-rc.4" over own 35999 / "0.36.0-rc.3" is OK.
5. Launcher `installNowOutcome`: a record from 35999 / "0.36.0-rc.3", with 360004 / "0.36.0-rc.4" running, is
   Installed.

## 3. The script and the workflows

**The script itself:**

- It runs under `set -euo pipefail`.
- Its numbers are `(0|[1-9][0-9]{0,8})`, so it rejects leading zeros. That makes `10#` unnecessary: there is none,
  and `((...))` never sees an octal-looking number. The mutation that allows leading zeros is caught (table below).
- The RC is capture group 5, inside the optional group 4.
- `BASH_REMATCH[5]:-` is safe under `-u`.
- The ranges are checked after the cutover. Numbers of up to 9 digits fit bash's 64-bit arithmetic.

**The workflows:**

- **Output lines.** `echo "$version" >> "$GITHUB_OUTPUT"` writes exactly `name=`, `code=` and `prerelease=`. The
  consumers are unchanged: `steps.version.outputs.name` and `.code` (the Gradle `-P` flags) and `.prerelease` (the
  job outputs). A refusal fails the step, because a failing command substitution in an assignment fails under
  Actions' `bash -e`.
- **Where the self-test runs.** It runs as a step without an `if:` in `launcher-changes` and `music-changes`, after
  the diff step, at the repository root (those jobs have no `working-directory`). The change filter only sets an
  output, so it cannot skip the self-test. The `launcher-ci`/`music-ci` gate requires `CHANGES = success`, so a
  failing self-test fails the required check.
- **On tag pushes** the changes jobs are skipped. The release build's version step runs `--selftest` itself (from
  `launcher/` or `music/`, as `../scripts/...`).
- **The contract path** is `$(dirname "${BASH_SOURCE[0]}")/../server/testdata/...`, which is relative to the script,
  not to the current directory. I checked it from the root and from a scratch directory, and it works from
  `launcher/` as `../scripts`.
- **Mode.** The script is committed as 100755.

## 4. Contract hygiene

- The two copies are byte-identical (`cmp`), and each suite compares them:
  `install_now::tests::launcher_tag_codes_match_the_launchers_copy` and the launcher's ``the tag codes are identical
  to the server's copy``.
- `launcher.yml`'s filter has `server/testdata/(...|launcher_tag_codes)\.json$`.
- `server-ci.yml`'s filter has `launcher/app/src/test/resources/(...|launcher_tag_codes)\.json$`. The launcher's own
  copy is under `launcher/`, which its own filter already covers.
- Root `CLAUDE.md` names the file, the launcher's `SelfUpdatePlanTest` and the server's `install_now::tests`. It
  gives modules, not test names (Nit N-2).
- Both CI path lists in root `CLAUDE.md` are updated.

## 5. Docs

The "minus 1" wording is gone or marked as the old rule in:

- `CLAUDE.md`
- `launcher/CLAUDE.md` ("Release builds" and the release-workflow paragraph)
- `docs/notes/server/play-and-updates.md`
- `launcher/app/build.gradle.kts`
- the workflow comments

The doc comments in `SelfUpdatePlan.kt`, `StatusCapabilities.kt`, `BootCoverPlan.kt` and `install_now.rs` are
updated too. `qa-rc-to-rc-code.md` has its superseded note. What's left:

- `music/app/build.gradle.kts:21-22` still says `X*1_000_000 + Y*1_000 + Z, an RC minus 1` (Nit N-4).
- The PLAN entry and the script header draw a wrong conclusion about old-rule phones (Q-2).

## 6. Mutations

Each mutation was applied to a copy (the script) or to the file itself and restored from a backup afterwards
(Kotlin, Rust), and was run against the matching tests. `git status` is clean after each batch.

| # | Mutation | Caught by |
|---|---|---|
| S1 | script launcher cutover one tag later (`0 36 0 5`) | selftest: `launcher-v0.36.0-rc.4 - FAIL (want 360004)` |
| S1b | script launcher cutover one tag earlier (`0 36 0 3`) | selftest: `launcher-v0.36.0-rc.3 360003 FAIL (want -)` + contract row |
| S1c | script music cutover one tag later | selftest: `music-v0.5.0-rc.4` |
| S2 | script rc.9 parses (`n > 9`) | selftest: `launcher-v1.0.0-rc.9`, `music-v1.0.0-rc.9`, contract `0.36.0-rc.9` |
| S3 | script stable N=0 | selftest (7 rows, e.g. `launcher-v0.36.0 360000`) |
| S4 | script launcher/music cutovers swapped | selftest (8 rows) |
| S5 | script leading zeros allowed (the `10#` question: there is no `10#`; the regex is the guard) | selftest: `launcher-v01.0.0 10000009 FAIL` (octal 01) + contract |
| S6 | script cutover exclusive (the first tag refused) | selftest: `launcher-v0.36.0-rc.4`, `music-v0.5.0-rc.4` |
| S7 | script stable sorts before its RCs | selftest: `launcher-v0.36.0`, `music-v0.5.0` refused |
| S8 | script MAJOR cap 200 | selftest: `launcher-v200.0.0`, `music-v200.0.0` |
| K1 | launcher `NEW_RULE_FROM` one tag later | ``a release tag's versionCode is the shared contract's`` |
| K2 | launcher rc.9 parses (`1..9`) | the contract test |
| K3 | launcher stable N=0 | the contract test + ``a newer release, the final over its rc, ...`` |
| K4 | launcher reads the first tag by the old rule (`first == null \|\| ...`) | the contract test |
| K5 | launcher regex allows leading zeros | the contract test |
| R1 | server `NEW_RULE_FROM` one tag earlier (`[0, 36, 0, 3]`) | `launcher_tags_read_as_the_shared_contract`, `the_same_code_needs_the_launchers_capability`, `tests::install_now::the_same_code_under_a_newer_name_is_offered_only_with_the_capability` |
| R2 | server rc.9 parses (`1..=9`) | `install_now::tests::launcher_tags_read_as_the_shared_contract` |
| R3 | server stable N=0 | `install_now::tests::launcher_tags_read_as_the_shared_contract` |
| R4 | server reads the first tag by the old rule (`<=`) | `install_now::tests::launcher_tags_read_as_the_shared_contract` |
| R5 | `version_of_code` decodes old codes by the new rule (`MUSIC_NEW_RULE_FROM = 1`) | `handlers::device_pages::tests::versions_read_short`, `tests::device_pages::about_this_phone_shows_the_last_reports_facts` |
| R6 | `version_of_code` music cutover one code later (50_005) | `handlers::device_pages::tests::versions_read_short` |
| R7 | server allows leading zeros | `install_now::tests::launcher_tags_read_as_the_shared_contract` |

Uncaught: none (22 of 22 caught). The Q-1 behaviour is not a mutation: it is how the code is written today, and no test
covers it (missing test 2).

## Findings

### Q-1 (Should) - the server offers Install Now to 0.36.0-rc.1/rc.2 phones that cannot take rc.4 to rc.8

`server/src/install_now.rs:278-289` (`release`). For a phone that reports an old-rule code, the server reads the tag
with today's parser. The phone reads it with its own, older parser. That build's `launcherTagVersionCode` is the old
formula for every tag, so `launcher-v0.36.0-rc.4` to `-rc.8` read as 35999 there.

- **A phone on 0.36.0-rc.1 or rc.2** reports 35999 with `install_now_v1` but without `same_code_update_v1`. Its
  self-update records rc.4 as ALREADY_RUNNING and never downloads it.
- **The server**, meanwhile, computes 360004 > 35999, sets `same_code = false` and returns `Standing::Behind`.
- **The parent's tap** gets "Already on 0.36.0-rc.2" back, which is exactly R2R-1 again. Before this branch the row
  was TooOld.
- **These phones only move** at 0.36.0 (old parser 36000) or 0.36.1-rc.1 (36000).

A phone on rc.3 is fine: it reads rc.4 as the same code and a newer name, has the capability, and installs.

Expected fix: judge the tag the way the reporting build does. When `report.app_version_code` is below 360_004
(the first new-rule code, and every old-rule build is below it), compute the tag's code with the old formula. That is
the existing old branch applied to any `X.Y.Z[-rc.N]`, and debug builds (116) fall under it too. Use that code for
`build_ahead` and `same_code`. A rc.1/rc.2 phone is then TooOld for rc.4 to rc.8, and an rc.3 phone stays Behind.
Add missing tests 1 to 3. The launcher needs no change: a new-rule build reads old tags correctly.

### Q-2 (Should) - the docs say old-rule phones move "by the code alone" and that "nothing is owed by hand"

- `PLAN.md:1563`: "so an installed rc.3 takes rc.4 by the code alone".
- `scripts/release-version.sh:15-16`: "so a phone on an old build updates to a new one by the code alone".
- `PLAN.md:1571`: the name rule stays for "a phone on 0.36.0-rc.1..3 looking at another old-rule build".
- `PLAN.md:1582-1586`: "since the own-versionCode rule ... the next RC, `launcher-v0.36.0-rc.4` (360004), is a higher
  code too, so nothing is owed by hand any more".

As Q-1 shows, an old-rule build compares the tag's old-rule code, not the APK's. So:

- rc.3 takes rc.4 to rc.8 through the legacy name rule. The name rule, `same_code_update_v1` and the server's same-code
  branch are load-bearing until no phone runs 0.36.0-rc.1 to rc.3. "Kept, harmless" in the doc comments is true, but
  the code must not be removed early.
- rc.1/rc.2 phones still need the one-time `adb install -r`, or must wait for 0.36.0.

Expected fix: correct those four places. Restore the owed step for rc.1/rc.2 phones in PLAN's RC-to-RC entry. Add a
line to the `SAME_CODE_UPDATE_CAPABILITY` / `SAME_CODE_CAPABILITY` comments saying they are needed while any phone
runs 0.36.0-rc.1 to rc.3.

### N-1 (Nit) - the contract names only three of the 16 real launcher tags

`server/testdata/launcher_tag_codes.json`. The rows for real tags are 0.34.0, 0.35.0-rc.1 and 0.36.0-rc.3 (plus
rc.1@70). Add a row for every real `launcher-v*` tag with its old code (table in §1). Then a later change to the old
branch cannot change a code a phone in the field reports. Both copies, one commit.

### N-2 (Nit) - root CLAUDE.md names modules, not the tests

`CLAUDE.md:87-90`. The neighbouring paragraphs name the test functions. Name
`install_now::tests::launcher_tags_read_as_the_shared_contract` /
`launcher_tag_codes_match_the_launchers_copy` and the launcher's ``a release tag's versionCode is the shared
contract's`` / ``the tag codes are identical to the server's copy``.

### N-3 (Nit) - the music cutover is in two literals with no shared check

`scripts/release-version.sh:28` (`MUSIC_FIRST=(0 5 0 4)`) and `server/src/handlers/device_pages.rs:288`
(`MUSIC_NEW_RULE_FROM: i64 = 50_004`). Each side tests its own literal. A comment in each that points at the other is
enough; music rows in a shared file would be more.

### N-4 (Nit) - music's build.gradle.kts still states the old rule

`music/app/build.gradle.kts:21-22`: "music-vX.Y.Z -> X*1_000_000 + Y*1_000 + Z, an RC minus 1". Change it the way the
launcher's comment was changed (`scripts/release-version.sh`, `X*10_000_000 + Y*10_000 + Z*10 + N`).

## Verdict

**Merge after a fix round**: Q-1 (server `release()` reading the tag with the reporting build's rule, plus missing
tests 1 to 3) and Q-2 (docs and the owed step). The nits can go in the same round. The script, the workflows, both
parsers, the cutover, the contract and its CI wiring are correct as they are.
