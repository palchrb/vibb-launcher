# Light code QA: pause-end-report (a pause ended on the phone reaches the PWA at once)

Branch `pause-end-report`, commit 245758e2 (`git diff 245758e2^..245758e2`), worktree `/workspace/wt-pause-end`.
Read-only review: no build, no cargo/Gradle (implementer's run: server 1729/0, launcher 2077/0). I read the diff and
the code around it: `WindowRelock.relock/check/lockAgain/relockInSync`, `runRelock` and `RelockStore.complete`,
`RestrictionsPause`, `SyncRunner`/`SyncCoalescer`, `CommandListenerService.requestSync`, `performMdmSync`'s relock and
command order, every caller of `check`/`lockAgain`/`lockAgainAsync`, the server's status INSERT, `parse_db_utc` and
every reader of `restrictions_paused`.

**Verdict: approve. 0 High, 1 Medium, 2 Low, 5 Info.** No loop, no storm; every end path reaches `relock()`. The one
thing to fix before relying on the 2 h cap is that its 2 h is a copy of the launcher's with nothing pinning the two.

## Medium

### M1 - The server's 2 h is a copy of the launcher's, pinned by nothing
`server/src/models.rs:509` (`RESTRICTIONS_PAUSE_MAX_MS`) vs `launcher/.../server/RestrictionsPause.kt:13`
(`RESTRICTIONS_PAUSE_DURATION_MS`). Today they agree, and I found no other pause length on either side: the pause is
only started by `ParentPin` (`windowDuration`, ParentPin.kt:155) and by the `start_pause` command
(MdmSyncWorker.kt:515), both with the one constant; the PWA's "Unlock 2 h" sends no duration; the offline override
(`OVERRIDE_DURATION_MS`, 2 h) is not reported as `restrictions_paused`; Play install mode is 15 min and has its own
`install_mode_until_ms`. But the repo pins every other cross-side number with a shared contract file (CLAUDE.md lists
a dozen), and this one is exactly the kind where drift is silent and parent-facing: launcher moves the pause to 3 h,
the server shows "not paused" and hides "Lock again" (`lock_again: capable && open`, window_relock.rs:75) for the last
hour while the phone is open.
Scenario: a future "Pause 4 h" is added to the launcher only; after 2 h of the same report the Attention row,
Quick actions and the Lock-again button vanish for a phone that is still unrestricted.
Fix: a one-line `pause_max_ms` in a shared testdata file (or in an existing one), checked by a server test and a
launcher test, or at least a comment at the launcher constant naming `RESTRICTIONS_PAUSE_MAX_MS` and a test that
greps both.

Related, accepted trade-off to note in the docs (not a bug): the cap counts from the *latest report*, not from the
pause's start. A pause only shows paused in a report sent after it began, so `report + 2 h >= pause end` holds for
the normal case, and a report older than 2 h really is a pause that is over. The one hole is a pause **re-entered
(PIN, "starts a new window") while the phone is offline** after its last `paused=true` report: report 10:00 (pause to
11:50), offline, PIN again 11:30 (pause to 13:30), the server shows "not paused" from 12:00 to 13:30 although the
phone is open. Before the change the stale `true` would have shown it (right by luck, wrong the other way for an end
that never got through, which is what the change fixes). It needs an offline phone with a parent at it, and an offline
phone is shown as offline anyway, so Medium at most; consider saying "last report N min ago" on the row when the
newest report is over, say, 90 min old.

## Low

### L1 - A relock that keeps its marker gives no early sync, though the pause is already gone on the phone
`WindowRelock.kt:370`, `WindowRelockPlan.kt:64` (`cleared &&`). If `apply()` or the signer check throws,
`complete(..., keepPending = true)` returns false, nothing is requested, and `restrictionsPaused` is already false in
any report (`RestrictionsPause.clear()` ran in `lockAgain`, and `isActive()` self-clears on expiry). So with a failing
apply the parent still waits for the backstop (up to 30 min, then the 2 h cap) to see the pause end, which is the
symptom this change fixes. The gate is right for the loop it avoids (retries run on every screen-on/timer; the sync's
own re-check would ask again each time), but the user-initiated paths cannot loop. Option: also request a sync in
`lockAgain` (Home, notification, Settings, `end_windows`) regardless of `cleared`, and keep the `cleared` rule for the
timer, screen-on and boot paths. Low: needs a failing `apply()`, which already raises its own alarm in the report.

### L2 - Tests: the rule is covered, the wiring and four of five readers are not
- `WindowRelockPlanTest` "a pause ended outside the sync..." (lines ~454-470) is meaningful where it calls
  `runRelock` (second relock returns `report == null`, so no second request; `applyThrows` gives `cleared == false`),
  but `syncAfterRelock` itself is a one-line boolean, so the first/third asserts mostly restate it.
- `WindowRelockCallersTest` is a source scan (string order and presence), the project's usual style for wiring. It
  does not catch the real risk of this change, a path that ends the window without going through `check()`; I
  checked those by hand (Info I2).
- Server: `a_pause_goes_with_its_report_or_the_clock` (tests/attention.rs:42) tests `paused_at` only through the
  Attention row, at 119/121 min. `devices.rs:2021` (summary), `device_pages.rs:2006` (apps page), and
  `handlers/window_relock.rs:75/109` (Lock-again button and the "waiting for the phone" reload) have no test of the
  cap, and there is no direct unit test of `paused_at` for the exact 2 h edge (`<`, so 2:00:00 is already over, the
  right way), an unparseable `reported_at` (`is_none_or` keeps the report's word, the safe way for "paused"), or a
  future `reported_at`. A small `models.rs` unit test would cover all three and make the five readers' shared
  behaviour one assertion.

## Info (checked, holds)

### I1 - No loop, no storm, offline cost is bounded
relock -> sync -> relock cannot repeat: the request happens only when `runRelock` returned a report with
`cleared == true`, i.e. a window was due and the marker was just cleared. The sync's own `relockInSync` and its
posted `TimeRulesRuntime.recheck` -> `check()` then find `relockDue == false` and return `RelockOutcome(null, false)`,
which the `outcome.report ?: return` at WindowRelock.kt:~366 exits before the new line. A window must be opened again
(PIN, 2 h) before another request, so SCREEN_ON/BOOT/TIMER storms add nothing: they only retry while the marker is
pending, and those retries have `cleared == false`. Repeated "Lock again" presses: the second finds nothing open and
asks for nothing. Offline: one `SyncRunner.request` per ended window (same cost class as `pin_event`/`foreign_access`),
requests collapse in `SyncCoalescer`, the wake lock is the existing timed one; an offline sync fails at `fetchPolicy`
and the backstop alarm re-arms. Starting the anchor FGS from the background is the existing device-owner path and a
failed start is handled in `SyncRunner.request`.

### I2 - "Inside the sync" detection is sound, and every end path reaches `relock()`
`relock()` is private with exactly two entries: the worker (`check`) and `relockInSync` (trigger `SYNC`). `syncAfterRelock`
reads the queued `trigger`, not `asked` (`askedTrigger` from `lockAgain`), so a Lock-again whose relock the sync
happens to run (it takes `askedTrigger` but is labelled `SYNC`) correctly asks nothing: its report is sent right
after, built from `RestrictionsPause.isActive()` (MdmSyncWorker.kt:291). A worker relock that lands while a sync is
already running asks for one more run (coalesced), which is right if that sync's report was built before the pause
ended. End paths: Home's banner (HomeActivity.kt:338), the notification (WindowRelock.kt:750), the Settings switch
(SettingsFragmentLauncher.kt:301) and `end_windows` (MdmSyncWorker.kt:503) all go `lockAgain`/`lockAgainAsync` ->
`check`; expiry by itself, screen-on, boot and the time-rule re-check go `TimeRulesRuntime.recheck` -> `check`
(boundary alarm armed by `windowEndMs`). The only other `RestrictionsPause.clear()` is `isActive()`'s self-clear, which
leaves the marker pending for the next `check`. `end_windows` inside a sync may now cost one extra coalesced run next
to its existing `requestFollowUpSync`; harmless.

### I3 - Clock skew and a future report time fail safe
`reported_at` is the server's `datetime('now')` default on insert (device_api.rs, status INSERT), so the phone's clock
cannot skew it, and the newest row is chosen by `reported_at DESC, id DESC`. A `reported_at` in the future (the server
clock stepped back) gives a negative age, `< MAX` holds, so it reads as paused for that much longer: the safe
direction. Unparseable text keeps the report's word (`is_none_or`). `parse_db_utc` accepts both stored formats.

### I4 - The five readers are consistent
All raw reads of `restrictions_paused` outside the INSERT and `models.rs` go through `paused_at`: `attention.rs:561`
(`now_ms` of `facts_of`), `devices.rs:2021`, `device_pages.rs:2006`, `handlers/window_relock.rs:75` (`open`) and `:109`
(`paused`). The card's `open` and `paused` use the same `now_ms`, so `waited_for` cannot see them disagree. Nothing
else (PWA JS, templates, other SQL) reads the column. Install mode's own `install_mode_until_ms > now` is unchanged.

### I5 - Housekeeping
The commit does not touch PLAN.md; per the working rule the queue entry ("in build") is marked done in the merge
commit. The launcher and server CLAUDE.md lines are accurate for what the code does.
