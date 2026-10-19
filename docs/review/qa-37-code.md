# QA 37 (code): Install Now

Code QA, 2026-10-19. Under review: `85bc339e` (branch `install-now`, 3 commits on `c6232081`), server and launcher. I
read it against `docs/design/37-install-now.md` (with its "Implementation status" and the ten deviations),
`docs/design/qa-37-design.md` (the revision check R-M1..R-L4, R-N1/2), both CLAUDE.md "Install Now" sections,
`docs/testing/emulator.md` §6o, and the previous code QAs. Line numbers are `85bc339e`'s. No product code was changed.
Every mutation was reverted, and the tree was clean before this file was written.

**Verdict: sound, merge after a small fix round.** The user's model holds:
- one tap queues one `install_update`;
- the phone installs at once, screen on or off, waiting only for a call or an emergency;
- the download ignores Wi-Fi-only and roaming;
- the newest advertised release is installed and named in "Done";
- there is no expiry, and Cancel works while the command is unfetched;
- the button is only on the launcher's page, one per phone, with no notification.

I proved the safety property in the code (below), and the ordinary update path is untouched. What has to change:
- **M1**: right after "Done", the row falls back to "behind" with an **Install Now** button for a moment. The poll has
  already stopped, so the stale row stays until the page is reloaded.
- **M2**: Install Now's download still goes last, behind every catalog download, and it shares the run's failure
  counter with them.
- **L1**: for up to 2 minutes the row can say "Installing" while a call is actually holding the install.
- **L2**: the test gaps. 8 of 14 mutations survive, and among them is the key one: no test pins that the forced path
  still runs every APK check.

Counts: **0 High, 2 Medium, 3 Low, 3 nits.**

## Runs (all local, under `flock ~/build.lock`, ~4-5 GB free)

| Check | Result |
|---|---|
| server `cargo test -q -j 2` | ok, **1044 passed**, 0 failed |
| server `cargo fmt --check` | clean |
| server `cargo clippy --all-targets -q` | 13 warnings, the known ones on main; no new warning |
| server `node --test jstest/` | **56 passed** (7 of them `install-now.test.js`) |
| launcher `./gradlew --no-daemon --max-workers=2 testDebugUnitTest assembleDebug` (tsnet stub; no `tsnet.aar` on the VM) | BUILD SUCCESSFUL, **1371 tests**, 0 failed, 0 skipped (`InstallNowPlanTest` 13) |

Nothing went through CI.

## Safety: what I checked in the code

- **Every APK check still runs on the forced path.** In `MdmSyncWorker.kt` `commitPendingSelfUpdateIfDue`, `installNow`
  changes only two things:
  - `allowed` (:745): the hour's backoff is skipped, but the refusal is kept;
  - `decision` (:754, `commitDecision`).

  `pendingApkCheck(...)` (:774) follows unconditionally. It checks size, SHA-256, archive package, signer and
  versionCode (`SelfUpdatePlan.kt:141-160`), and every non-OK outcome returns before `recordAttemptStarted`. The
  download's own size and hash checks (`AppDownloads.finish`) are unchanged. `beforeCommit` re-runs `commitDecision`,
  and the fence (`UpdateFence.fenceBeforeCommit`) is untouched. **No test pins this** (mutation L1, finding L2).
- **The ordinary path is unchanged.** With `installNow == false`, both `allowed` and `decision` are the old calls
  (`pendingCommitAllowed`, `updateWindowDecision`). `waitForWindow` still arms only with the screen off, and
  `armIfPending`/`onScreenOn` change only while a record is active. The night window, the 24 h overdue path and
  debug's 2 minutes (`commitCheckDelayMs`, `overdueMs`) have no diff, and their `SelfUpdatePlanTest` cases pass.
- **A phone without `install_now_v1` is never queued.** `release()` makes such a phone `TooOld`, and `refusal` answers
  `too_old`. `every_refusal_lands_on_an_element_the_page_renders` asserts the word, the row's "works from this update
  on" text and no button. I didn't run a mutation on this one.
- **Server**:
  - Both POSTs and the JSON route sit behind full auth. The CSRF guard is the global `csrf_guard`; the anonymous POST
    is tested for install only (nit 1).
  - `device_command_queued` / `device_command_cancelled` are logged.
  - One waiting command per phone (`replaced_kinds`, mutation S2 caught).
  - `release_tag` is sent only for `install_update` (correct, but untested: S1).
  - `policy_json_keys_snapshot` is untouched; the contract files are unchanged.
- **The receipt/result flow across the kill**:
  - The DE record holds the id, `requested_at`, tag and `fromVersionCode`.
  - In the new process, `PackageReplacedReceiver` and the anchor's start sync both reach the settle. "Installed" means
    running > `fromVersionCode` and beats a recorded failure. `clearCommand` runs after `CommandAnswers.result`, so a
    lost answer waits in the outbox.
  - A redelivered command is not run twice (`CommandAnswers.begin`).
  - A reboot mid-download resumes: the record is in DE, and `gate()` reads it live.
  - A refused install answers through `AppInstallReceiver`.
  - The 6 h call cap and the 2-minute alarm with the screen-off re-arm (R-L1) are in place.
  - R-L2's three commit outcomes sync or answer.
  - The prompt failures are in place: 3 retries then `download failed`, the second hash mismatch, `no space`, and
    `refused: <check>`.

  `command_result` is last-writer-wins, not idempotent (nit 3); in practice a repeat carries the same text.

## Findings

### Medium

**M1. After "Done", the row shows Install Now and the old version until a manual reload.**
- Cause: in one sync the launcher answers before it reports. `checkForTrackedAppUpdates` (`MdmSyncWorker.kt:199`, which
  ends in `settleInstallNow`, :644, so `CommandAnswers.result` runs there) comes before `api.sendStatus` (:205). The
  server's `offer` (`server/src/install_now.rs:247`) reads an answered command as `Standing(release(tag, report))`
  against the *latest report*, which is still the pre-install one: `app_version_code` 1.3.2.
- What the parent sees: if the 5 s poll lands between the result and the report, the state word changes from "working"
  to "idle". The page reloads, and the row reads "1.3.2 · 1.4.0 is waiting" with **Install Now**, next to "Install Now
  · Done · Installed 1.4.0". The reloaded page has no poll (`install_polling` is false), so this stays until a manual
  reload. If that status report fails (best effort), it stays until the next report, up to the 30-min backstop. A tap
  then sends a pointless command, answered "Already on 1.4.0".
- `the_answer_shows_in_the_row` posts the report *before* rendering, so it misses this.
- Fix (server, smallest): in `offer`, while the newest command is Done with a `result` of `installed: …`, and the
  report's `reported_at` is older than `acknowledged_at`, give
  `Offer::Working { words: "Installed - waiting for the phone's report" }`. The poll then continues and reloads when
  the report lands. Alternatively, the launcher could flush Install Now's answer after `sendStatus`.
- Test: post the result, render without a new report, and assert no `>Install Now</button>` and `state == "working"`.

**M2. Install Now waits behind catalog downloads, and a failing catalog app can starve it.**
- Cause: `downloadOrder` (`AppDownloadPlan.kt:258`) still sorts the launcher last. In the run loop, `failures` is one
  counter for the whole run (`AppDownloads.kt:512`), and only a give-up whose `next` is the launcher fails Install Now.
- On Wi-Fi, a queued 300 MB catalog app downloads first, so "at once" waits for it.
- Worse, a catalog app whose downloads keep failing ends every run (3 retries, then "waiting for the next trigger")
  before the launcher's turn. The row then says "Downloading" or "The phone got it" with no answer until "No answer" at
  25 h.
- Fix: while `InstallNow.active`, `downloadOrder` puts the launcher first (a pure parameter, testable), and the give-up
  counts per record.

### Low

**L1. "Installing" while a call holds the install, for up to 2 minutes.**
- Cause: the status report goes out before the commit step, in the same sync. When `commitDecision` then waits on a
  call, `waitForWindow` (`MdmSyncWorker.kt:888`) sets `lastWait = CALL` and arms the 2-minute alarm, but asks for no
  report. So the report the server holds carries `install_now_ready` ("Installing") until the alarm's sync.
- This is the "honest row words" point of the design QA's M2.
- Fix: on the first move into a wait on the Install Now path (`lastWait` changed), request one sync, coalesced, so the
  report carries `install_now_call` / `_emergency`.

**L2. Tests: 8 of 14 mutations survive** (table below). Add:
- (a) **A pin that the forced path runs `pendingApkCheck`**. Best is a pure helper for the commit step's choice, or a
  source pin that `val check = pendingApkCheck(` is unconditional and that no `installNow` appears in that statement.
  This is the safety property of the feature.
- (b) The record stored at the dispatch, and the superseded answer: extract `startInstallNow`'s decision as a pure
  `installNowOnReceipt(old, pending)`, or pin the lines.
- (c) `AppInstallReceiver`'s failure answer and the commit step's CORRUPT → `download corrupt` (R-L2): source pins, as
  the other glue lines already have.
- (d) Server: an `allow_blocked` row *with* its payload, asserting no `release_tag` (the current test uses a
  payload-less ring, so S1 survives).
- (e) The app page's new 500s: make the `tracked_apps` row read fail, and the phones read fail. Today
  `the_launcher_page_and_its_routes_fail_closed` only renames `device_commands`, so reverting either read to the old
  `unwrap_or_default` / `.ok().flatten()` passes.

**L3. The answer test for "Done" posts the report first.** This is the same root as M1. Keep that test, and add M1's
ordering as its own case.

### Nits

1. `only_the_launchers_page_and_only_an_admin`: add the anonymous Cancel POST.
2. `SelfUpdate.onCallsChanged` reads the DE prefs (`InstallNow.active`) on the main thread; only the first read touches
   disk. Fine as it is, but it could hop to the scope.
3. The refusal check and the queue in `handlers::install_now::install` are not one transaction. A pop in between leaves
   the fetched command used up and a second one queued, which the phone answers "superseded". It is harmless and says
   so; mention it in a comment. Separately, `command_result` overwrites an earlier answer (last-writer-wins). An outbox
   retry carries the same text, so this is acceptable.

## Mutations (each one reverted)

Server mutations ran as `cargo test` (full suite for the survivors). Launcher mutations ran as `testDebugUnitTest`
(full suite for the survivors). Independent mutations were batched by the test they would trip: each failing test maps
to one mutation, and a batch with no failure means every mutation in it survived.

| # | Mutation | Caught by |
|---|---|---|
| S2 | `replaced_kinds` without `install_update` (no replace) | `tests::install_now::install_now_queues_one_command_with_the_tag_and_the_pop_sends_it` |
| S3 | the redirect ignores "set up" (`not_set_up` lands on `#phone-N`, a fragment that doesn't exist) | `every_refusal_lands_on_an_element_the_page_renders` |
| S5 | `received_overdue_minutes` always 10 (no 25 h) | `install_now::tests::the_offer_follows_the_newest_install_now`, `a_server_start_keeps_it_and_a_confirmed_one_works_for_25_hours` |
| S1 | the pop sends `release_tag` for every command | **survives** (L2 d) |
| S9 | the launcher page's phones read back to `unwrap_or_default` | **survives** (L2 e) |
| S13 | the app read back to `.ok().flatten()` (404 instead of 500) | **survives** (L2 e) |
| L2m | `installNowDecision` ignores a live call | `InstallNowPlanTest` "only a call or an emergency holds it" |
| L5m | "Done" with the old versionCode (`>=`) | `InstallNowPlanTest` "the settle answers…" |
| L10 | the download override stays on after a failure (`active` → `load`) | `InstallNowPlanTest` "every gate in AppDownloads goes through gate" (a string pin) |
| L1m | the forced path skips `pendingApkCheck` (signer, package, version, hash) | **survives** (L2 a) |
| L4m | the record is never stored at the receipt (the receipt goes out, nothing runs) | **survives** (L2 b) |
| L6m | `AppInstallReceiver` doesn't answer a refused install | **survives** (L2 c) |
| L7m | CORRUPT at the commit answers nothing (R-L2) | **survives** (L2 c) |
| L8m | a superseded record is never answered | **survives** (L2 b) |

**Score: 6 of 14 caught.** The pure rules (the gate, the settle, the gate override, the server's offer, refusals and
redirects) are well pinned. The launcher glue and two server branches are not.

## The implementer's deviations

1. `not_reported` refusal word: **right**. §2.1 lacked a word for `NotReported`, and it lands on the row.
2. `in_progress` also for a Sent command: **right**. A second command for a phone that has the first would only be
   answered "superseded". A lost delivery gives the button back.
3. The poll: **right**. It runs every 5 s while visible, stops in the background, gives up after 360 polls, and reloads
   in place on a change (never mid-save or with a `<details>` open), all pinned by `install-now.test.js`. Copying 16
   lines of `auto-reload.js` is acceptable, since that file installs itself. M1 is a server-side gap, not the poll's.
4. The 6 h call cap (R-L4): **right**. It uses elapsed realtime, a reboot restarts it, and it answers "Not installed: a
   call stayed on".
5. R-L2's three outcomes: **right**. They are in the code; untested (L2 c).
6. R-L1's screen-off: **right**. `armIfPending` arms `installNowRecheckMs(lastWait)`, and screen-on keeps the alarm
   (source-pinned).
7. R-N1/R-N2: **right**. The DE reason is restated, and the `CatalogRefusal` bullet is dropped.
8. A superseded record with its own failure answers that failure: **right**.
9. The redirect table in `tests/install_now.rs`, not in `tracked_apps`' table: **right**. Posting the launcher's "This
   is the launcher app" switch mid-table would break the table, and `check_forms` + `assert_lands` cover every word.
10. `render_detail_full` fails closed on every app's page: **right**, by the fail-closed rule. Neither new 500 is tested
    (S9/S13, L2 e).
11. The working words also cover roaming: harmless and correct.

The two words added to the event scan's `NOT_EVENTS` (`no_release`, `unknown_phone`) are **the right fix**. They are
notice codes within the scan's 15-line window around `record_security_event`, the same as the existing notice codes
there (`not_a_domain_*`, `parent_pin_*`). The heuristic is crude, but making a list exception is the established way.

## Owed to device checks

- The emulator run of `docs/testing/emulator.md` §6o, items 1-7. In item 2, look for M1: the row right after Done.
- On the Jelly Star:
  - a daytime install with the update fence off (the stock home screen, and the unfiltered calls for about a minute,
    as the ⓘ says) and on;
  - the download on real mobile data with "Wi-Fi only" on;
  - the 2-minute alarm catching the end of a VoIP or Telecom-only call;
  - the alarm with the screen on in Doze;
  - "Done" over tsnet arriving after the next unlock.
- On the iPhone PWA: the poll stopping in the background and reloading in place.
