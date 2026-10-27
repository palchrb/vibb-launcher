# Code QA: nf-keep-log (the notification filter logs a Keep's reason and checks that a cancel took)

Branch `nf-keep-log` (9e04fc84, one commit, cut from main 8b1252c0) against main `8baaf932`. Reviewer's branch
`qa-launcher-small-b` (cut from `day-task`'s head) carries this file with the two other small reviews. Design: PLAN
"Fix backlog" ("The notification filter kept Play services' cross-device welcome nag with the switch ON", the user's
2026-10-27 diagnostic: the nag was kept with the filter on and nothing said why - the next time it must be in logcat).

## Setup

- Worktree on 9e04fc84. I read `git diff main..nf-keep-log` in full, and the code around it: the whole
  `BadgeListenerService` (every callback, `sweep`, `applyRule`, `facts`, the two `activeOrNull`s), the whole
  `NotificationRuleRuntime` (`refresh`, `attach`/`detach`, `act`, `report`), `NotificationRule.kt` (`nagVerdict`,
  `groupSummaryCancellable`, `NagCounts`, `nagLogDue`), `AppEnforcer.essentialNotificationPackages` and `resolveHelpers`'
  log lines, and emulator.md's B6 and §7.
- Launcher, full suite under the lock (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2
  -PwarningsAsErrors=true assembleDebug testDebugUnitTest`): **BUILD SUCCESSFUL in 2m 39s, 1882 tests, 0 failures,
  0 errors, 0 skipped** (main: 1878; `NotificationRuleTest` 21, four new). The only warning is kapt's `RELEASE_11` line.
- Mutations: three batches by an exact-replace script, at most one mutation per test method in a batch, each run
  `testDebugUnitTest --tests 'com.kidslauncher.mdm.badges.*'` (86 tests). The sources were put back with
  `git checkout -- launcher/app/src` after each batch, and `git status` was empty before this file.
- No emulator (as briefed): B6 (`docs/testing/emulator.md`, Notifications) is the emulator's step - the shell
  notification vanishing within 1 s with its `cancel com.android.shell / shell_cmd` line, the `kept` lines, the
  `platform kept` line, and the real GMS nag's line on the user's phone.

## What was checked and holds

- **Nothing new logs a key, title, text, tag or group key.** Every new `Log.i` is read: `kept <package> / <channel or
  ->: <label> (N so far)` (`logKept`), `platform kept <package> / <channel>` (`checkAfterCancel`), and the labels are
  the reason's name, `ALLOWED via <set names>` or `FACTS_FAILED <exception class>`. `checkAfterCancel` holds the key in
  memory for one second (the lambda's `key`), never prints it, and `activePostTime(key)` asks the platform only for that
  key. The group-summary branch still reads the group key in memory only.
- **The status report is byte-for-byte what it was.** `report()` and `NagCounts` are untouched; `KeepCounts` is a
  separate object the report never reads or resets, so `notification_cancels` keeps its numbers and the once-per-process
  reasons stay once. `act()`'s cancel/snooze lines and their 1st/10th/100th rule are unchanged.
- **The main-thread rule.** `kept()` is set lookups plus one synchronised map operation; `keptFactsFailed`/
  `keptGroupChild` the same; `checkAfterCancel` only posts to the main handler. The delayed lambda's one binder call,
  `getActiveNotifications(keys)` for one key, is the call `recount()` already makes for every notification on every
  post. No PackageManager work, and `policy` is the cached volatile.
- **No leak at unbind.** `detach(service)` removes every pending check by the one token and zeroes the counter;
  `onListenerDisconnected` and `onDestroy` both call it. A lambda posted by an instance that detached late (a new
  instance already attached) finds `listener !== service` and returns after `done()`; and every lambda is at most one
  second away anyway. The service reference lives only in those lambdas.
- **`nagVerdict`'s order is unchanged** (the diff doesn't touch it); `NagPolicy`'s three new sets are derived from
  the same inputs as `allowed` (`allowlist + messaging + lockUsableApps`), so `changed = next != policy` doesn't churn,
  and the verdict still reads `allowed` alone (`allowedBy` is only the label).
- **The GMS case produces a line for every reachable path.** With the policy known and active (`Rule active: 9
  essential packages` was in the user's log), a clearable `AUTO_CANCEL` nag at importance 4 can end in: `ONGOING`/
  `NOT_CLEARABLE`/`FULL_SCREEN`/`INSISTENT` (once per package+channel), `ESSENTIAL_CATEGORY`/`ESSENTIAL_PACKAGE`/
  `ESSENTIAL_CHANNEL`/`ALLOWED via ...` (1st/10th/100th), `FACTS_FAILED <class>`, `GROUP_CHILD_KEPT`, a `cancel`/
  `snooze` line (unchanged), `Couldn't remove a notification of <pkg>: <class>` (unchanged, tag `BadgeListener` - Q-4),
  or `platform kept`. The silent ones (`POLICY_UNKNOWN`, `SWITCHED_OFF`, `UNMANAGED`, `OWN`) hold phone-wide and are
  covered by the `Rule active/inactive` edge line; a nag posted before the first `apply()` is re-judged by that
  apply's sweep. One silent path is left (Q-1).
- **The bounds.** `KeepCounts` keeps 128 triples; the oldest-recorded triple goes first and comes back at 1 (a line,
  never silence); `kept()` returns before recording for the silent reasons, so they take no slot. The post-cancel
  checks cap at 8 in flight (`PostCancelChecks`), given back in the lambda and cleared on detach.
- **B6 matches the code**: the rates, the once-per-pair reasons, the never-logged four, the `platform kept` rule.

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| K1 | `keepLogDue`: ONCE reasons log at every count (`>= 1`) | `a keep is logged by its reason ...` |
| K2 | `keepLogDue`: the SILENT branch dropped (falls to `nagLogDue`) | the same |
| K3 | `allowedBy`: `messaging` never named | `an ALLOWED line names the set ...` |
| K4 | `allowedBy`: joined with `,` | the same |
| K5 | `platformKeptAfterCancel`: `<=` -> `<` (the same post not kept) | `the post-cancel check ...` |
| K6 | `platformKeptAfterCancel`: gone counts as kept | the same |
| K7 | `KeepCounts`: no eviction | `keep counts run per (package, channel, reason) ...` |
| K8 | `PostCancelChecks.take`: nine in flight | `the post-cancel check ...` |
| K9 | glue: `applyRule` never calls `kept()` | **not caught** |
| K10 | glue: `detach` keeps the pending checks | **not caught** |
| K11 | glue: no `checkAfterCancel` after a cancel | **not caught** |

Every pure function the brief names is pinned on both sides of its rule; the glue that calls them is not (Q-3).

## Findings

### Q-1 (Low) - one silent keep is left: the group summary whose children can't be read

`launcher/app/src/main/java/com/kidslauncher/mdm/badges/BadgeListenerService.kt:127` (`(active ?: activeOrNull() ?:
return)`).

What's wrong: the branch says "every silent return names itself", but when a summary's children must be read and
`getActiveNotifications()` throws (unbound in between, a `RemoteException` the service caught), `activeOrNull()` is
`null` and the summary is kept with no line at all. Scenario: GMS posts the welcome nag as a group summary (it groups
most of its notifications), the listener's read fails once, and the user's next logcat again holds no line for the
package - the exact hole this branch closes for the other returns.

Expected fix: `?: run { NotificationRuleRuntime.keptActiveUnreadable(facts); return }` through `logKept` with a reason
`ACTIVE_UNREADABLE` (the `nagLogDue` rate), and one more row in B6's list.

### Q-2 (Low) - `ESSENTIAL_PACKAGE` names no route

`NotificationRuleRuntime.kt:138-142` (`kept`), `AppEnforcer.kt:587-620` (`essentialNotificationPackages`).

What's wrong: the resolved essentials come from eight sources (the system and default dialers, the emergency dialer,
Telecom, the cell-broadcast helper, the alarm app, the SMS app, the two cell-broadcast receiver queries, the IMEs), and
nothing logs the set's members - only `Rule active: N essential packages`. The user's PLAN entry already asks "GMS lands
there if it registers a cell-broadcast receiver on this image"; an `ESSENTIAL_PACKAGE` line answers "it is in the set"
and leaves "by which resolver" for another round. `ALLOWED` got exactly this treatment (`allowedBy`); the essentials
didn't.

Expected fix: log the resolved set by name when it changes (`refresh`, next to `Rule active` - they are system package
names, nothing private), or keep `essential` by source in `NagPolicy` and name the source in the line as `allowedBy`
does ("ESSENTIAL_PACKAGE via sms_cb").

### Q-3 (Low) - the glue around the new rules is unpinned

`BadgeListenerService.kt:119, 140`, `NotificationRuleRuntime.kt:117`, `NotificationRuleTest`.

What's wrong: K9, K10 and K11 survive. Without the `kept()` call the whole diagnostic is silent again (K9); without
`detach`'s removal a check can run against a service the framework unbound (K10, a 1 s window); without the
`checkAfterCancel` call the `platform kept` line never exists (K11).

Expected fix: three `contains` pins in `NotificationRuleTest`: `NotificationRuleRuntime.kept(facts, verdict.reason)`
inside `applyRule`, `NotificationRuleRuntime.checkAfterCancel(this, sbn.key, sbn.postTime, facts)` right after
`cancelNotification(sbn.key)`, and `main.removeCallbacksAndMessages(postCancelToken)` inside `detach`.

### Q-4 (Low, docs) - the one refusal line the diagnostic doesn't catch is under another tag

`BadgeListenerService.kt:146` (`Log.w("BadgeListener", "Couldn't remove a notification of ...")`),
`docs/testing/emulator.md` B6 (`adb logcat -s NotificationRule`) and §7's grep (no `BadgeListener`).

What's wrong: a cancel the platform refuses with an exception (not silently) logs under `BadgeListener`, which B6's
`-s NotificationRule` and §7's reporting grep both leave out. Scenario: the user pastes B6's output, the GMS cancel threw
a `SecurityException`, and the paste shows neither a `cancel` line (`act()` logged it before the throw - that one is
there) nor why it didn't take.

Expected fix: move that line behind `NotificationRuleRuntime` (tag `NotificationRule`), or add `BadgeListener` to B6's
`-s` list and to §7's grep.

### Q-5 (Nit) - the `ALLOWED` label reads the policy a second time

`NotificationRuleRuntime.kt:140`: `allowedBy(policy, ...)` re-reads the volatile; `applyRule` has the policy the verdict
used. A `refresh` landing between the two could print another set or `unknown`. Pass it in (`kept(facts, reason,
policy)`).

### Q-6 (Nit) - a sweep's burst skips checks silently

`checkAfterCancel` returns without a word past 8 in flight: at a connect sweep that cancels 20 nags only the first 8
are checked. Fine for the diagnostic, but one `post-cancel checks skipped (N)` line per burst would say so.

### Q-7 (Nit) - `KeepCounts`' KDoc says "the oldest forgotten"; the code is LRU

`record` does `remove` then put, so a triple recorded again moves to the end and the least recently recorded one goes -
the better choice, and the test can't tell the two apart. Say "least recently recorded".

## Docs

PLAN's entry and emulator.md B6 match the code (the line formats, the rates, the silent four). launcher/CLAUDE.md's
"Notification rule" bullet (`Privacy: ... logs carry package/channel and are rate-limited (1st/10th/100th)`) still
holds; it could name the `kept` and `platform kept` lines and `KeepCounts` in one sentence.

## Verdict

**Merge.** Nothing Medium: the purpose holds - the next GMS nag produces a line that names its reason, and nothing new
leaks a key, title or text; the report, the verdict's order and the main-thread rule are untouched. Q-1 and Q-2 are
the two worth a small follow-up before the user's next logcat (one silent path left, and `ESSENTIAL_PACKAGE` without
its route); Q-3 and Q-4 can ride with them.

Suite results, verbatim: `BUILD SUCCESSFUL in 2m 39s` - 1882 tests, 0 failures, 0 errors, 0 skipped.
