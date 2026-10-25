# Design QA - launcher round 13 (the rule card on the glance page, screen time left in the anchor notification)

Reviewed on `r13-design` at 6640d65c (2026-10-25): `docs/design/r13-launcher-rule-card-and-time-left.md` and its mockup
(`r13/mockup.html` in the session scratchpad), checked against PLAN's two entries (PLAN.md:909-915, :1289-1299), designs
19b, 31 (`provable_end`), 35, 59/59d, 62 step 5, 64, r11, `CLAUDE.md`, `launcher/CLAUDE.md`, `server/CLAUDE.md` and the
code on `main` (1b71c21b) and on `pwa-fixes-a`. Launcher paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/`
unless they start with `launcher/`, `server/`, `docs/`, `.github/` or `PLAN`. No build run; reading only.

Confirmed as the note says:
- Every cited line: `LockGlance.kt:268` (`glanceBackToTop`), `:525` (`GLANCE_COMPACT_FROM_ROWS`), `:548` (`glanceLayout`);
  `GlancePanel.kt:364/394/447/1217/1519/985/1541`; `LockActivity.kt:131/144/177`; `TimeRules.kt:163/186`;
  `ScreenTime.kt:145`; `ScreenTimeTracker.kt:19/43/82`; `TimeRuleAlarm.kt:64`; `TimeRulesRuntime.kt:141/226`;
  `MdmSyncWorker.kt:203-207` (the recheck posted to the main thread), `:332` (`PowerEvents.upload`, after the status
  report at :247); `CommandListenerService.kt:111/161`; `Notifications.kt:18-19`; `ParentPinEvents.kt:48`;
  `device_routes.rs:83`; `parent_events.rs:36/54/113/968/1006`; `device_time.html:37`; `lifts.rs:41-52`;
  `schedules.rs:1279`; `launcher.yml:76`; `server-ci.yml:53`; `music_spotify.rs:188`.
- The geometry: clock 78.4 + date 25.6 = 104 dp; scroller 401 / 425 dp (`LockGlanceLayoutTest`, "the parts match the
  design's model"); the card 12 + max(40, 21 + 18.2) + 12 = 64 dp, 82 with its margin, in the test's 1.4 x model; every
  row of §1.3's table adds up (186, 384, 448 = 47 / 23, 360.8, 346, 430.8 = 29.8 / 5.8, 410 = 9 / fits, 366.2).
- `decideTimeLock` at `until` catches the same rule's next day, an all-day window, a fail-closed 7-entry rule, an adjacent
  and an overlapping rule (`Span.contains` is start-inclusive, `TimeRules.kt:71`).
- The anchor runs in the app's process (only `BootCoverActivity` has its own, manifest :187), so `ScreenTimeTracker`
  can post its notification. The kiosk's lock-task features always include NOTIFICATIONS
  (`server/src/handlers/devices.rs:697-702`), so the shade is there while unlocked. While LOCKED it is gone in both
  kiosk modes (`featuresWhileLocked`, `PIN_LOCK_FEATURES_KIOSK_OFF`, `server/LockTaskHelpers.kt:119-133`).
- A non-exported receiver behind an immutable explicit PendingIntent follows the repo's pattern
  (`InstallModeReceiver`, `WindowRelockReceiver`, manifest :453-460). The offline case works: a reconnecting SSE stream
  syncs (`sse_open`, `CommandListenerService.kt:243`).
- Server: Notify Me About, the push queue and the per-parent reads are all driven by `Kind::switches()`
  (`notifications.rs:503-521`, `:577-600`, `push.rs:336-347`, the template loop `notifications_settings.html:110`).
  "No template change" holds, and so does default 1 (no row means on, migration 0088). `told` runs in the inbox, in
  Activity (`activity.rs:87` via `inbox_row`) and in the push (`push.rs:498`). `begin_with("BEGIN IMMEDIATE")` exists
  (`zones.rs:539`).

## Findings

**R13-1 (Medium) - migration 0095 is taken.** `pwa-fixes-a` adds `server/migrations/0095_push_session_epoch.sql`
(2ccab9f1, security review M1). The same branch renames the test to `migrations_73_to_95_in_order`
(`git show pwa-fixes-a:server/src/tests/music_spotify.rs`, :186-189). Two 0095 files break `sqlx::migrate!`.
Change: §2.6 and step 2 use `0096_extra_time_request.sql`, and the test becomes `migrations_73_to_96_in_order`, with the
doc comment's "the extra-time request 0096 (was `migrations_73_to_95_in_order`)". Add one line: whichever branch merges
second renumbers.

**R13-2 (Medium) - `lockEnd` checks the end against today's budget even when the end is tomorrow; "twin of
`provable_end`" isn't true.** §1.1 passes the snapshot's `BudgetUse` (`TimeRulesRuntime.kt:147`, today's ledger) to
`decideTimeLock(policy, lock.until, …)`. For a window that ends the next day (bedtime 22:00-07:00, `spanOn`
`TimeRules.kt:87`) this goes wrong both ways:
- (a) With today's budget used up, every bedtime evening gives no end, so the common "Leggetid / Til 07:00" mostly
  never shows. The mockup's frame C caption depends on "today's screen time isn't used up" for an end that falls
  tomorrow.
- (b) If tomorrow's budget is 0 (`budgetMinutesFor`, :116; the time-rule screen's own "Ingen skjermtid i dag",
  `BudgetLine.NoneToday`), the card promises "Til 07:00". At 07:00 the phone locks again as SCREEN_TIME. That is a false
  promise, which U2 rules out.

The server's `provable_end` also refuses an end in the DST gap or overlap (`zone.single_instant`,
`server/src/time_state.rs:309`, `:326`), and the phone's version has no such check.

Change:
- §1.1: the budget at `until` is today's `BudgetUse` when `until.toLocalDate()` is the ledger's day. Otherwise it is
  `BudgetUse(0, budgetMinutesFor(policy, until.toLocalDate()), 0)` (budget lifts apply to the day first seen).
- An end that isn't a single instant in the snapshot's zone gives null (`lockEnd` takes the zone).
- Replace "the phone's twin" with what each side checks.
- `RuleCardTest` gets three cases: bedtime with today used up gives 07:00; tomorrow's budget 0 gives null; an end in the
  spring-forward gap gives null.

**R13-3 (Medium) - the notice's refresh rides on a code path that returns early whenever nothing counts.** §2.3 refreshes
"at the end of `ScreenTimeTracker.update`, with the `BudgetUse` it just computed". It also expects `recheck`'s first line
to refresh on "an accepted policy, a changed budget, midnight and the pause". But `update` returns at
`if (!appInUse(context)) return` (`ScreenTimeTracker.kt:90`) and computes the budget only after that (:93-94). So nothing
is refreshed when the screen is off, on the PIN lock, or on a free screen. The free screens include the time-rule screen
and Settings (`FREE_SCREENS`, `ScreenTime.kt`), and Settings is where a pause starts.

With Q3's recommended default this is the main path: school locks while a budget is set, and the kid asks from the
time-rule screen. The parent's +15 min arrives and runs `recheck`. `update` returns early, school still locks, and
`LockActivity` stays. The title stays on the old minutes and the action stays "Spurt" although it was answered, until the
30-minute handler. Under bedtime, the shade at 00:05 still says "… brukt opp i dag" from yesterday.

Change: §2.3 refreshes from `TimeRulesRuntime.recheck` after `reevaluateLockReasonFromCache`, with that snapshot's
budget, whatever the counting state (or `update` computes the budget before its early return). The checkpoint keeps the
per-minute tick while counting. Say in §2.3 that the time-rule screen, Settings and the screen-off states are covered
this way.

**R13-4 (Medium) - counting the card toward the compact player changes a rule the user set; it belongs in §4.**
`GLANCE_COMPACT_FROM_ROWS = 2` is "the user's ask: 'ved 2 notifications'" (`LockGlance.kt:524-525`, r11 §2). Counting
the card makes the player compact at one notification. The note decides this itself ("veto at review"), but it is the
user's call. The trade is in the note's own table: the full tile with the card and one row is 430.8 dp, a 30 dp / 6 dp
scroll, which r11 accepts; the compact one is 346 dp and doesn't scroll. The rule is also uneven: the timer tile, which
is taller (114 dp), doesn't count (`glanceLayout`, :548).
Change: move it to §4 as question 4. Options: (a) the card counts (the note's), or (b) only notification rows count, as
the user said, and the page scrolls. Keep frames B/D for both. Recommend (b) unless the user wants the card's page
scroll-free.

**R13-5 (Low) - re-render and recompute: the listener adds a third snapshot per re-check, and it is laid out twice.**
- `recheck` already builds a snapshot in `reevaluateLockReasonFromCache` (`MdmSyncWorker.kt:1203`), and `update` loads
  the ledger. The glance listener builds one more, with `commit()`s of the ledger and the lift records on the main
  thread (`TimeRulesRuntime.kt:79-121`). At screen-on, `resumeNow` and the `recheck(SCREEN_ON)` listener
  (`PinLockRuntime.kt:980`) both compute it.
- Inside `recheck`, `MissedCallNotifier.relearn` (`TimeRulesRuntime.kt:242`) fires the panel's `changed` before the lock
  listeners run at the end. `renderNow` then lays out with the old card, and the listener lays out again with the new one.
- The existing callback is `LockActivity`'s preference listener on `lockReason`/`lockKey` (`LockActivity.kt:53-58`). The
  note should say why it isn't enough: `TimeLock.key()` (`TimeRules.kt:305`) carries neither `budgetExhausted` nor
  `until`.

Change:
- `recheck` hands its snapshot to the listeners (one snapshot per re-check).
- The lock listeners run before `relearn`.
- One sentence on the preference listener.
- In the glue, the card computation gets its own try/catch inside `resumeNow`, as the rows have in `renderNow`.
  `guarded` (`GlancePanel.kt:1541`) wraps the whole entry point, so a throw would skip the stage decision and `renderNow`.
- §1.1/§1.5's "before the first unlock the snapshot throws" is moot: `PinLockActivity` isn't `directBootAware` (only
  `BootCoverActivity`, in `:bootcover`, is). Say "the glance never runs before the first unlock".

**R13-6 (Low) - "briefly optimistic" is avoidable.** §1.1 uses the lifts in force now. A lift on a second rule that ends
before `until` gives a "Til 14:30" that is wrong until the lift's expiry re-check. That is a short window, but U2 says
"only when certain". Change: in `lockEnd`, a rule lift counts only if its expiry instant (`liftExpiries`, `ScreenTime.kt`)
is at or after `until`. Add one test case.

**R13-7 (Low) - "Spurt" can mean nobody was told.** On an old server the 404 marks the record sent (§2.4), and the
server's fourth request of the day is a silent 204 (§2.6). Both show the kid "Spurt" while no parent hears. The phone has
no server version or capability to go by (`PolicyResponse` carries none).
Change, either of these:
- The phone keeps its own count per ledger day (the cap, 3) and hides the action after it; the server keeps the
  authoritative cap. After a 404 it remembers "not supported" for 24 h and hides the action.
- Or §2.4/§2.6 state it as accepted.

Either way, put the number 3 to the user in §4 (it is a product rule, decided in the note now).

**R13-8 (Low) - a no-op "Spurt" button, and the expiry handler across process death.** A notification action can't be
disabled. "Spurt" is still a tappable button, and TalkBack reads it as one. The one-shot 30-minute handler (§2.3) is
posted at the tap only, so it is lost when the process dies. After that, "Spurt" outlives its 30 minutes until some other
refresh.
Change:
- While a request is open, the notice has no action and shows the asked state as text (content text or `subText`
  "Spurt kl. 14:02" / "Asked at 2:02 PM").
- Every refresh re-arms the expiry handler at `askedAtMs + 30 min` while `requestOpen` is true.
- §2.2's table and frame G follow.

**R13-9 (Low) - the tests' files and steps.**
- Step 2 adds "the contract copy and its encode test", but §2.5/§2.8 put that test in `TimeLeftNoticeTest`, which is step
  3's.
- `TimeLeftNoticeTest` holds four units.

Change:
- `TimeRequestsTest` (pure): record, `requestOpen`, `askStep`, `timeRequestLeavesQueue`, the contract encode and the
  copies compared. Step 2 creates it with the contract part, and step 3 adds the rest.
- `TimeLeftNoticeTest`: the table and `leftMinutes`.
- `checkpointDelayMs` goes in `ScreenTimeTest`, next to `screenTimeCounts`.
- Server: drop the "log line" assertion (not lean). Add "a lift created before the request leaves the Asked row", and
  "a retried POST of the third request is taken, not counted as a fourth" (the `dedup_key != id` rule).

**R13-10 (Low) - CLAUDE.md has two places to update.** Besides the shared-files paragraph, "CI and releases" lists each
build job's watched files (`launcher.yml` "… `zone_status_report.json` and `launcher_tag_codes.json`", `server-ci.yml`'s
"… launcher's `time_status_report.json`, …"). Change: step 2 adds `time_request.json` to both lists, and to the comment
block above `server-ci.yml`'s regex.

**R13-11 (Low) - anchor glue details.**
- `onCreate`'s "first, unconditionally" (`CommandListenerService.kt:110-111`; launcher/CLAUDE.md's FGS rule): keep
  `startForeground` with the plain notification literally first, then `AnchorNotice.refresh`. Computing the notice first
  reads CE prefs before the deadline-bound call.
- `running` is private (`CommandListenerService.kt:328`), so expose `isRunning`.
- The "last posted" field resets in `onCreate` (a new instance posted its own).
- The equality check on the model misses a change of Vibb's per-app language: key the cache with the locale, or re-post
  on a configuration change.

**R13-12 (Nit) - Android API wording and one emulator check.**
- The builder is `NotificationCompat` (`CommandListenerService.kt:13`), so the call is
  `NotificationCompat.Action.Builder(…).setAuthenticationRequired(true)` (core 1.8+, the repo has 1.17.0), not
  `Notification.Action.Builder`.
- §2.9 #8: a MIN-channel silent notification is normally not shown on Android's lock screen. Make the check "if it shows
  there, the action asks for the unlock".

**R13-13 (Nit) - server details.**
- `happened_at` is RFC 3339 with `T…Z` (`parent_events.rs`, `instant()`), and `created_at` is `datetime('now')`.
  Compare the day bounds through `datetime()`, as `devices.rs:1410` does.
- Activity's `without_own_clock` (`activity.rs:123-143`) strips a leading clock case-sensitively. The day after, the
  stored "Yesterday 21:40 · Bedtime" (capitalized by `told`) against `when` "yesterday 21:40" keeps both clocks. Make
  the strip case-insensitive, or pin the case in a test.
- Update `Kind`'s doc comment, which still lists `extra_time_request` as reserved (`parent_events.rs:33-34`).
- Name the handler's module (`handlers::device_api::time_request`?).
- Register `src/tests/time_request.rs` in the tests' `mod.rs`.

**R13-14 (Nit) - small wording.**
- A pure `ruleLabel(kind, name)` can't call `getString`: it returns the name or the kind's resource or enum, and
  `LockActivity`/`GlancePanel` resolve it.
- For the card's TalkBack stop, `screenReaderFocusable="true"` (API 28) says exactly what's meant without a keyboard
  focus stop. `focusable` works too.

## The brief's ten points

1. Threshold and heights: the numbers are right (above). The threshold itself is R13-4.
2. "Certain end": right for overlapping and adjacent rules and the next day's window. Wrong across midnight with the
   budget (R13-2), and the lifts are optimistic (R13-6).
3. The listener: the boundary alarm, the policy's lift, screen-on, user-present, the time change and the budget running
   out all reach `recheck`, so one hook covers them. The existing preference listener misses budget and end changes. A
   third snapshot and a double layout (R13-5).
4. Modes: own and android mode are the same page; the card adds no path and doesn't call `backToTop`, which is
   consistent with `glanceBackToTop`. The time-rule screen still waits for Android's unlock
   (`KeyguardCover.afterAndroidUnlock`). TalkBack: one stop, no action. The BFU wording is R13-5.
5. The notification: still one (id 1002, the same channel), so 19b's decision holds. The receiver is safe, and
   `setAuthenticationRequired` covers Android's keyguard. Our own lock has no shade. The record survives process death,
   the handler doesn't (R13-8). The queue and dedupe are fine. The cap is on the server, but the kid sees nothing
   (R13-7). The no-push rule after 30 minutes is fine.
6. The contract: the shape is fine, and both directories go in one commit. The path filters are right, CLAUDE.md is
   R13-10, and the migration is R13-1. Default on and the template loop are confirmed. No per-parent read change is
   needed.
7. PWA: `#extra` exists, and the lift forms return to `#extra`/`#pause`, so no save jumps to the top. The texts are
   English.
8. Tests: lean, pure, but split them (R13-9).
9. Order: server 0.49.0 before step 3 is right. A new server with an old launcher shows the switch and raises nothing,
   which is harmless. A new launcher with an old server gives a silent "Spurt" (R13-7).
10. §4: Q1-Q3 are the right questions. Q2 restores what PLAN already assumed ("as on the time-rule screen"). Missing are
    the compact rule (R13-4) and the daily cap (R13-7). Nothing the user decided is contradicted except the compact
    threshold. r11 superseded PLAN's "glanceFit, never scrolls", and the note says so.

## Verdict

**Approve with the listed changes.** Before implementation: R13-1 to R13-4 in the note (the migration number, the
budget across midnight in `lockEnd`, the refresh that doesn't depend on counting, and the compact rule as a question to
the user). R13-5 to R13-11 go into the note's glue and step text. The nits can go in with the implementation.
