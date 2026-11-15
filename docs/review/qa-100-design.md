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
