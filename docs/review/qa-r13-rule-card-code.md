# Code QA - launcher round 13 (the rule card, screen time left in the anchor notification, "Be om mer tid")

Reviewed on `qa-r13-rule-card` = `r13-rule-card` at d614fe9c (2026-10-25): the three step commits d057cb91 (step 1,
launcher), 537f3de0 (step 2, both directories), d614fe9c (step 3, launcher) on 77089563, against
`docs/design/r13-launcher-rule-card-and-time-left.md` (D1-D6, U1-U7, R13-1..14, "Implementation status" with its six
deviations), `docs/design/qa-r13-design.md`, `CLAUDE.md`, `launcher/CLAUDE.md` and `server/CLAUDE.md`. Launcher paths are
under `launcher/app/src/main/java/com/kidslauncher/mdm/` unless they start with `launcher/`, `server/`, `docs/` or `.github/`.

## Verdict

**Merge after fixes.** The branch does what the note decided, and the suites are green. Every decided point (D1-D6, U1-U7)
and every design-QA fix (R13-1..14) is in the code. The six deviations are sound (below). There is one Medium: the note
and PLAN plan to ship the server part as 0.49.0, but `main` already released `server-v0.49.0` without it. There are six
Lows: two are wrong states a parent can see, two are gaps in robustness and two are gaps in the tests. The merge into
today's `main` conflicts in three files, all mechanical.

## Fidelity

Checked point by point against the note. Everything below is present and correct unless a finding says otherwise.

- **D1/D2**: `ruleLabel` returns `Named`/`Kind` and both screens resolve it (`LockActivity.labelText`). The time-rule
  screen's "Til" line is `lockEndOf(snap)` inside its own try/catch (`ui/LockActivity.kt:164-172`), so the card and the
  screen never disagree.
- **D3**: `timeLeftNotice` ignores the lock. The action shows under school or bedtime whenever a budget is set today.
- **D4**: `glanceLayout`'s tile size still counts rows only. `rule` is carried for `placeList` and the layout model.
  `LockGlanceTest` pins it for 0-9 rows with and without the timer (mutation L2 caught).
- **D5**:
  - the phone counts accepted requests per ledger day in DE prefs (`count_v1`);
  - `Capped` hides the action until the day changes;
  - `{accepted:false, reason:"capped"}` sets the count to 3;
  - "Spurt" comes only after `accepted:true` (`afterTimeRequestAnswer`, mutation L5 caught);
  - a 404 drops the record silently and the action stays, as decided.
- **D6**: `0096_extra_time_request.sql` and `migrations_73_to_96_in_order` (but see "Merge" below: today's `main` has 0095,
  so after the merge there is no gap and nothing needs renumbering).
- **R13-2/R13-6, `lockEnd`** (`lock/RuleCard.kt:78-92`):
  - the end day's budget: today's `BudgetUse` only when `until.toLocalDate() == ledgerDay`, else a fresh
    `budgetMinutesFor` day;
  - DST: `getValidOffsets(until).size != 1` gives null, for both the gap and the overlap;
  - lifts that expire before the end: `ruleLiftsAt` keeps a rule lift only if `liftExpiry` is not before the end;
  - every case has a test, and mutations L1, L8 and L9 are each caught by their own case.
  - An end at midnight (a 20:00-00:00 rule) correctly takes the next day's fresh budget. One remaining gap is Q-6.
- **R13-3, refresh from `recheck` whether counting or not**: `AnchorNotice.refresh(context, snap)` runs right after
  `reevaluateLockReasonFromCache(context, snap)`. The order is pinned by a source test.
- **R13-5, the lock listener**:
  - one snapshot in `recheck` feeds the reason, the notice and the listeners;
  - the listeners run before `MissedCallNotifier.relearn`;
  - `relearn` is asynchronous anyway (`runThenDone`), so its re-render comes later and keeps the new card;
  - the panel listens between `start` and `stop`, re-renders only when the model changed, and `renderNow` binds only
    from the kept model;
  - `resumeNow` has its own `try/catch` (`currentRuleCard`), and `notifyLockListeners` catches each listener.
  - Not pinned by a test (mutation L11 not caught), but it is behaviour-neutral today because `relearn` is asynchronous.
  - While counting, a re-check still builds a second snapshot (Q-5).
- **R13-8**:
  - "Spurt kl. HH:MM" is the content text, never a button: there is exactly one `addAction` and it only fires on
    `AskPart.Action`;
  - there is no action while a request is pending, taken or capped (mutation L16 caught);
  - `openUntilMs` is a stored instant and an extra `nextBoundary` instant, filtered to the future so an expired record
    doesn't arm a past alarm.
- **R13-11**:
  - `startForeground(COMMAND_LISTENER_NOTIFICATION_ID, AnchorNotice.plain(this))` is literally the first line after
    `super.onCreate()`, then `reset()`, then `refreshNow()` in a try/catch (pinned line by line);
  - `isRunning` is exposed;
  - the cache key is `notice to locale`.
- **R13-12**: `NotificationCompat.Action.Builder(..).setAuthenticationRequired(true)`. minSdk is 34, so it always applies,
  and so does `screenReaderFocusable`.
- **R13-13/14**:
  - the day bounds go through `datetime()`;
  - the case-insensitive strip has its own test (mutation S7 caught);
  - `Kind`'s doc comment is updated, the handler module is named, `time_request.rs` is in `mod.rs`;
  - the card is `screenReaderFocusable`.
- **Android mode vs own mode**: the card lives on the glance page itself, which both faces show. It adds no path, no
  action and no `backToTop`. `LockGlanceGuardTest` is unchanged and green.
- **TalkBack**:
  - the card is one stop with `contentDescription` "Skole, til 14:30" (or the label alone); the children are
    `importantForAccessibility="no"`;
  - it is never clickable and stays out of `swipeControls`;
  - the notification's action is a real action and "Spurt" is plain text.
- **"Fewer persistent notifications"**: the same id 1002, channel and icon, only `notify()` on the FGS's own id, so there
  are never two notifications from this feature. The phone without a budget gets `PLAIN_NOTICE`, the title "Vibb One"
  alone (`QuietNotificationsTest`).
- **Privacy**: the phone sends `{id, asked_at_ms}` and nothing else. The server words the event from its own latest
  report. `ExtraTimeParams` holds the phone's name, the instant, `state`, `left_minutes` and `rule` (the `lock_reason`
  kind, never the rule's name).
- **Step 2's contract**:
  - `server/testdata/time_request.json` and `launcher/app/src/test/resources/time_request.json` are byte-identical
    (`cmp`);
  - both sides compare the copies (`the_time_request_contract_matches_the_launchers_copy`, ``the contract is identical
    to the servers copy``);
  - a one-sided change is caught on each side (mutations L6, S6);
  - `launcher.yml`'s regex has `time_request` in the `server/testdata/(…)\.json` group;
  - `server-ci.yml`'s regex has it in the launcher resources group, and its comment block names it;
  - both of CLAUDE.md's "CI and releases" lists and the shared-files paragraph name it;
  - `server/CLAUDE.md`'s route list and data model name it.
- **The handler** (`server/src/handlers/device_api.rs:2303-2390`):
  - device bearer only (`require_device_token`; 401 without a token, tested);
  - 1 KiB body limit; 400 for a bad body, a bad id or an out-of-range instant;
  - the cap is counted per phone, per day of the **server zone** (Phone Setup), as §2.6 says. The phone counts per its
    own ledger day; the two agree whenever the zones agree (Q-2 for when they don't);
  - `capped` and `stale` answer 200; a retried third request is taken and not counted, because
    `dedup_key != id` (mutation S5 caught);
  - "stale" means asked before the server zone's midnight. The 30 minutes are the push rule (`Push::Now` under 30 min
    at intake), and a future `asked_at` is clamped to now.
- **The event**: key = the request id; title and summary stored empty; `told` words it; target `#extra`; TTL 3600; normal
  urgency; last in `Kind::ALL`.
- **The switch**:
  - `push_extra_time` "Asks for more time", default 1;
  - `push::queue` reads `COALESCE(p.push_extra_time, 1)` per parent, so a parent with no row (a new one) gets it on;
  - Notify Me About lists it from `Kind::switches()`;
  - the case of an existing row is not tested (Q-7, mutation S4 not caught).
- **Device › Time**:
  - the `data-asked` row sits above `act-grid` in `#extra`;
  - the lift forms post to `/lifts#extra` and redirect to `?done=extra#extra`, so no save jumps to the top (tested).
- **Step 3**:
  - the title rounds up (mutation L4 caught);
  - the per-minute tick runs only while counting: `update` returns before any refresh when `!appInUse`, pinned;
  - refreshes come from `recheck`, `RestrictionsPause.reapply`, `OfflineOverride.activate`, the tap, the server's
    answer and the anchor's start;
  - the receiver is `exported="false"` and named explicitly by the PendingIntent (mutation L3 caught).

**Can another app fire `TimeAskReceiver`?** Not by a broadcast: it isn't exported, so only our own uid or the system can
deliver to it. The only other way in is the immutable PendingIntent itself, which only an app holding it can send: the
system UI, or a notification listener the phone has granted access to. `setAuthenticationRequired` is enforced by the
system UI only, so a listener could send it while the screen is locked. On a managed phone that is bounded by the
enrolment check, the 3/day cap and the parent seeing every push. Noted only; no fix needed.

**The deviations**:

1. **The 0096 gap**: sound. On today's `main` the gap is closed by 0095, so no renumbering is needed, only the merged
   order test (below).
2. **`ExtraTimeParams.rule`**: sound and needed for the kind word. It holds no rule name.
3. **`requestState`'s order** (an open record before Capped): sound.
   - After the day's third `accepted:true` the record is ASKED and the count is 3. The kid still sees "Spurt kl. HH:MM"
     for its 30 minutes or until a lift, then the action stays gone until midnight.
   - A PENDING record with the count at 3 can't come from a tap: `askStep` needs Idle, and a `capped` answer drops the
     record.
   - Pinned by `TimeRequestsTest` (mutation L10 caught).
4. **A lift in the same second counts as the answer**: sound. Both `created_at` values are on the server's clock at
   one-second resolution, and the other way round would leave the row after a quick answer.
5. **Two extra refresh points and the tick's fresh snapshot**:
   - the pause and override starts are right: neither runs `recheck`;
   - the fresh snapshot per tick costs more than the note's "hand the `BudgetUse` through" (Q-5).
6. **The stricter card check** (the pref's reason and the snapshot's reason must be the same): sound.
   - The pref is written by `reevaluateLockReasonFromCache` from that same snapshot inside `recheck`, so on the listener
     path the two always agree.
   - On the `resumeNow` path they differ only when a boundary passed since the last re-check (the alarm late in Doze).
     The screen-on `recheck(SCREEN_ON)` that comes with every resume then fires the listener and the card appears.
   - The looser rule would show the snapshot's kind while enforcement and the time-rule screen still say the old one.
     The stricter rule is the safer of the two.
   - Pinned (mutation L14 caught).

## Findings

**Q-1 (Medium) - the server part is planned as 0.49.0, but 0.49.0 is already released without it.** `main` has `server/Cargo.toml` at
0.49.0 and the tag `server-v0.49.0` (0d9d046d, "server 0.49.0; PLAN queue: the server round ..."). The note says "Server
release (0.49.0) before the launcher with step 3" (`docs/design/r13-launcher-rule-card-and-time-left.md:572`, `:682`),
and so does PLAN (`PLAN.md:507`). A launcher with step 3 against today's 0.49.0 gets a 404: D5's silent path, so the kid
taps and nothing happens.
- Fix: after the merge, bump `server/Cargo.toml` to 0.50.0 and change all three places to "0.50.0". The release order
  stays: `server-v0.50.0` before the first `launcher-v*` tag with step 3.

**Q-2 (Low) - the cap and "stale" go by the server zone's day, while the phone's count goes by its own ledger day.** When
the two zones differ, a `capped` answer hides the action for the phone's whole new day.
- Where: `device_api.rs:2323` and `:2347` use `phone.zone.start_of_today(now)` (Phone Setup's zone, the UTC fallback when
  it is unset). `afterTimeRequestAnswer` then writes `TimeRequestCount(record.day, 3)` for the phone's day
  (`timerules/TimeRequests.kt`, CAPPED).
- Example: Phone Setup's zone blank (the UTC fallback), a phone in Oslo (UTC+2).
  - The kid asks three times in the evening, then once at 01:00 local.
  - The server's "today" still runs from 00:00 UTC (02:00 local) the day before, so it answers `capped`.
  - The phone records 3 for the new day, and "Be om mer tid" is gone all day.
  - Requests after the phone's midnight but before the server's are also judged against the wrong day.
- The same split happens whenever the phone's zone is ahead of Phone Setup's: a family travelling east with
  Phone Setup at home, in the hours between the phone's midnight and Phone Setup's.
- Fix:
  - the handler takes the day bounds from the zone the phone reports (`time_state.zone`, design 34 P13, already in the
    report it reads at `:2328`) when there is one, else Phone Setup's;
  - one test: a report in another zone, and the fourth request after that zone's midnight is accepted;
  - or the body carries the phone's ledger `day` and the server caps and judges `stale` per that day. That is a
    contract change: both directories and `time_request.json` in one commit.

**Q-3 (Low) - a lift that arrives while a request is PENDING makes the phone treat it as answered, but the next sync
still uploads it.** `requestState` turns Idle once `maxLiftId > liftIdAtAsk` (`TimeRequests.kt:62`), but `upload` posts
any PENDING record that is still inside its 30 minutes (`:210-243`).
- Case: the kid taps offline, a parent adds time in the meantime (or just before the tap, before the phone has the
  lift), and the phone reconnects.
- The sync fetches the policy with the lift, then posts the request anyway. The result:
  - the parent gets "… asks for more screen time" after already giving time;
  - Device › Time shows "Asked for more time at …" because the event's `created_at` is after the lift, and it stays
    there until the next lift or midnight;
  - the phone uses up one of its three.
- Fix: `upload` reads the current policy's `maxLiftId` and the ledger day. It posts only while
  `requestState(record, count, now, day, maxLiftId)` is `Pending`, otherwise it drops the record unsent (that also skips
  yesterday's PENDING record, which today only earns a `stale`). Make the decision a pure function and add one
  `TimeRequestsTest` case.

**Q-4 (Low) - the tap doesn't arm the boundary alarm for `openUntilMs`; only the sync's re-check does.**
- `TimeAskReceiver` writes the record and calls `SyncRunner.request` (`timerules/TimeAskReceiver.kt:32-34`). The alarm
  learns about `openUntilMs` only when `performMdmSync` posts its `recheck`.
- If the sync returns early (`performMdmSync`'s `deviceToken.isNullOrBlank()` return; after the r10 merge an unreadable
  Keystore token reads as null) or the anchor fails to start, nothing arms the alarm.
- A PENDING request on the time-rule screen (nothing counting, so no minute tick) then outlives its 30 minutes, with no
  action, until the next screen-off/on. §2.9 check 6 expects the alarm to end "pending" there.
- Fix: `TimeRuleAlarm.schedule(app)` in `TimeAskReceiver` after a successful `ask` (one line). Also call it after
  `upload` changes the record, which is cheap.

**Q-5 (Low) - while counting, the notice rebuilds a full snapshot on every `ScreenTimeTracker.update`, not once a
minute.**
- `AnchorNotice.refreshNow(context)` (`timerules/ScreenTimeTracker.kt:100`) runs on every `update` in the counting
  branch: every activity resume and pause in the process, screen on and user present, and the first line of every
  `recheck`.
- Each `refreshNow` is `TimeRulesRuntime.snapshot`: a ledger load and save, a lift-records read and commit,
  `messagingApps`, `decideTimeLock`, on the main thread.
- So a re-check while counting builds two snapshots again, against R13-5's "one snapshot per re-check". Opening and
  closing apps from Home costs one snapshot per transition.
- Fix:
  - refresh from the `checkpoint` runnable only. `update` can take a `fromCheckpoint` flag, or the checkpoint calls
    `AnchorNotice.refreshNow` after `update`;
  - skip the refresh when `update` is called from `recheck`, which refreshes from its own snapshot a few lines later;
  - the note's own idea also works: compute the notice from the `BudgetUse` that `update` has already computed, plus the
    request's DE state.

**Q-6 (Low) - `lockEnd` can promise an end that the budget breaks first, when the rule leaves apps usable that count
screen time.**
- `lockEnd` checks the budget at the end with today's *current* use (`RuleCard.kt:90`). Screen time keeps counting under
  a rule in its exempt apps: `usableApps`, the kid in a messaging app rather than on our free screens.
- Example: school 08:00-14:30 with a messaging app exempt and 10 minutes left. The kid uses it for 15 minutes in the
  break, and at 14:30 the phone locks as SCREEN_TIME. The card said "Til 14:30".
- The server's `provable_end` has the same blind spot, so this doesn't break D2's "never disagree".
- Fix: when the end is the ledger's day, the lock has `usableApps` and `budget.remainingMs` is less than the time to the
  end, return null. One `RuleCardTest` case.

**Q-7 (Low) - "on by default for every existing parent" is not tested.**
- Mutation S4 (`DEFAULT 0` in 0096) passes every test. Mamma has no `notification_prefs` row (COALESCE gives on), and
  Pappa's row is written by the settings form with every column explicit.
- The parents U7 is about, who saved Notify Me About before 0096, are never exercised.
- Fix: in `a_request_raises_one_event_and_pushes_by_each_parents_switch`, give Mamma a row first that names no
  `push_extra_time`: `INSERT INTO notification_prefs (admin_id) SELECT id FROM admin_users WHERE username = 'admin'`
  (adjust to the table's NOT NULL columns). Then expect her push.

**Q-8 (Low) - `tests::time_request` fails in windows around Oslo's midnight.** The test database has Phone Setup's
default zone, Europe/Oslo (migrations 0018/0034), so the handler's "today" starts at 00:00 Oslo. Three tests are affected:
- `a_request_raises_one_event_and_pushes_by_each_parents_switch`: the `report()` helper sends
  `"day": Utc::now().date_naive()`, the UTC date. Between 00:00 Oslo and 00:00 UTC (one or two hours every night, 22:00
  or 23:00 to 24:00 UTC) that is "yesterday" in Oslo. `screen_time` drops the report, the state is `Unknown`, and the
  "1 h 20 min left" assertion fails.
- `the_contract_answers` posts "a minute ago" (`server/src/tests/time_request.rs:134`), which gets `stale` in the first
  minute after Oslo's midnight.
- `news_stale_and_refused_bodies` posts "31 minutes ago" (`:254`), which gets `stale` instead of `accepted` in the first
  31 minutes.

A CI run in those windows goes red. Fix:
- build the report's day from the zone (`Zone::load(&app.db).await.today(Utc::now())`, as `time_pages.rs:861` does);
- for the two instants, use `max(now - N min, start_of_today + 1 s)` and set the "31 min old" case with an SQL
  `UPDATE … happened_at`, or skip it with a clear message when today is less than 32 minutes old.

**Q-9 (Nit)** - small things:
- `AnchorNotice.reset()` sets `lastPosted = null`, so every anchor start posts the plain notice a second time right after
  `startForeground` (`server/AnchorNotice.kt:38`). It is harmless; `reset` could set
  `lastPosted = PLAIN_NOTICE to <locale>`.
- A change of Vibb's per-app language shows only at the next refresh, which is up to a minute while counting and later
  on a locked phone. It is acceptable as the note decided it (the cache key); no extra trigger is needed.
- The tap path (`TimeAskReceiver`) and `refreshNow` commit prefs on the main thread, as `recheck` already does. Fine.
- `TimeAsk::of` (`device_api.rs:2328-2339`) takes the newest report of any age. The sync posts its status just before
  the upload, but that report is best-effort. If it failed, the push can say "School" from the morning. Optional: use
  the report only when `reported_at` is within about 10 min, else `Unknown`.

**Q-10 (Nit) - docs:**
- the note's "Implementation status" counts `tests::time_request` as 6; there are 7;
- step 2's row in §3 names `pwa.md`, but the Time page's text went (rightly) into `docs/notes/server/time-rules.md`;
- PLAN's two "Built" entries and both CLAUDE.md files are true as written, except Q-1's version.

Not findings: D5's old-server behaviour (a tap that does nothing on a 404, with the action back) is as decided. So is the
"Asked" row that lasts until a lift or midnight while the phone's "Spurt" lasts 30 minutes (§2.7).

## The merge into today's `main` (11d1bfd5)

`git merge --no-commit --no-ff origin/main` on a scratch branch conflicts in three files. Everything else auto-merged,
including `CommandListenerService.kt` (r10's token reader next to r13's `onCreate`), `MdmSyncWorker.kt`, both
`strings.xml`, `launcher/CLAUDE.md`, `docs/notes/server/notifications.md` and pwa-fixes-a's `push.rs`/`notifications.rs`.

1. **`server/src/tests/music_spotify.rs`**: the order test. `main` has `migrations_73_to_95_in_order` with
   `(95, "push session epoch")`; r13 has `migrations_73_to_96_in_order` with `(96, …)`. Resolution:
   - `migrations_73_to_96_in_order`, range `73..=96`, with both rows 95 and 96;
   - the message lists "0095 (security review M1), 0096 (design r13's extra-time request)";
   - the doc comment drops the "whichever branch merges second" sentence: no renumbering is needed, since 0095 is now on
     `main` and 0096 follows it with no gap.
2. **`server/CLAUDE.md`**: three hunks of the migration paragraph.
   - Take `migrations_73_to_96_in_order` in the two references.
   - In the last hunk, keep `main`'s 0095 sentence and follow it with r13's 0096 sentence. Drop the "with a gap … sqlx
     accepts the gap" wording and keep "The next free number is 0097".
3. **`PLAN.md`** (the queue at :503-508): take `main`'s list, which no longer has the r10 line (merged), with r13's two
   updated lines, and fix 0.49.0 -> 0.50.0 (Q-1).

Also update by hand, though they don't conflict: the note's D6/"Deviations" line about the gap, and the comment in
`0096_extra_time_request.sql`. The migration file is frozen from its first commit on a pushed branch. `r13-rule-card` has
not been pushed, so its "whichever branch merges second renumbers" comment may still be edited before it is.

**The merged tree, resolved as above (scratch only, not committed):** the launcher's `assembleDebug testDebugUnitTest` gave
**1776 tests, 0 failures**, and the server's `cargo test` gave **1308 passed, 0 failed** (with `migrations_73_to_96_in_order`
listing 95 and 96). There is no semantic conflict with r10 or pwa-fixes-a.

## Mutation pass

Each mutation was applied alone; the relevant test classes or filters were run; the file was then reverted.
`git status` was clean after every one.

| # | Mutation | Caught by |
|---|---|---|
| L1 | `lockEnd` uses today's budget for any end day | `RuleCardTest` "bedtime with today's budget used up ends at 07 00 …" |
| L2 | the card counts toward the compact player | `LockGlanceTest` "the rule card - … never counted toward the compact tile"; `LockGlanceLayoutTest` "what scrolls at 473 and 497 dp" |
| L3 | `TimeAskReceiver` `exported="true"` | `TimeLeftNoticeTest` "one notification - …" |
| L4 | the title rounds down | `TimeLeftNoticeTest` "minutes left round up …"; `ScreenTimeTest` "the checkpoint waits …" |
| L5 | `capped` keeps the record as ASKED ("Spurt" on `accepted:false`) | `TimeRequestsTest` "the answer table" |
| L6 | the contract changed on the launcher side only | `TimeRequestsTest` "every answer decodes", "the contract is identical to the servers copy" |
| L7 | the phone's count not reset at midnight (no `count.day == day`) | `TimeRequestsTest` "the state - …" |
| L8 | `lockEnd` without the single-instant check | `RuleCardTest` "an end in the spring-forward gap is no end" |
| L9 | `ruleLiftsAt` ignores the lift's expiry | `RuleCardTest` "a lifted second rule running past the end …" |
| L10 | `requestState`: Capped before an open record | `TimeRequestsTest` "the state - …" |
| L11 | the lock listeners after `relearn` | **NOT CAUGHT** (behaviour-neutral today: `relearn` is asynchronous) |
| L12 | the action without `setAuthenticationRequired(true)` | `TimeLeftNoticeTest` "one notification - …" |
| L13 | `openUntilMs` not a boundary | `TimeLeftNoticeTest` "the refreshes - …" |
| L14 | `ruleCard` without the same-reason check | `RuleCardTest` "the card - …" |
| L15 | `upload` overwrites a newer record (no `current?.id != record.id`) | **NOT CAUGHT** (glue, no test) |
| L16 | the action shown while Pending | `TimeLeftNoticeTest` "the table - …" |
| L17 | no refresh in `ScreenTimeTracker.update` (the minute tick off) | `TimeLeftNoticeTest` "the refreshes - …" |
| S1 | the cap at 4 (`>` for `>=`) | `time_request::the_contract_answers`, `::three_a_day_and_a_retried_third_is_no_fourth` |
| S2 | "stale" never | `::the_contract_answers`, `::news_stale_and_refused_bodies` |
| S3 | the news rule at 32 min (31 min old pushed) | `::news_stale_and_refused_bodies` |
| S4 | `push_extra_time DEFAULT 0` | **NOT CAUGHT** (Q-7) |
| S5 | a retried third counted as a fourth (no `dedup_key != id`) | `::three_a_day_and_a_retried_third_is_no_fourth` |
| S6 | the contract changed on the server side only | `::the_contract_answers`, `::the_time_request_contract_matches_the_launchers_copy` |
| S7 | Activity's clock strip case-sensitive again | `activity::tests::the_own_clock_goes_whatever_its_case` |
| S8 | the "Asked" row ignores lifts | `::the_time_page_shows_the_open_request_until_a_lift` |
| S9 | a lift made before the request answers it | `::the_time_page_shows_the_open_request_until_a_lift` |
| S10 | upper-case ids taken | `::news_stale_and_refused_bodies` |
| S11 | a future `asked_at` not clamped | `::news_stale_and_refused_bodies` |
| S12 | pushed whatever its age | `::news_stale_and_refused_bodies` |
| S13 | the kind's TTL back to 12 h | `parent_events::tests::a_request_for_more_time_is_told_from_its_parameters` |
| S14 | "Bedtime" lost to "A time rule" | `parent_events::tests::a_request_for_more_time_is_told_from_its_parameters` |

**Score: 28 of 31 caught.** Launcher 15/17, server 13/14. L15 and S4 deserve a test: S4 is Q-7, and L15 can come with
Q-3's pure upload decision. L11 can stay unpinned.

## Suites

- Launcher (`assembleDebug testDebugUnitTest -PwarningsAsErrors=true`, JDK 17): **1742 tests, 0 failures**, build green.
  This matches the note's count.
- Server: `cargo test` **1298 passed, 0 failed**; `cargo fmt --check` clean; `cargo clippy --all-targets` shows the
  **13 known warnings**, none in a file this branch touches (devices.rs:1324, models.rs, storytel.rs, dns_engine.rs), so
  no new one.
- No emulator run (owed by the note: §1.7, §2.9).

## Emulator checks to add

To §1.7 (the card):

9. A rule that starts while the used-up budget already locks (SCREEN_TIME, then SCHOOL), with the glance on screen:
   the hourglass card turns into the book card at the minute (the stricter reason check plus the listener).
10. A lifted second rule whose pause ends before the first rule's end: no "Til" on the card or on the time-rule screen.
    With the pause running past the end: "Til HH:MM" on both.
11. Switch Vibb's language nb <-> en with the glance up, then lock: the card's label and "Til"/"Until" follow.
12. A rule with an exempt messaging app and 5 minutes of budget left. Use the app for 6 minutes: note what the card
    promised and what happened at the end (Q-6).

To §2.9 (the notification and the request):

9. Tap offline, add +15 min from the PWA, then go online: the phone must not post the old request, and Device › Time
   must not show "Asked" (Q-3).
10. Reboot while "Spurt kl. HH:MM" shows: after the unlock it is still there, and it goes at the 30 minutes with the
    screen left on the time-rule screen.
11. Three accepted requests, then move the clock past midnight (`adb shell cmd alarm set-time` or a zone change): the
    action is back at 00:00 with no tap.
12. Phone Setup's zone unset, the phone in Europe/Oslo: a request between the phone's midnight and UTC midnight after
    three in the evening (Q-2).
13. `adb shell dumpsys notification --noredact | grep me.vibb.launcher` after the pause starts and ends and after the
    offline override: still exactly one record, id 1002, plain during the window.
14. TalkBack on the shade: the action reads "Be om mer tid, button"; "Spurt kl. HH:MM" reads as text, with no action
    announced.
15. Switch Vibb's language with the shade open: the title follows within a minute while counting.

## Fix round (2026-10-25, `r13-rule-card`)

`main` (6e845493: r10, `pwa-fixes-a` with 0095, `server-two-arch`) merged in a8ce85b9, the three conflicts resolved as
"The merge into today's `main`" says (`migrations_73_to_96_in_order` with 95 and 96; server/CLAUDE.md's 0095 sentence,
then 0096 with no gap; PLAN's queue). The fixes are in 7090c459.

- **Q-1** - Fixed in 7090c459: `server/Cargo.toml` and `Cargo.lock` at 0.50.0; the note (§3 step 2, "Implementation
  status") and PLAN say 0.50.0 before the launcher with step 3.
- **Q-2** - Fixed in 7090c459. Chosen: the server counts per the **phone's reported zone**
  (`device_api::extra_time_day_zone`: `time_state.zone` from the phone's latest report when it reads as an IANA zone,
  else Phone Setup's), for both the cap and "stale". Why: the phone's ledger - and so its own count of three - counts
  its day in exactly that zone (design 34 P13), so the two days are the same day; no contract change, no new field,
  and an older launcher without a zone keeps Phone Setup's day as before. Test
  `time_request::the_cap_counts_the_phones_own_day` (a zone 13 h from Phone Setup's, three rows in the window where the
  days differ: the fourth is accepted or capped by the phone's day; an unknown or missing zone falls back).
- **Q-3** - Fixed in 7090c459: pure `uploadStep(record, count, now, day, maxLiftId)` - SEND only while `requestState` is
  Pending with the policy this sync fetched and the ledger's day; DROP (unsent) for one a lift answered meanwhile,
  another day's or an expired one. `TimeRequestsTest` "the upload sends only an open request".
- **Q-4** - Fixed in 7090c459: `TimeAskReceiver` calls `TimeRuleAlarm.schedule(app)` after a successful ask (pinned in
  `TimeLeftNoticeTest`); `TimeRequests.upload` re-arms it after any change to the record.
- **Q-5** - Fixed in 7090c459: `ScreenTimeTracker.update` refreshes with `AnchorNotice.refresh(context, budget, policy,
  ledger.day, now)` - the budget it computed, the request's state from its DE prefs, no snapshot (pinned: the update
  calls neither `refreshNow` nor `snapshot(`). Also Q-9's first nit: `reset(context)` records the plain notice, so
  the anchor's start posts once.
- **Q-6** - Fixed in 7090c459: `lockEnd(.., at)` returns null when the end is the ledger's day, the lock leaves apps
  usable and `budget.remainingMs` is less than the time to the end. `RuleCardTest` "no end when the budget can run out
  first in the rule's usable apps" (and an end on another day still certain).
- **Q-7 / S4** - Fixed in 7090c459: `time_request::a_parent_saved_before_the_migration_has_the_switch_on` (a database
  migrated to 0095, a parent's row with every older switch explicit, then 0096: on), and Mamma's row naming no
  `push_extra_time` in `a_request_raises_one_event_and_pushes_by_each_parents_switch` still gets her push.
- **Q-8** - Fixed in 7090c459: the tests' Phone Setup zone is `steady_zone` (one of Oslo, Tokyo and New York whose day
  is between 1 h and 22 h old now - "a minute ago", "31 minutes ago" are today and "26 hours ago" isn't, at any hour),
  and the report's day is that zone's today. No faked clock in the harness.
- **Q-9** - the first nit fixed (above); the report's age: `TimeAsk::of` reads a report of the last 15 minutes only
  (`AWAY_STALE_MINUTES`), else `Unknown`. The others need nothing, as the review says.
- **Q-10** - the note's counts and step 2's `time-rules.md` corrected.
- **L11** - pinned: `RuleCardTest` "the lock listeners run before relearn". **L15** - pinned: pure
  `recordAfterUpload` (null for a newer or no record) and `TimeRequestsTest` "the answer writes only the record that
  was sent". **S4** = Q-7.
- The emulator checks to add are in the note's §1.7 (9-12) and §2.9 (9-15).

Suites after the fix round (the merged tree): launcher `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`
**1780 tests, 0 failures**; server `cargo test` **1311 passed, 0 failed** (the deploy-script tests from `main`
included), `cargo fmt --check` clean, `cargo clippy --all-targets` the 13 known warnings, none new; the two
`time_request.json` copies byte-identical.
