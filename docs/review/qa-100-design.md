# Design QA: One Vibb notification (`docs/design/100-one-vibb-notification.md`)

Checked against `design-100` (913764505, = `main` 6c2f52d50 + the design). Read: the design; design 99 and r13 where
cited; the launcher's `server/CommandListenerService.kt`, `server/AnchorNotice.kt`, `Notifications.kt`,
`shortcuts/ShortcutsService.kt`, `ShortcutsRuntime.kt`, `ShortcutsNotification.kt`, `ShortcutsReceiver.kt`,
`ShortcutsModel.kt`, `timerules/TimeAskReceiver.kt`, `TimeRequests.kt`, `TimeLeftNotice.kt`, `TimeRulesRuntime.kt`
(`recheck`), `ui/LockActivity.kt`, `ui/TimeLockModel.kt`, `server/AppEnforcer.kt` (`applyHardening`), `Application.kt`
(`initRest` and its tail), `AndroidManifest.xml`, `res/layout/activity_lock.xml`, `item_lock_row.xml`, the drawables;
the tests `QuietNotificationsTest`, `TimeLeftNoticeTest`, `ShortcutsTest`, `TimeLockModelTest`; the server's
`testdata/shortcuts.json`, `testdata/time_request.json`, `i18n/{en,nb}.json` (`device.home.shortcuts*`),
`templates/device_home.html`, `src/tests/shortcuts.rs`, `parent_events.rs` (`EXTRA_TIME_REQUESTS_A_DAY`). The probe in
the scratchpad `d100/` (`probe/src/.../{N,Cmd,Fgs}.java`, `out/p10_line_action.png`) spot-checked.

**Verdict: the mechanism is right and well measured.** One id with `notify` swapping channels (M1-M3) is the simplest
shape that keeps r13's "never a second notification", and §2.2's ensure-before-build answers M7 properly. Removing
`ShortcutsService` takes design 99's start/stop race surface with it. The server/contract claim holds: nothing in
`shortcuts.json`, `time_request.json` or the report changes, and only the ⓘ string and its test move (D8). The one
real hole is a threading one in the new time-rule screen listener (H1). There is one robustness gap in who gives the
poster its context (M1), and the rest is small.

Counts: **1 High, 1 Medium, 10 Low.**

## Findings

### Q100-H1 High - the screen's listener fires on the tap thread: a Mer tid tap in the shade over the used-up screen would render `LockActivity` off the main thread (§8.4)

§8.4: "`AnchorNotice` gains `addListener`/`removeListener` (main thread). It calls them whenever its computed
`(TimeLeftNotice, RequestState)` changes, before the anchor-running check ... `LockActivity` listens from `onStart` to
`onStop` and re-renders." The design reads "main thread" as a property of the listeners, but `AnchorNotice.refresh` is
not only called on main:

- `ShortcutsRuntime.askForTime` -> `AnchorNotice.refresh(app, snap)` (`shortcuts/ShortcutsRuntime.kt:481-485`) runs
  on the `shortcuts-tap` executor (`ShortcutsRuntime.kt:78`, reached from `ShortcutsReceiver.kt:18-20` via `tap` ->
  `act` -> `ShortcutKey.MORE_TIME -> askForTime`). After §8.4, `TimeAsk.askNow` runs there too.
- `refresh` is `@Synchronized` and takes any thread (`server/AnchorNotice.kt:101`).

The scenario is the design's own: the kid is on the used-up screen (`LockActivity` started), pulls the shade and taps
the Mer tid disc. A listener called inside `refresh` would run `LockActivity.render()` on `shortcuts-tap`, which
touches views. The result is `CalledFromWrongThreadException`, and the device owner's process crashes, which ends calls
(§12's own reasoning for M7).

Fix in the design: `AnchorNotice` dispatches every listener through the main `Handler` (post), whatever thread
`refresh` ran on, and `LockActivity` re-reads the state in `render()` rather than trusting a value passed in. Add a
source pin for it, for example "the listener loop in `AnchorNotice` is inside `main.post`".

### Q100-M1 Medium - the one poster's context and model come from `ShortcutsRuntime.init`, which runs in a tail that can be skipped (§2.2, §2.3)

§2.2 moves `ShortcutsRuntime`'s executor into `AnchorNotification` and has `AnchorNotice` "hand the notice over and
ask for a refresh. It no longer posts." Today the refresh needs `ShortcutsRuntime.appContext`
(`ShortcutsRuntime.kt:177`: `val app = appContext ?: return`), which only `init` sets. `init` runs inside one `try`
in the unlocked setup's tail (`Application.kt:505-552`), after `MusicMedia.init` and `Torch.init`. Two cases:

- **Order.** A process started for the anchor (a `requestSync` from an alarm, a sticky restart) can run the
  service's `onCreate` before the posted tail (the comment at `Application.kt` ~497-504 says the system queues service
  creates ahead of it). §2.3 covers the first frame (`forStart` falls back to plain). The refresh that follows has no
  context yet, though, and the time left waits for `init`'s own refresh. That is harmless if `init` comes.
- **Failure.** If anything earlier in that `try` throws, `init` never runs. The anchor still runs (it is started
  by every sync), so the anchor's notification stays the plain "Vibb One" for the whole process. It loses r13's time
  left and its "Be om mer tid", which work today because `AnchorNotice.post` needs nothing from `ShortcutsRuntime`.

Say in §2.2 that the poster takes its application context from whichever caller reaches it first (`forStart`,
`notice(...)`), independently of `ShortcutsRuntime.init`. Also say that with no panel model, or with `computePanel`
throwing, the view is `Plain(notice)`, never "post nothing". `init` then only adds the platform signals.

### Q100-L1 Low - `baseline_settings_24` still has a reader; it can't be removed (§4)

§4: "`baseline_settings_24.xml` and `baseline_bug_report_24.xml` then have no reader in the launcher. They are removed
in the same commit." `res/layout/activity_list.xml:39` (the app list's settings button, `ui/list/AppListActivity`,
MIT code) uses `@drawable/baseline_settings_24`. Removing it breaks the build. The "grep first" would catch this, but
the sentence should be corrected now: only `baseline_bug_report_24` goes. Also say where `ic_stat_gear` comes from:
`scripts/material-symbols.sh`, like the other glyphs, or a literal-fill copy of `baseline_settings_24`'s path, which
keeps the M10 look that was measured.

### Q100-L2 Low - the plain notice isn't always deferred: Android shows an FGS notification with an action at once (§2.3, M8)

§2.3: "The plain notice keeps today's ~10 s deferral (M8). It has no `FOREGROUND_SERVICE_IMMEDIATE`." Android also
shows an FGS notification immediately when it has action buttons (one of the documented exemptions, besides
IMMEDIATE, media and the call/navigation/transport categories). `forStart` builds "the view this process last
computed". In a live-process restart on a budget day that is `Plain` with "Be om mer tid", which shows at once. This is
harmless, and the shorter deferral window shrinks M7's risk, but the claim and the pin "the plain builder has no
`FOREGROUND_SERVICE_IMMEDIATE`" shouldn't be read as "the plain one is always deferred". Reword it.

### Q100-L3 Low - the listener can miss the server's answer when the anchor isn't running (§8.4)

The design puts the listener call "before the anchor-running check" in `refresh`, but `refreshNow` returns before
computing anything when the anchor isn't running (`AnchorNotice.kt:110-111`). `TimeRequests.changed` ->
`AnchorRefresh.request` -> `refreshNow` (`TimeRequests.kt` ~288-300) is the path for Pending -> Asked, Capped and
Dropped. In practice the upload runs inside the anchor's sync, so the risk is small. State that `refreshNow`'s early
return moves below the listener dispatch too, or that the screen re-reads on `onStart` and on the boundary alarm.

### Q100-L4 Low - a post shed by Android's rate limit is recorded as posted (§2.2, M5, §12)

M5's shedding is silent: `notify` returns normally and the poster records the key. The poster's own ≤ 1/s with a
trailing post covers its own bursts. Android's rate is per package, though, so the launcher's other posts in the same
second can tip one update of 1002 over: a sync's per-app `notifyAppInstalling`/result pairs, the ring, missed calls,
SMS. A shed swap leaves the wrong view up until the next change. On a day without a budget there is no minute tick to
repair it. A cheap guard is to compare the posted channel (or a tag in `extras`) with `getActiveNotifications()` on the
poster thread in the trailing slot, and post again on a mismatch. Taps are still gated by `shortcutTapAllowed`'s
`panel.shown` (fail closed), so a stale panel can't act.

### Q100-L5 Low - a post from the poster thread can land after `onDestroy` (§2.2)

Today `AnchorNotice.post` runs on the main thread, like `onDestroy` (`CommandListenerService.kt:165`), so "only while
the anchor runs" is exact. On the poster thread, `isRunning` can be true at the check and false by the time `notify`
lands. That leaves a plain, non-FGS 1002 with no owner until the next anchor's `startForeground(1002)` adopts it. It is
still one notification, and the anchor is almost never destroyed in a live process. Either accept this in §12, or make
the `isRunning` check and the `notify` one step under a lock that `onDestroy` takes before clearing `running`.

### Q100-L6 Low - one source for the time part (§2.2, §2.5)

`anchorView(panel, notice)` combines a panel built from `ShortcutsRuntime.ask` (the cached `AskPart`,
`ShortcutsModel.kt` `MORE_TIME`) with the notice handed over. If they disagree for one post, the Mer tid disc can say
"asked" while the line has no "Spurt", or the action row and the disc can both be missing. Say that the poster builds
the panel from the notice it was handed (the `ask` cache is set from it before `computePanel`).

### Q100-L7 Low - `forStart`'s "no CE read" needs the cached channel id (§2.3)

`ShortcutsRuntime.channelId` reads the `shortcuts` prefs (`KEY_CHANNEL`) and makes a binder call. `forStart` should
build from the last key's channel id (the key already holds it, §2.2) and never call `computePanel` (Bluetooth, music
sessions, DPM reads) inside `onCreate` before `startForeground`. Pin it: "`forStart` has no `computePanel(`".

### Q100-L8 Low - `showAgain` runs on `Dispatchers.IO`, not on the poster thread (§2.4)

`ShortcutsPickerActivity.kt:82` runs `showAgain` on IO. "`showAgain` checks the posted view is plain before it
deletes" then reads the poster's state from another thread. A failed delete is harmless: AOSP throws
`SecurityException` for a channel holding an FGS notification, `runCatching` swallows it, and the number still moves
on. Running the check, the delete and the bump as one task on the poster's executor makes the design's claim exact,
though, and orders it against the next build's ensure (M7).

### Q100-L9 Low - M6 lowered the importance from the app, not by the kid's "Turn off" (§1, §2.4, §11 check 7)

The probe's `block` (`d100/probe/.../Cmd.java:22`) re-creates `snar_low` at `IMPORTANCE_NONE` from the app itself.
That path doesn't send `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED`, which §2.4 relies on to swap to plain. It
may also differ from SystemUI's user block in how NMS cancels the record. The swap-to-plain on a real block is
therefore unmeasured. Check 7 says it "takes setting up" and offers a fallback that checks only "can't be modified".
Keep check 7's full form as owed (a non-role emulator or the Jelly Star with roles off) before calling §2.4's row
measured.

### Q100-L10 Low - small text and pin gaps (§3, §8, §10)

- The time line is the first text in a RemoteViews with `nunito_bold`. The probe's line wasn't Nunito
  (`N.java`). Add "the font draws in SystemUI" to check 2.
- "Du har spurt tre ganger i dag." is fixed text tied to `TIME_REQUESTS_A_DAY = 3` (`TimeRequests.kt:20`). Add a
  one-line pin that the constant is 3 next to the string's test, so a change of the cap can't leave the sentence wrong.
  Note also that Capped can come from the server's `capped` (its zone's day, `parent_events.rs:1640`) when the phone's
  own count is lower. That is rare, and acceptable.
- D5's "Sender …" can stand for up to 30 minutes offline and then turn back into the button, unsent. Consider
  "Sendes når telefonen er på nett" / "Sends when the phone is online" for Pending older than a few seconds. This is
  optional and is the user's call.
- §10's pins: add the H1 pin (main-thread dispatch), the M1 pin (the poster posts `Plain(notice)` without a panel
  model) and the L7 pin.
- D8's en string uses «Ask for more time». en.json mixes both styles, so this is a nit.

## The checklist

- **startForeground first in `onCreate`**: kept literally (§2.3), with the existing pin moving
  (`TimeLeftNoticeTest.kt:75`, `QuietNotificationsTest` "startForeground stays first"). `forStart` never throws, which
  is good. See L7 for what it must not do.
- **API 34+ timing and the FGS type**: the 2-argument `startForeground` takes the manifest's type
  (`AndroidManifest.xml:551`, `specialUse`, with `FOREGROUND_SERVICE_SPECIAL_USE` declared). Removing
  `ShortcutsService` (`:560-567`) leaves the anchor's type unchanged. Only the subtype text grows. `specialUse` from
  boot is allowed on Android 15. Nothing is missing.
- **Channel before every post**: §2.2 does it for both views. This is better than today, where the anchor relies on
  `createNotificationChannels` having run. See L8 for `showAgain`'s ordering.
- **Deferral**: as measured, with L2's correction. §11 check 5 (does an IMMEDIATE update inside the deferral show at
  once) is honestly marked as unmeasured.
- **Rate limit and the trailing post**: the existing `requestRefresh` (one queued refresh, computed when it runs, the
  delay from `lastPostMs`) already posts the latest state, so "trailing" is what the code does. See L4 for sheds the
  poster doesn't cause.
- **No second notification / never nothing**: one id holds by construction. While the anchor runs, the only "nothing
  in the shade" states are the kid's own blocks (§5, unchanged from today) and the M1 case, which shows plain rather
  than nothing but without the time left.
- **`ShortcutsService` removal**: every caller is in `ShortcutsRuntime` (`instance`, `stopNow`, `startForegroundService`,
  `serviceStarted`/`serviceGone`, `notificationForStart`) and the manifest. The tests are `ShortcutsTest.kt:253-258`
  (`START_NOT_STICKY`, the manifest loop), which §13 names. `AppEnforcer.applyHardening`'s
  `ShortcutsRuntime.requestRefresh()` (`AppEnforcer.kt:731`), `ShortcutsReceiver`, `ShortcutAirplaneActivity`, the
  picker and `MdmSyncWorker`'s report keep working unchanged, because none of them touches the service. `languageChanged`
  (`Application.kt:280`) stays.
- **`shortcutTapAllowed`**: unchanged, and still the one gate for the discs. The panel's new action bypasses it
  exactly as r13's does: `TimeAskReceiver`, `setAuthenticationRequired`, `askStep`.
- **Time request path**: `TimeAsk.askNow` is a straight move of `TimeAskReceiver`'s body
  (`TimeAskReceiver.kt:29-41`) and `ShortcutsRuntime.askForTime` (identical). `askStep` keeps the 3-a-day cap and
  stale taps out. `time_request.json` and the server's `EXTRA_TIME_REQUESTS_A_DAY` are untouched. See H1 for the
  threading.
- **The time-rule screen card**: `rule != null` from `LockActivity.render` (`LockActivity.kt:317`) is the right D4
  input. `LockActivity` isn't `showWhenLocked` (`AndroidManifest.xml:177-180`), so the button is behind Android's
  unlock as well as Vibb's PIN. The row look matches `item_lock_row.xml` (52 dp, 14/10 padding), and the colours
  exist (`kid_accent`, `kid_ink`, `kid_core_dim`). `unlockOpenRow`'s new argument has one caller to change
  (`LockActivity.kt:250`) and its pin (`TimeLockModelTest.kt:190`). TalkBack and i18n are as specified.
- **Server/PWA/contract**: verified that no change is needed beyond D8. The test at `src/tests/shortcuts.rs:229-231`
  pins the current ⓘ text and moves with D8.
- **D-items**: each is a real choice with a sound recommendation. D1 correctly puts the 60 dp cost to the user. D2
  and D6 follow from D1. D4's three reasons hold. D3 is what the approval says.

## Not verified

The emulator measurements themselves (M1-M16): only the probe's source and one frame were looked at. The AOSP
behaviour cited in L2 (actions exempt from deferral), L4 (shedding counts every post of the package) and §2.4
(deleting a channel with an FGS notification throws) was read from documentation and source memory, not re-run.
Nothing was run on the Jelly Star or with the launcher itself.

---

# Revision 2 (d12417042)

Checked against `design-100` at d12417042 (= `main` 6c2f52d50 + the design; `main` itself is now 50ea94467, PLAN only)
and design 101 at `design-101` 28902fa86 (its design, and `server/templates/device_detail.html` as its step 1 left it).
Read for revision 2: the new "Revision 2" section, §2-§3 and §8 where they changed, §9's D9-D12, §14 whole, §15. In the
server: `handlers/device_api.rs` (`time_request`, `phone_zone`), `parent_events.rs` (`extra_time_request`,
`extra_time_told`, `open_extra_time_request`, `raise`), `handlers/lifts.rs` (`record_lift`), `handlers/rule_pause.rs`,
`time_rules.rs` (`PolicyLift`, `TimePolicy`, `deliverable_lifts`), `lock_now.rs` (`the_contract`), `models.rs`
(`PolicyResponse`), `handlers/devices.rs` (`render_device`'s `asked`), `security.rs` (`display_name`), migrations 0025,
0087, 0147, 0148, `retention.rs`, `static/scroll-restore.js`, `static/style.css` (the Safari 14.0 notes), the tests
`device_api.rs::policy_json_keys_snapshot`, `intake_limits.rs`, `settings_pages.rs::check_forms`, `rule_pause.rs`,
`time_request.rs`; `testdata/time_request.json` (byte-identical with the launcher's copy, checked), `lock_now.json`,
`rule_pause.json`; both workflows' path lists and the root `CLAUDE.md`. In the launcher: `timerules/TimeRequests.kt`,
`TimeLeftNotice.kt`, `TimeRules.kt` (`Lift`, `TimePolicy`), `TimeRulesRuntime.kt` (`Snapshot`, the boundary list),
`server/KidModeEnforcer.kt`, `server/StatusCapabilities.kt`, `PolicyResponseCompatTest`, `TimeRequestsTest`. The two
mockups exist only in the session scratchpad (`d-mock/project/Foresporsel.dc.html`, `Telefon.dc.html`); the card's
one was read.

**Verdict.** The one-open-request rule is simple and the phone's state machine holds up: without the 30 minutes the
record lives until an answer to its own id, the phone's day change, or a final verdict, and every case I walked
(offline all afternoon, a reboot - the record is in DE prefs -, the day change in the phone's zone, a clock set back -
the new ask is `stale` at the server and dropped, the server's card stays consistent and a later ask supersedes) ends
in a state the kid can act on. The server side is the right shape too: one `BEGIN IMMEDIATE` per answer, the answer
keyed by the phone's id, the request's day in `phone_zone` like the intake, an older launcher handled by
`superseded`, a flag for an older server. Migration 0149 is the next free number (`main`'s highest is 0148, and no
branch or worktree holds 0149 or higher). No High. Four Medium: the card's form breaks the repo's form rule as
written, "every lift answers" tells the kid "Mamma ga deg 30 min" when the lift can't unlock, the contract change
reaches two more shared files than §14.5 names, and the old rows' "before the migration" rule has no cut-off.

Counts: **0 High, 4 Medium, 11 Low.**

## Findings

### Q100-R2-M1 Medium - the card's form has no fragment of its own group, so `check_forms` fails and the staying post never runs (§14.4, design 100:765-777)

§14.4 gives the card `<form method="post" action="/devices/{id}/time-request/answer">` and says that with JS "it goes
through `scroll-restore.js`'s staying post (`X-Handy-Stay`), so it never jumps to the top". The script posts as a
staying post only "a POST whose action has a #fragment" (`server/static/scroll-restore.js:53-57`), and
`settings_pages::check_forms` (`server/src/tests/settings_pages.rs:136-167`) requires every action on the summary to
carry a fragment **that names the form's own enclosing group**. A fragment-less action is a plain browser post, and
the summary's test fails. The return target `#quick` can't be the action's fragment either, because the card sits
outside `section#quick` (after the header, design-101's `device_detail.html:22-55` and `:193`).

Fix in the design: the action is `/devices/{id}/time-request/answer#ask` (the card's own `section#ask`), and the
route's redirect goes to `/devices/{id}?done=…#quick` as written. The script then follows the server's stay answer to
`#quick`, and since `#ask` no longer exists after the reload, "a #fragment the form's groups don't hold is the anchor"
(`scroll-restore.js:59-60`) lands the parent at Quick actions, right where the card was. Name `summary.rs`'s form list
(`src/tests/summary.rs:290-320`) in §14.6 for the new action.

### Q100-R2-M2 Medium - "every lift answers an open request" can tell the kid "Mamma ga deg 30 min" while the phone stays locked (§14.4 "Every lift answers", design 100:790-793; D9)

Any lift made while a request is open writes that request's `yes` row, "whichever button the parent used". Two
ordinary cases go wrong on the phone:

- The kid asked at "Skjermtiden er brukt opp" (state `used_up`), and later a parent pauses Leggetid from Quick actions
  or the Regler sheet for an unrelated reason. That rule lift closes the request as `yes`. The phone says "Mamma ga deg
  30 min" and the screen stays on the used-up lock.
- The kid asked under a rule (D9's case), and a parent taps Skjermtid's "+30 min" in Quick actions. That budget lift
  closes the request. The phone says "Mamma ga deg 30 min", and the rule keeps it locked.

The kid may ask again (`Answered`), but no one answered this request, and the words promise time that never
comes, which the r13 card and this design both try hard to avoid. Fix: a lift answers an open request only when its target
fits the request's state (a budget lift for `used_up`/`left`/`unknown`, a rule pause for `rule`). Any other lift
leaves the request open, and the card stays. Add a test row to §14.6 ("a rule pause doesn't answer a used-up
request").

The same point weakens D9's own rule. The card picks "pause the rules" from the request's `state`, which is the
phone's report **at the ask** (`TimeAsk::of`, at most 15 min old, `handlers/device_api.rs:2887-2894`). A request asked
at 19:50 under "used up" and answered at 20:40 under Leggetid adds screen time the rule won't let the kid use. The
other way round, a rule that ended pauses nothing. Recommend D9 decides at the answer, from the phone's newest
fresh report (`lock_badge`'s freshness, the same test design 101 §4.1 factors out as `time_state::report_stale`), and
falls back to the ask's state only without one. Say what the card's sub-line shows when they differ.

### Q100-R2-M3 Medium - `lifts[].by`/`at_ms` change two more shared contract files than §14.5 names (§14.2, §14.5)

§14.5: "`server/testdata/time_request.json` is extended (… one file …)". But `PolicyLift` is served inside
`time_policy` in other contracts too:

- `server/src/lock_now.rs:621-652` (`lock_now::tests::the_contract`) serializes a `TimePolicy` with a `PolicyLift`
  built in the test and compares it with `lock_now.json`'s `policy` and `policy_end_locked`. Each has lifts (4
  `expires_at_ms`).
- `server/src/tests/rule_pause.rs:205-216` compares the served lifts' **key sets** with `rule_pause.json`'s
  `policy.time_policy.lifts`.

With `at_ms` always sent (it comes from `created_at`, which every row has), both comparisons fail. `lock_now.json` and
`rule_pause.json` must then be rewritten in both directories in the same commit (and the launcher's `LockNowTest` and
`RulePauseTest` re-read them; they decode with defaults, so only the copies change). The alternative is to make
`at_ms` and `by` `skip_serializing_if` on a lift without them, but `at_ms` would still appear for a seeded lift. Name
the two files in §14.5, in step 2's commit list, and in the root `CLAUDE.md` paragraphs on `lock_now.json` and
`rule_pause.json`. The CI path lists already hold all three files.

Also in §14.5: `policy_json_keys_snapshot` pins `time_policy`'s keys exactly as `["daily_budget_minutes", "lifts",
"rules"]` (`src/tests/device_api.rs:517-520`) on a fresh phone. `answer` is absent there, so the snapshot keeps
passing and doesn't pin `answer`'s keys at all. Say how they are pinned: a seeded answered request in the snapshot
test, or `time_request.json`'s `policy_*` rows standing as the pin, with the snapshot left as it is. Same for the
launcher: `PolicyResponseCompatTest`'s time-policy test (`PolicyResponseCompatTest.kt:1183-1230`) decodes lifts. It
should decode one with `by`/`at_ms` and the `answer` block.

### Q100-R2-M4 Medium - "rows made before the migration" has no cut-off, and keeping two rules forever is the wrong shape (§14.4 "When", design 100:745-748)

`open_extra_time_request` "keeps today's 'no lift since' condition only for rows made before the migration". The
design doesn't say how the code tells such a row apart (an event id recorded at migration, a timestamp, the
`_sqlx_migrations` time?). A permanent second branch in the open-request query would also have to be mirrored in the
policy's `answer` and the intake's supersede.

Simpler: the migration itself backfills. For every `extra_time_request` event that has a `time_lifts` row of the same
device with `datetime(created_at) >= datetime(e.created_at)`, insert a `yes` answer with that first lift's id (and
`answered_by` from `created_by` through `admin_users.username`, `by_name` NULL). The new rule is then the only rule
from the first start, and §14.6 tests one query. An event the backfill leaves open is a real open request of today or
an old one outside every day's window, both harmless. Keep the backfill in 0149 (a migration is frozen from its first
pushed commit, `server/CLAUDE.md` "Data model"), so write the file only in step 2's build commit, after rechecking the
number against `main`.

### Q100-R2-L1 Low - the policy's minutes for a "yes" can be gone before the phone needs them (§14.2, §14.3)

`answer` carries no minutes. `Given(by, minutes)` reads them from the lift `lift_id` names, but `deliverable_lifts`
serves only lifts still running (`time_rules.rs:1009-1035`). A budget lift stays 12 h (`BUDGET_LIFT_DELIVERY_HOURS`),
so the used-up screen after a "+30 min" is fine. A D9 rule pause of 15 min leaves the policy after 15 min, inside
D12's 30 minutes, and "End now" removes it at once. Add `minutes` to `answer` (yes only), so the note never depends on
the lift's delivery.

### Q100-R2-L2 Low - D10's "Given" from a lift needs a filter (§14.3, D10)

The table's "Idle, a lift with `by` or without, given within 30 min" would also catch:

- design 98's phone-key pause (`by` absent by design, `created_by` NULL, `rule_pause.rs:159-161`), which shows "Du fikk
  30 min mer" right after a parent typed the code on the phone to pause a rule;
- the twin lifts with negative ids (`lock_now.rs:226-241`), which duplicate a rule pause;
- a rule pause that isn't screen time at all.

Recommend: D10's note comes only from a `target == "budget"` lift with no `phone_key` and a positive id.

### Q100-R2-L3 Low - nothing re-posts the shade at D12's 30 minutes or at the 15-second "offline" mark (§8.4, §14.3, D12)

The design removes the request's boundary alarm (`openUntilMs` in `TimeRulesRuntime.kt:245`) and adds two new
instants the shade depends on: the answer's or the lift's at + 30 min (D10, D12), and the Pending record's ask + 15 s.
Only the screen gets a `Handler` callback (§8.4). The notice is refreshed by the tracker's minute tick, which doesn't
run while the used-up lock is up, and by syncs. So after a "Nei" on a locked phone, the panel's note can stay well
past 30 minutes, and "sender …" can stay in the shade while the screen says "sendes når …". Put the next of these
instants into `nextBoundary` (where `openUntilMs` was), which also re-renders the screen through `recheck`. That makes
the screen's own 15-second callback unnecessary.

### Q100-R2-L4 Low - `timePart`'s inputs and outputs don't cover what §8.2 and §14.3 ask of it (§8.2, §14.3, design 100:722)

- `timePart(budget, windowOpen, state, lifts, nowMs)` has no `answers`. `Capped`'s "the last answer's note" needs it,
  since `RequestState.Capped` carries nothing.
- The screen uses the note "with no time limit" and the shade only within 30 min (D12). `TimePart` has one `note`.
  Say whether `timePart` returns both (`shadeNote`, `screenNote`) or takes the surface as an argument. `lockAsk` takes
  `note` separately, so the screen's caller needs the unlimited one.
- `requestState(…, answers: TimeAnswers?, …)` needs the top-level `time_request_answers` flag, but the
  `TimeRulesRuntime.Snapshot` that every caller holds carries only the `TimePolicy` (`TimeRulesRuntime.kt:130-139`,
  `KidModeEnforcer.timePolicyOf`). Either put the flag inside `time_policy` (for example `time_policy.answers: true`,
  so it travels and caches with `answer` and survives `inForceAt`'s `copy`), or say that the snapshot gains it.
  Inside `time_policy` is the simpler of the two.

### Q100-R2-L5 Low - "the newest answered request of the phone's day" is ambiguous at midnight (§14.2)

Is it the newest request **asked** today that has an answer, or the newest answer **given** today? A request asked
at 23:50 and answered "no" at 00:05 is the second but not the first. The phone's record is gone at its day change
(Idle), so with the second reading the panel says "Pappa svarte nei kl. 00:05" about yesterday's request. Recommend
"asked today (the intake's day) and answered", the same day as the card's own rule.

### Q100-R2-L6 Low - the supersede and the "already answered" flash need their edge cases written down (§14.4)

- The intake supersedes only when the new request is **stored**: not when it is `capped`, `stale`, a retry of the same
  id, or dropped by `raise` (`parent_events.rs:1126-1160`: the hourly budget, a bad target). Write the supersede
  after `raise` returns `Some`, in the same transaction.
- A card whose request was superseded (the kid's older launcher asked again) is stale, but no parent answered it.
  "{parent} har allerede svart." would name nobody (`answered_by` NULL). Give it its own flash ("{name} har spurt på
  nytt." / "{name} asked again.") or simply land on the new card.
- A lift's transaction must be `BEGIN IMMEDIATE` too, not only the card's. `record_lift` today writes on the pool with
  no transaction, and its security event and `command_notify` are inside it (`handlers/lifts.rs:94-188`). In WAL, a
  deferred transaction that reads the open request and then writes fails with `SQLITE_BUSY_SNAPSHOT` if the card's
  transaction wrote in between, which `busy_timeout` doesn't retry. Say that `record_lift`'s core takes the caller's
  `IMMEDIATE` transaction for every caller (`create_lift`, `rules_now`), with the event and the nudge after commit, as
  the card's route does.

### Q100-R2-L7 Low - the answer in Activity and the inbox: one read path is simpler (§14.4 "For every parent")

"`extra_time_told` gains the answer, read from `time_request_answers` where the rows are read." Event rows are read
in several places (the inbox, Activity, the digest), and `told` works only from the row's `params`. Writing the
answer into the event's `params` in the answer's transaction (`json_set(params, '$.answer', …, '$.by', …)`;
`ExtraTimeParams` gains two optional fields, `serde(default)`) keeps every read site as it is. Store the
answering parent's id there, not a name, so the inbox can word it with display name or login at read time as
designed.

### Q100-R2-L8 Low - the PWA card's details: i18n keys, Safari 14.0, the mockup's path (§14.4)

- i18n: the section's `aria-label` has nb only ("Forespørsel om mer skjermtid"; en "Request for more screen time").
  The security event `time_request_no` needs `security.event.time_request_no` and its detail in both catalogs, and
  `settings_pages.rs:1229`'s kind list. D9's sub-line and button words, the superseded flash (L6) and the catalog key
  names for all of them should be listed as §8.4 lists the phone's.
- The mockup lays the card out as a `display: flex; flex-direction: column; gap: 12px` column. Flex `gap` needs
  Safari 14.1, and the floor is 14.0 (`style.css:1986`, `:2665`: "no flex gap: Safari 14.0"). Grid `gap` for the four
  buttons is fine. Say "margins, not flex gap" for the column.
- `Foresporsel.dc.html` and `Telefon.dc.html` are cited by name only and live in the session scratchpad. Commit them
  under `docs/design/mockups/` (design 99's and 101's pattern) or link the artifact, or the build has no reference.
- Where the card sits: "right after `</header>`" puts it above `#access`, `#oppsett` and `#attention`
  (design-101 `device_detail.html:55-99`). That fits "the top" in PLAN and the mockup's note, but say it, because
  Attention's alarms otherwise come first on the summary.

### Q100-R2-L9 Low - the phone has no way to learn that the server holds no open request for it (§14.1, §12)

The record stays Waiting until an answer or the day ends. If the server loses the request (a restore from a backup
taken before the ask, a phone deleted and re-added, a dev reset), the phone waits all day with no card for any parent
to answer, and the kid can't ask again. Rare, and §12 accepts the unanswered-request case. But one key in the
`answer` block, `open: "<request id>" | null` (the server's open request of the phone's day), would let the phone
drop a Waiting record the server doesn't hold. That makes the "request the server never stored" case close itself.
Optional.

### Q100-R2-L10 Low - the parent's name on the phone: say where it shows, and one source (§14.2, D11)

- Design 62's display name was introduced for the PWA ("by Mamma" lines between parents). Now it shows on the kid's
  screen. The account page's display-name field should say so in one line (nb + en), so a parent who typed a full
  name knows. D11's no-name rule (never the login, which can be an e-mail) is right, and the name is already checked
  for control and bidi characters (`security.rs:55-70`).
- `answer.by` is a snapshot (`by_name`) while `lifts[].by` is read live. The same "yes" can then show two names after
  a rename (the answer's note vs D10's lift note). Prefer the answer's for a lift that has an answer row; or snapshot
  both.

### Q100-R2-L11 Low - small leftovers

- Q100-L4's fix (§2.2, :145) compares only the channel. Revision 2's note line changes the view on the **same**
  channel (the answer arriving on a used-up day), and a shed update there isn't caught. My original suggestion's
  "or a tag in `extras`" (a hash of the key) covers both.
- "Sendes når telefonen er på nett" also shows for a server that answers 5xx or 401 (`RETRY`), when the phone is
  online. That's acceptable as is, but the en "sends when the phone is online" has the same limitation.
- PLAN's entry on `main` (50ea94467) still says "the time left … and 'Be om mer tid' move into the panel", which D1
  reversed. Fix it with the revision's PLAN line.

## The checklist

- **Repo rule for the API**: server and launcher in one commit (step 2), `time_request.json` byte-identical (verified
  today; the extended file goes in both), CI path lists already hold it (`server-ci.yml:73`, `launcher.yml:84`), root
  `CLAUDE.md` paragraph named (§13 step 5, §14.5). Missing: `lock_now.json`, `rule_pause.json` (M3), and how
  `answer`'s keys are pinned (M3). `intake_limits` isn't touched: no report body changes, and the capability rides in
  `capabilities`, whose stored JSON is capped at 4096 bytes (`device_api.rs:1361-1364`; today's list is far under).
  The walker in `intake_limits::the_field_tables_keep_every_contract_key` reads top-level `time_state`, `call_state`,
  `calendar`, `report` and `capability`; the extended `time_request.json` must not name its new top-level keys
  `report` or `calendar` (the planned names don't). Its `capability` (`time_answer_v1`) is only compared with
  Snarveier's, so it is harmless.
- **The migration**: 0149 is free (verified on every local branch and worktree). The schema is sound: PK
  `(device_id, request_id)`, foreign keys with `CASCADE`/`SET NULL` (`foreign_keys(true)`, `main.rs:1943`), retention
  by `LIFT_DAYS`. See M4 for the backfill and the frozen-file timing.
- **First answer wins**: one `BEGIN IMMEDIATE`, the open check first, the insert's conflict as a backstop. That is
  right for two cards. With a lift made elsewhere it is right only if every lift path is `IMMEDIATE` too (L6), and
  which lifts may answer needs M2's rule.
- **Old/new both ways**: an older launcher ignores the keys (`ignoreUnknownKeys`, its `Lift`/`TimePolicy` have
  defaults, `TimeRules.kt:65-86`) and is superseded after its 30 minutes. This launcher on an older server keeps r13's
  lift rule by the flag's absence (L4: where the flag lives). The release order (server first) is stated.
- **The phone's state machine**: verified above. Offline, reboot, the day change, a clock set back and `superseded`
  are consistent. The never-stored case is L9, and the timers the shade needs are L3.
- **The parent's name**: display name only, never the login (D11), never the kid's name on the phone. The PWA's
  words name the phone ("Emma", the placeholder design 101 uses too). nb + en for the phone's strings are listed
  (§8.4), and the PWA's are partly listed (L8).
- **The PWA card**: tokens for light/dark, the hourglass, a grid of four. Its form must follow the repo's form rule
  (M1). Safari 14.0 is OK except flex gap (L8). The push's `#ask` is right while the card is up. A push opened after
  the answer lands at the top of the summary, which is fine for a link (the "no jump" rule is about saves).
- **D9-D12**: real choices. D10, D11 and D12 are sound as recommended, with L2's filter and L3's timers. D9 is sound
  as an intent, but decide its target at the answer (M2).

## Not verified

No emulator, no `cargo test`, no Gradle run. Not checked by running: that the policy write after a nudge reaches
`AnchorNotice.refresh` and `LockActivity`'s listener on the phone (followed by reading only as far as
`TimeRequests.changed` -> `AnchorRefresh`). Not checked: that the anchor's network callback triggers a sync, which
"the request goes when it comes back" relies on (`CommandListenerService.kt:224-239` registers one; its action wasn't
followed). Also not checked: the WAL `SQLITE_BUSY_SNAPSHOT` behaviour cited in L6 (from SQLite's documentation, not
run here), and whether "Emma", "Mamma" and "Pappa" differ from the family's real names, which I can't know.
