# Design 25 - code QA (the lock's glance page)

QA, 2026-10-12, of branch `lock-glance-25` (6cee3af2, 0b6f7731, bc4bac58, 760c9736, a2d92c00) on `main` c9462785.
I read the spec `25-lock-glance.md`, including its design QA, the decisions after it and the implementation status.
In the code I read:
- the launcher's diff, in full: `GlanceReader`, `GlanceStore`, `GlanceRows`, `GlancePanel`, `GlanceOpener`,
  `LockGlance`, `VisibleSender`, `PinLockActivity`, `LockActivity`, `MusicLockPanel`, `MissedCallNotice`,
  `MissedCallNotifier`, `BadgeListenerService`, the layouts, the strings and every test;
- the code around it: `PinLockRuntime.dispatch`, `unlocked` and `afterUnlock`, `lockLeave`/`rootLeave`,
  `HomeActivity.reconcileKioskMode`, `TimeRulesRuntime.snapshot` and `MdmSyncWorker`;
- the server's diff (the migration, `lock_glance.rs`, the handler, `build_policy`, the template, the tests) and
  `scroll-restore.js`;
- `scripts/smoke-test.sh` and `docs/testing/emulator.md`.

I checked these against AOSP 16: `PendingIntent.send`, `PendingIntentRecord.sendInner`, `ActivityStarter`'s abort
and lock-task results, the NMS hide/unhide paths for suspended packages, and when `NotificationListenerService` calls
`onListenerDisconnected`.

**Runs:**
- **Launcher:** `./gradlew testDebugUnitTest assembleDebug assembleRelease -PwarningsAsErrors=true`, with the throwaway
  `tsnet` stub, which was removed afterwards. Result: 939 tests, 0 failures, both builds green.
- **Server:** `cargo test` passed 553, and `cargo fmt --check` is clean. `cargo clippy --all-targets` gives 12 warnings,
  all from before this branch: the dead model fields in `models.rs`, `dns_engine.rs:54-55`, and the `sort_by` at
  `devices.rs:1297` (2026-08-15).
- **Probe:** a one-off `java` probe of `cleanGlanceText`'s whitespace rule, for #5. I deleted it afterwards.

**Verdict:** 0 High, 0 Medium, 7 Low. I found no way to open anything without the right code, to leave lock task, to
reach an app that isn't allowlisted or is suspended, or to get past a time rule. Nothing glance-related reaches a
log, the disk, the server, a crash report or a screenshot. The Lows are about consistency, missing tests and small
functional gaps.

## Findings

### 1. Low - the time-rule hand-over opens the app in the lock-task-root case, which the lock's own path refuses

- **Where:**
  - `launcher/.../lock/LockGlance.kt:287-289`: `HAND_TO_TIME_RULE_SCREEN` is decided before the `lockWasRoot` row, and
    `lockWasRoot` counts only `from == LOCK`.
  - `lock/GlanceOpener.kt:60-69`, `openHandedOver`, never looks at it.
  - `LockGlanceTest.kt:172-174` pins this: "wasRoot only counts from the lock".
- **Scenario:**
  1. The kiosk is on, and the PIN lock is the lock-task root. This is the 1 s fallback, for example after a boot where
     Home's `startLockTask` lost the "not in foreground" race.
  2. The screen-time budget is used up, so Element X is usable.
  3. The kid taps the Element X row and types the code.
  4. `unlock()` stops the lock task (`stopOwnLockTask`), and `go()` brings Home and hands the target over.
  5. `afterUnlock` then starts `LockActivity` (`NEW_TASK`) right on top of Home. Home may never resume, and so never
     roots lock task again (`reconcileKioskMode` runs in `onResume`).
  6. `LockActivity.onResume` sends Element X's intent, and the app runs outside lock task. Its own links, such as its
     notification settings, are then not stopped by lock task or BlockedAppActivity.
  7. Without a time rule the same unlock opens nothing (`"the lock was the lock-task root"`). The spec's reason, "the
     app would cover Home before Home re-roots lock task", applies here just as much.
- **Exposure:** bounded. Every app the rule doesn't leave usable is suspended. `LockActivity`'s own buttons for the
  usable apps already start them in the same state. So this is an inconsistency, not a new opening.
- **Fix (either):**
  - Carry `lockWasRoot` through the hand-over: `glanceOpenStep` returns NOTHING for `TIME_RULE_SCREEN` with
    `lockWasRoot` too.
  - Or, more robust for both paths: with `LockTaskChrome.kioskOn` and
    `ActivityManager.lockTaskModeState == LOCK_TASK_MODE_NONE` at the open, return NOTHING ("lock task not running").
  - Update `LockGlanceTest` and the device check "the kiosk-on root case" to cover a time rule.

### 2. Low - a refused send counts as opened: no launch fallback, and the row hides itself

- **Where:** `lock/GlanceOpener.kt:95-97`, `110` and `132-138`.
- **Why:** `PendingIntent.send(...)` throws `CanceledException` only for a negative result (AOSP 16 `PendingIntent`
  1082-1084). `ActivityStarter` (15+, the `BAL_BLOCK` path) refuses with positive codes:
  - `START_ABORTED` (102) for a blocked background start ("Abort background activity starts");
  - `START_RETURN_LOCK_TASK_MODE_VIOLATION` (101) for a lock-task violation without the kiosk block bit.
- **Scenario:** the visibility opt-in isn't honoured on some build (the API 34/35 device check), or a ticked app's own
  content intent leads to a package that lock task doesn't permit while "Block other apps" is off.
  - `send` returns normally, so `glanceOpenStep` never gets `sendFailed`, and the launch-intent fallback never runs.
  - `openedThroughGlance` marks the conversation opened.
  - The kid lands on Home with nothing open, and the row stays hidden until the next message. Its notification is
    still in the shade.
- **Fix:**
  - Use `sendAndReturnResult`, which is hidden, through the `OnFinished` path. Or check after the send that a
    foreground activity of the package came up.
  - At least, call `markOpened` only for the launch path (`startActivity` throws when it is refused), or not at all.
    Element X clears the room's notification itself when the room opens (spec §1.4 says so).
  - Add the "a refused send" case to the device checklist.

### 3. Low - `MissedCallNotifier.current` isn't learned again: after a restart during a no-calls rule, or when a snoozed notification comes back

- **Where:**
  - `calls/MissedCallNotifier.kt:90`: `refreshAtStart` = `recompute(mayPost = false, mayClear = false)`.
  - `calls/MissedCallNotice.kt:174`: `notice == null && !mayClear` gives `Keep`.
  - `badges/BadgeListenerService.kt:58-71`: only our removal is watched, not our re-post.
- **Scenario A (deviation 9's own case):**
  1. Mamma's call at 20:50 is missed. Our notification shows, and so does the glance row.
  2. Bedtime (calls off) begins at 21:00. The nightly self-update at 02:00-04:00 restarts the process.
  3. `refreshAtStart` computes against `effectiveState()`, which is emergency contacts only during the rule, so the
     notice is `null`.
  4. `Keep` leaves the notification in the shade, which is right. But `lastPosted` stays `null`.
  5. Nothing recomputes at the rule's end. In the morning the shade still has "Tapt anrop fra Mamma", but the glance
     page shows no missed-call row.
  6. This lasts until the next missed call, call end or phone-book visit. It breaks §2.4's "the glance shows exactly
     what our missed-call notification shows".
- **Scenario B:**
  1. The kid snoozes ours from the shade while unlocked. `removedFromShade` clears `current`, as Low 9 asked.
  2. When the snooze ends, NMS posts it again. No code of ours learns `current` from that post, so the row is missing.
- **Fix:**
  - In `refreshAtStart`, compute the notice against `CallPolicyStore.state` (the managed rules without the time rule).
    A notification on screen during a no-calls rule can only have been posted before it.
  - In `BadgeListenerService.onNotificationPosted`, when our own id 1006 is posted while `current == null`, run the
    same `mayPost = false, mayClear = false` pass.
  - Or run that pass at the time lock's end edge (`TimeRulesRuntime.recheck`).
  - Pure test: `noticeStep` with the start pass's view.

### 4. Low - missing tests for the fail-closed and glue decisions

- **The reader's "no ranking = suspended" (QA Medium 1) isn't pinned.**
  - Where: `GlanceReader.kt:139` (`suspended = !ranked || ranking.isSuspended`) and `:72`
    (`getRanking(key, ranking) != true || ranking.isSuspended`).
  - `LockGlanceGuardTest`'s identifier allowlist can't catch `ranked &&` replacing `!ranked ||`. `GlanceRowsTest`
    only tests the flag downstream.
  - Fix: move the two expressions into a pure `glanceSuspended(ranked, isSuspended)` and test it, or pin the source
    lines in the guard test.
- **Q1's usability rule is untested glue.**
  - Where: `GlanceOpener.usableUnderTimeRule`, `GlanceOpener.kt:77-84`. It decides both the tap and the hand-over: the
    app in `usableApps`; missed calls when `callsAllowed && managed`.
  - Fix: a pure `glanceUsableUnderLock(lock, target, callsManaged)` with tests, for the budget's messaging apps, a
    rule's exempt apps, a no-calls rule and unmanaged calls.
- **The policy order isn't tested.** `GlanceStore.refreshPolicy`'s rule "the cache read at start never overwrites a
  fresh policy" (`freshSeen`, `GlanceStore.kt:145-146`) has no test. Fix: a pure
  `nextGlancePolicy(current, freshSeen, incoming, fresh)`.
- **`removedFromShade` isn't tested** (`MissedCallNotifier.kt:98-103`): it keeps `current` when our re-post trails the
  removal. It is glue on the executor. A comment-level guard test would do.

### 5. Low - `cleanGlanceText` keeps Unicode line and paragraph separators

- **Where:** `badges/GlanceRows.kt:155-169`, and line 162 `Regex("\\s+")` in particular.
- **Why:** Java's `\s` is ASCII-only. U+2028/U+2029 aren't ISO control characters either. The probe showed both
  surviving the cleaning, and U+00A0 not being collapsed.
- **Scenario:** a Matrix display name or a text holding U+2028 (`"Pappa …"`) breaks the one-line title. Only the
  part before it shows, followed by an ellipsis. §2.2 says "whitespace collapsed".
- **Fix:** use `Regex("[\\s\\p{Z}]+")` (or `RegexOption`/`(?U)`), and drop format characters
  (`Character.getType == FORMAT`: U+200E/200F, U+2060-2064 and U+FEFF) along with the bidi ranges. Add a case to
  `GlanceRowsTest`.

### 6. Low - two wrong texts in the PWA subsection

- **Where:** `server/src/handlers/devices.rs:571-622` (`lock_screen_card`) and `server/templates/device_detail.html:350`.
- **Scenario 1:** a ticked package that is in the allowlist but neither reported installed nor a selected catalog app
  is not in `UnifiedAppRow`. It is listed as "io.example.app - not allowed - ticked", although it is allowed.
- **Scenario 2:** the phone reported its apps, the allowlist is `[]`, and the parent unticks Element X. The empty
  state then says "the phone hasn't reported its apps".
- **Fix:**
  - For the note, check the parsed allowlist. Use "not allowed - ticked" only when the package isn't in it, and "not on
    the phone - ticked" otherwise.
  - Choose the empty-state text from `latest_status`/`installed` ("No allowed apps to choose from" when there is a
    report).

### 7. Low - the music tile's progress ticks every second on the keypad stage

- **Where:**
  - `lock/GlancePanel.kt:232`: in the KEYPAD stage `music.show(fit?.tile ...)` keeps the last fit's FULL or COMPACT,
    because the fit returns early while the page is GONE.
  - `music/MusicLockPanel.kt:130`: `glanceTicks(shown != NONE, s)`.
- **Scenario:** music plays, and the kid taps "Lås opp". While they type, the hidden tile is re-rendered on the main
  thread once a second: art check, texts, progress and three buttons. The keypad's compact row has no progress.
  - Step 4 ticked only while the media view was shown (`lockMediaTicks(ui.mediaView, ...)`).
  - `glanceTicks`'s own KDoc says "only while it shows a tile".
- **Fix:** `GlancePanel.renderNow` passes `GlanceTileSize.NONE` (or a `pageShown` flag) while
  `state.stage == KEYPAD`. `glanceTicks(tileShown && pageShown, session)`, with a test.

## Migration renumbering

- The test suite names the number once: `server/src/tests/lock_glance.rs:12`, `const MIGRATION: i64 = 65`, used by
  `migrated_before(MIGRATION)`.
- A forgotten const doesn't fail the test. `finish()` still applies the renamed file, so the test then checks a bit
  less but stays green.
- The other mentions are prose: `server/src/lock_glance.rs:2`, `server/src/models.rs:113`, the header of
  `server/src/tests/lock_glance.rs:2`, `server/CLAUDE.md:632`, `PLAN.md:190`, and `25-lock-glance.md:822`.
- `grep -rn 0065` at merge finds them all.
- `relock-24b-s4` also has `0065_safe_boot_default.sql`. Whichever lands second must be renamed before both are on
  `main`.

## Verified OK

- **Nothing opens without the code.**
  - `pendingOpen` is set only in `unlock()`, and only after the right kid PIN or parent code with no screen-off since
    the check began. It is used and cleared only in `go()`, and only while `PinLockRuntime.mode == UNLOCKED`.
  - A refused `unlocked` (mode LOCKED), the switch-off, the crash guard and `onResume`'s leave have no target.
  - `go(rootLeave)`'s second pass has `pendingOpen = null`.
- **The hand-over.**
  - `openHandedOver` takes the hand-over at the first `LockActivity.onResume` and clears it.
  - It refuses after 10 s or while LOCKED, and checks usability again with a fresh `snapshot`.
  - A `LockActivity` that finishes in `onStart` (the rule ended) never resumes, so the hand-over expires (deviation 2).
  - A rule that begins between the tap and the unlock gets "not usable" and opens nothing.
  - A stale `lockReason` at the unlock only lets the kid open, a second early, what Home would open.
- **Time rules.**
  - The target is taken only when the app is in `TimeLock.usableApps`. A missed-call row's target is taken only when
    calls are managed and allowed.
  - At the open, the opener checks again that the app is installed, not suspended and not hidden
    (`dpm.isApplicationHidden`, because `getApplicationInfo` without MATCH_UNINSTALLED fails for hidden apps).
  - Suspended apps have no rows (§2.2), and they open nothing.
- **The creator check and PendingIntents.**
  - Only an activity intent whose `creatorPackage` is the notification's package is sent. A delegate's intent or a
    handed-over one falls back to the app's launch intent.
  - The fill-in intent is `null`, so a mutable or fillable PendingIntent gets nothing of ours.
  - Broadcast and service intents (a possible "mark read") are never sent.
  - Actions, remote inputs, delete and full-screen intents are never read (`LockGlanceGuardTest`). No cancel, snooze or
    `setNotificationsShown` is called anywhere.
- **`VisibleSender`.**
  - Its options moved without change: ALLOW_IF_VISIBLE on 36, and ALLOWED on 34/35.
  - The sends run from the resumed lock, after Home of the same uid, or from the resumed `LockActivity`. On 14/15/16
    the sender's visible activity is what counts (`hasVisibleActivity(uid)`, `mVisibleRequested`).
  - Our device-owner and HOME rights are never passed to the creator.
- **Lock task.** No new `stopLockTask`. The kiosk-on order "Home, then the app's NEW_TASK, then the lock's task
  finished" leaves the app on top inside lock task. A non-permitted target ends in BlockedAppActivity, or is refused.
- **The reader.**
  - Only ticked packages, own-user notifications and candidates are read, and a missing ranking counts as suspended
    on post and in the sweep.
  - Hide and unhide reach P+ listeners as ranking updates (AOSP 16 NMS 13875/13898).
  - Ranking updates refresh the flag.
  - The connect sweep uses `currentRanking`. A notification posted between the two reads heals at the next ranking
    update.
  - The text is read only in `readTexts` at TEXT, and never the kid's own messages.
  - In sender-only mode, notifications that aren't MessagingStyle show the app and the count only (Low 8).
  - SECRET maps to APP_ONLY.
  - A redacted (sensitive) notification falls back to the app's label.
- **Store, threading and memory.**
  - Every map is touched on the main thread only. `refreshPolicy` and `loadAtStart` post to it, and a fresh policy
    beats the cache.
  - A policy change clears and sweeps again, so switching the text off drops every text at once.
  - Disconnect clears, through `onDestroy` -> `onListenerDisconnected`. A Service never has two live instances, so a
    late disconnect can't wipe a new sweep.
  - Memory is bounded by the active notifications. The opened marks and `failedIcons` stay tiny.
- **Missed calls.**
  - `lastPosted` changes on the single executor, and its setter posts guarded listeners to main.
  - `removedFromShade` runs after anything queued and keeps a re-post, apart from #3.
  - `refreshAtStart` coalesces safely: OR-ing `mayClear` gives a broadcast's semantics.
  - One row per contact, the plain row for fail-closed or unmanaged with our dialer role, and Telecom's own
    notification never read.
  - `MissedContact`/`MissedCallNotice` print no names or numbers.
- **Crash safety.**
  - Each entry point catches `Throwable`, logged as the exception class only: the panel's
    `start/resume/stop/step/render/fit/tap/takeTarget`, its construction in `onCreate` (a null panel leaves the plain
    keypad), the icon job (`CancellationException` rethrown), the store's and notifier's listeners, `refreshPolicy`,
    `loadAtStart`, every reader entry, and `GlanceOpener.open/openHandedOver`.
  - "Nødsamtale" is the lock's own button.
- **Privacy.**
  - Every log line carries package names, counts, reasons or exception classes only (guard-tested).
  - Each glance type has a redacted `toString`.
  - Nothing goes to preferences, DE or CE, a DTO, the status report or a crash report. The status report carries only
    `lock_glance_v1`.
  - `onSaveInstanceState` keeps only the typed digits, and TextViews don't freeze their text.
  - `FLAG_SECURE` is set before inflation and never cleared, which covers the VoIP card in the same window; the
    parent-PIN dialog has its own.
  - The rows are data-sensitive down to every child. TalkBack and UiAutomation read them; other accessibility
    services don't.
- **Layout.**
  - `glanceFit` matches §1.2. The expected 445/464/394/458/460/493 dp hold in the test model.
  - The fit measures the bound views at the page's width after the insets. It runs again on every height change and
    never before the first layout, and the re-layout during layout happens before the first draw.
  - The round controls are fixed and square. The tile's 204 dp, the compact tile's 144 dp and a row's 144 dp (with
    "yesterday") leave at least 120 dp for text at 320/360/411.
  - The "more" line has `minHeight` 24 dp.
  - The keypad prompt has `maxLines="2"` and `ellipsize`, with the label cut to 20 code points.
  - Keys go down to the 32 dp floor rather than clip.
  - Font scale is measured on the device, not modelled (§9).
- **Server.**
  - Migration: table plus column. Element X is ticked for existing devices, and the trigger ticks it for every new
    device. The ticks cascade with the device, and nothing rewrites `device_policy` (no REPLACE), so the switch can't
    be reset by another save.
  - `build_policy` sends `apps: []` on an error, before the command pop.
  - Snapshot and compat tests run on both sides with the same literal.
  - The routes sit behind the admin auth layer, answer 404, 400 or 500 with nothing written, record a security event,
    nudge the phone and redirect to `/devices/{id}` without a fragment. `scroll-restore.js` hooks
    `form.submit()`, so no save jumps to the top.
  - The notes appear.
  - The label logic lists the allowed apps with an allowlist, every reported app without one, and the unlisted ticks
    with a note, except as in #6. It never lists Play core or the launcher.
- **Smoke test.**
  - `enter_pin` taps `lock_glance_unlock` when it is present, and falls through on the keypad stage.
  - `glance_missed_row` matches the en and nb descriptions.
  - `shot` notes the black screencap.
- **Not new, left as they were:**
  - the main-thread `TimeRulesRuntime.snapshot` at a row's tap (`LockActivity.render` does the same);
  - `PinLockActivity`'s `configChanges` (strings and sizes don't refresh without a recreation);
  - `emulator.md` A2's lock screencap, which is now black.
