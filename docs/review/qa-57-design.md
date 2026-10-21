# Design QA - 57 Only the call's own screen over the PIN lock (usage access), and five small lock fixes

Reviewed on `main` at dd2c4941 (2026-10-21), with the note untracked in the working tree. This is the one design QA
round. I read `docs/design/57-voip-call-screen-over-lock.md` against:
- `CLAUDE.md` and `launcher/CLAUDE.md`: "VoIP calls over the PIN lock (design 17)", "Handy's own PIN lock and call
  screens", "Lock flash (design 32)" and "Kiosk escapes".
- `docs/design/17-voip-calls-over-lock.md`, including QA #4, #10 and its known gaps.
- PLAN's Fix backlog: the 2026-10-21 reviews (S-M4), the user's rule that usage access is mandatory, "VoIP over the
  lock, small launcher fixes" and "Stuck in Recents after an unlock".
- The code the note cites, every line reference opened (list in "What holds").
- AOSP, fetched with `?format=TEXT` on 2026-10-21 into the session scratchpad (`qa57/`), `main` unless named:
  - `ActivityStarter`, `TaskFragment`, `ActivityRecord`, `ActivityTaskManagerService`, `LockTaskController`,
    `ActivityStartInterceptor`;
  - `UsageStatsService` (also `android14-release`, `android15-release`), `UsageStatsManager` (also 14/15),
    `UsageEventsQuery` (also 15), `UsageEvents`;
  - `core/api/current.txt` of `android14-release` and `android15-release`;
  - `PermissionPolicyService` (main, 14, 15), `core/res/AndroidManifest.xml`, `BlockedAppActivity`, `Intent`,
    `Settings`, `ActivityManager`;
  - Permission module `EnhancedConfirmationService`, and `packages/apps/Settings/AndroidManifest.xml`.
- Element X `features/call` (main 2a8ce3ef, the scratchpad copy). I only read it.

Paths: `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`. "Note" = the design note, line numbers as in the
untracked file. I only read; nothing was built or run.

## What holds

- **The user's decisions are carried into §0 faithfully.**
  - Access is mandatory, with no E fallback and no Element X exception.
  - The D rule applies to every app.
  - The lock's start is never a user leave.
  - The parent is told.
  - The privacy limits are written as non-negotiable.
  - The small fixes (a)-(e) are all addressed.
- **Every code claim in §1 checks out**, to the line or within a line or two:
  - `RefrontPolicy.kt:86`; `PinLockState.kt:153/161/171/177/198`.
  - `PinLockRuntime.kt:608-613/611/630/823/911/1070/1262-1305/1395-1403/1665`; `VoipCalls.kt:387-391`.
  - `SelfUpdate.kt:219/338`, `BootHome.kt:49`, `WindowRelock.kt:539`, `MusicCalls.kt:56`, `MusicMediaPlan.kt:155-156`.
  - `ScreenTimeTracker.kt:74`, `KeyguardCover.kt:298`, `PinLockActivity.kt:550`, `LockTaskRoot.kt:50`,
    `LockTaskHelpers.kt:188-201`.
  - `AppEnforcer.kt:635-670`, `OwnPermissions.kt:54-55`, `kid_lock.rs:409-460`, `attention.rs:586`.
  - `PinLockRuntime.show` is the only start of `PinLockActivity`, with `NEW_TASK | REORDER_TO_FRONT | NO_ANIMATION`
    (`PinLockRuntime.kt:1285-1286`).
  - Two small slips are in 57-20.
- **The AOSP claims check out** where the note makes them:
  - **User leave.** `ActivityStarter.java:2719` sets `mUserLeaving` from `FLAG_ACTIVITY_NO_USER_ACTION`.
    `TaskFragment.java:1803-1806` auto-enters PiP only with `userLeaving`, and `:1829` hands it to the client. The note
    misses a supporting line: with Shell transitions, `ActivityStarter.java:1835-1837` also sets
    `setCanPipOnFinish(false)` when the start isn't a user leave. `--activity-no-user-action` is `Intent.java:8923`.
  - **PiP in lock task.** It is refused: `ActivityRecord.java:3380-3397` and `ActivityTaskManagerService.java:3777-3780`.
    Starting lock task removes the pinned tasks (`LockTaskController.java:674`).
  - **Who may read usage events.** `hasQueryPermission` (`UsageStatsService.java:2245-2259`) takes the app-op, else
    `PACKAGE_USAGE_STATS`. The device-owner exemption is only `hasObserverPermission` (`:2271-2284`).
  - **The API 35 query.** `queryEvents(UsageEventsQuery)` with `setPackageNames` is public in API 35
    (`api-android15-release.txt:9486-9527`), as a `@FlaggedApi`. The package filter is applied in the service
    (`queryEventsWithFilter` `:2469-2493`).
  - **Event latency.** Two handler hops: `ActivityRecord.java:5982/5996/6007` -> `ActivityTaskManagerService.java:5261-5276`
    (`mH`) -> `UsageStatsService.java:3228-3246` -> `:1077-1080` -> `:2140`. The timestamp is taken at report time
    (`:3235`). A 1 s allowance for the event's own lag is generous.
  - **101.** It is `START_RETURN_LOCK_TASK_MODE_VIOLATION` (`ActivityManager.java:593-594`, 100 + 1). It comes from
    the target task's lock-task auth (`ActivityStarter.java:2236-2246`, `LockTaskController.java:380-402`), which
    `:816-823` takes from the root activity's package. The HOME feature only maps to a status-bar flag (`:117-118`).
  - **BlockedAppActivity.** It comes only from the interceptor (`ActivityStartInterceptor.java:364-374`, `isActivityAllowed`
    `LockTaskController.java:419-430`). The `START` log line is built before interception (`ActivityStarter.java:1089`
    vs `:1340`).
  - **ECM.** It lists `OPSTR_GET_USAGE_STATS` (`EnhancedConfirmationService.java:261`).
  - **The usage access page.** `ACTION_USAGE_ACCESS_SETTINGS` with `package:` has its own activity in AOSP Settings
    (`AppUsageAccessSettingsActivity` -> `UsageAccessDetails`, Settings `AndroidManifest.xml:2389-2403`).
- **Element X, as the note says:**
  - `ElementCallActivity` is `showWhenLocked` (`:100`). A user-leave hint asks for PiP (`:191-198`), and auto-enter
    is on for S+ (`:319`).
  - `switchToPip` hangs up only when the widget's `canEnterPip()` is false. A refused `enterPictureInPictureMode` is
    only logged (`PictureInPicturePresenter.kt:95-101`), so **a refused PiP does not hang up by itself**.
  - Leaving PiP while not STARTED hangs up (`ElementCallActivity.kt:209-211`).
  - `IncomingCallActivity` and `ElementCallActivity` share the task affinity `io.element.android.features.call`
    (`features/call/impl/src/main/AndroidManifest.xml:32-45`).
- **The overall shape is sound.** The pieces are right:
  - a pure verdict;
  - one file that reads events, guarded by a source scan;
  - the lock's own start flag;
  - fail-closed `null` access;
  - an exit through a button instead of the automatic reopen. Dropping `showVoipCall` removes a real loop.
- **Standing rules.**
  - No stock gesture is removed: `NO_USER_ACTION` changes no gesture.
  - No form is added, so the PWA's no-jump rule isn't touched.
  - The contract change is planned as one commit in both directories (§11 step 3).
  - No secrets.
  - (d) keeps the call-rule exception to "override and pause lift every restriction" and says so.

## Findings

**57-1 (High) - The watcher sees only activity changes. In-place navigation is invisible, and so are single-activity
apps and content intents that bring a chat to the front.** The user's rule is "only the call's own activity". The
note turns this into "only the call's activity class" and never says what that leaves out.

Usage events are activity lifecycle events (`ActivityRecord.java:5979-6017`): RESUMED, PAUSED, STOPPED, DESTROYED,
keyed by class. Inside a resumed activity, a fragment, Compose or window change makes **no event at all**. So the
design can't see any of the following, and once one is in front it's allowed for the whole call:
- **A call screen that switches its content in place.** For example a "back to chats" arrow that swaps fragments, a
  participant picker, or a chat sheet.
- **An app whose call UI lives in its main activity.** For example a call view drawn as a window or fragment over the
  app's single main activity. This is common in single-activity apps (from memory and not checked here: Telegram's
  call view sits over `LaunchActivity`). The learned `ring` or `call` is then the whole app.
- **A ring screen or ongoing-call content intent that brings the app's existing main task forward.** Learning rule 1
  trusts "the first RESUMED after our send" (note:144-146), and the top of that task may be a chat.

The guarantee holds only for apps whose call screen is a dedicated activity whose every exit starts another activity.
Element X qualifies: its own task, and Back and the arrow do nothing (PLAN). For WhatsApp, the arrow that "leads
straight into the chats" (PLAN, S-M4) is the case that matters, and §13 leaves its target to step 1.

Failure scenario: if WhatsApp's arrow swaps `VoipActivityV2`'s content (or a future version does), no RESUMED of
another class ever comes. The chats stay over the lock for the rest of the call, which is S-M4 unchanged.

Fix:
- Say the guarantee in §0/§2.2 as an activity-level one.
- Make step 1's WhatsApp arrow check a **gate**: if it doesn't produce a RESUMED of another class, the note goes
  back to the user before any code.
- Add a pure guard in `learnCallScreens`, with no extra usage data (PackageManager only). Never learn as `ring` or
  `call`:
  - the package's launcher or main activity (`getLaunchIntentForPackage`'s component, any `MAIN`/`LAUNCHER`
    activity, resolved through aliases);
  - a class that already caused a COVER in this ring.
- An app whose call screen is its main activity then can't be over the lock: the kid unlocks to talk. That is a user
  decision (Q-A).
- Tests: `CallScreenPlanTest` "launcher activity never learned", "a class that caused COVER is never learned by the
  button". Mutation: drop the guard.

**57-2 (High) - The re-front loop defeats "Tilbake til samtalen" and the covered card's Svar.**
- `cover` is cleared only by "a later ALLOW" (note:172), which only a watcher poll can produce. That happens at most
  `VOIP_SCREEN_POLL_MS` (500 ms) after the lock leaves the front, plus the event lag.
- Every pause of the LOCKED lock schedules the re-front check after `PAUSE_REFRONT_MS` = 200 ms
  (`RefrontPolicy.kt:102`, `PinLockRuntime.kt:1352`).
- While covered, `voipLockYields(_, cover = true, _)` is false (note:187/190), so that check returns `Refront`
  (`RefrontPolicy.kt:91`).
- The lock is back on top before the watcher's first poll. While the lock is resumed the watcher doesn't run
  (`voipWatchDue`, note:229-231), so `cover` stays set.

The same race hits the RINGING→IN_CALL flip and the pending hand-off: the re-front check reads a `callKnown` that the
last poll left stale.

Failure scenario: the lock covers a WhatsApp call after the arrow. The kid taps "Tilbake til samtalen", the call
screen flashes, and the lock is back about 200 ms later, every time. On a covered ring, Svar answers, and the lock
then covers the answered call.

Fix:
- **Our own send opens an "expected" state.** `SendMark(REOPEN|ANSWER|RING_SCREEN)` sets `expect = until send + VOIP_SCREEN_LEARN_MS`.
- **While `expect` runs, the lock yields** to the package. That is `voipLockYields` with an `expecting` input, and
  `cover` is cleared at the send, not at the ALLOW.
- **The watcher decides** whether the screen that came up is allowed.
- **One decider.** During a lock-facing phase, the re-front check takes the watcher's fresh verdict: one synchronous
  query, or the last verdict if it is younger than one poll. It never reads a `cover`/`callKnown` left by an older poll.
- Tests:
  - `RefrontPolicyTest`: covered + expecting -> Yield, covered + expired -> Refront.
  - `CallScreenPlanTest`: a REOPEN whose first RESUMED is the call clears `cover`.

**57-3 (High) - Emergency SOS loses to the VoIP cover, both through (b) and through the watcher.**
- **(b) is too broad.** It makes `sosGestureLikely` false while "a lock-facing phase other than NONE" (note:298) holds,
  so during any VoIP ring or call a real SOS is never marked.
- **The note's safety argument fails.** "A real SOS during a VoIP ring is still yielded to: 'voip' comes before 'sos'"
  (note:301-302) held only while the VoIP yield was unconditional. Now `voipYields` is false whenever the lock is
  covering, so the re-front loop covers the SOS countdown.
- **The watcher covers SOS too.** With the call screen in front, the SOS activity (another package, a task of its
  own) stops the call screen, which is row 7 -> COVER. `coverVoipCall` checks only `voipOtherScreen` (note:273). That
  is our or Telecom's call, the lock's own emergency flow and the alarm (`PinLockRuntime.kt:911`). An SOS countdown
  is none of these until it dials.
- **This breaks a rule the user named:** "Emergency calls ... always win over VoIP". The protection is 25b-H's yield
  with its narrowing (`RefrontPolicy.kt:181-203`).

Failure scenario: a WhatsApp call is over the lock (or the ring card is up), and the kid presses power five times.
Within 0.5 s the lock is over Emergency SOS's countdown. Whether the countdown still dials behind the lock isn't known.

Fix:
- (b): `voipStartRecent` = only **our own** wake/FSI/answer/reopen start less than `SOS_GESTURE_WINDOW_MS` before the
  stop (the two logged cases). Drop the phase clause.
- `coverVoipCall` also returns without a start while a probable SOS is marked (`sosFlowUntilElapsed`).
- The watcher treats "the call screen stopped within `SOS_GESTURE_WINDOW_MS` of a screen-on, with an SOS app
  present" as a probable SOS: it notes the mark and covers nothing, and the re-front's "sos" yield takes over.
- Tests:
  - `RefrontPolicyTest`: `sosGestureLikely` true during a phase with no recent own start (mutation: restore the
    phase clause).
  - `CallScreenPlanTest`/glue scan: no COVER under the SOS mark.

**57-4 (High) - Unpinning the call package on missing or unknown access kills a live call (design 17 QA #4).**
- **The split ties the pin to the gate.** `voipFacing` sets `lockPinned = null` when access is false or unknown
  (note:114-118), and §4 makes the kiosk-off pin set follow it (note:279-280).
- **Unpinning a package removes its locked task.** `LockTaskController.updateLockTaskPackages` removes every locked
  task that lost allowlisting and clears it for reuse (`LockTaskController.java:764-788`). launcher/CLAUDE.md says the
  same ("removing a package clears its task").
- **That kills the call.** The call app's activity goes, and Element's `onDestroy` stops its call service
  (`ElementCallActivity.kt:226-231`).
- **Design 17 pinned the package for exactly this.** "never dropped while the call may live", and the checklist's
  "kill our process mid-call: the package stays pinned, the call survives".

This happens in three places:
- **A new process mid-call.** `UsageAccess.granted` is `null` until the IO read at init (note:216-219), but
  `VoipCalls.init` -> the first chrome refresh runs first and drops the stored record's pin.
- **A revoke mid-call** (note:222, §10.4 "the call goes on behind it").
- **Any throwing read.**

Failure scenario: kiosk off. A ring comes while LOCKED, so the lock's lock task runs. The kid answers, then the
launcher crashes or updates. The new process unpins the call package and the call ends.

Fix:
- The lock-task pin follows the call, not the gate: `LockTaskChrome.voipPackages` reads `callPackage`, as today's
  `pinnedPackage` does.
- Without access, the re-front still keeps the screen. Pinning only means the platform doesn't kill the task.
- Move `LockTaskChrome.voipPackages` to the "a call exists" row of §2.1's table, and correct §4's sentence.
- Test: `VoipCallPlanTest` `voipFacing(granted = false|null)` keeps `callPackage`. Add a source scan that
  `LockTaskChrome.voipPackages` reads it. Mutation: read `lockPinned`.

**57-5 (High) - §7.4's "grant before updating" can't work, and device check 6 is answered by AOSP: the mode is reset
at once.**
- **How the reset works.**
  - `PermissionPolicyService` watches the op of every app-op permission (`PROTECTION_FLAG_APPOP`, not exempted at
    `:285-292`), `PACKAGE_USAGE_STATS` included (`core/res/AndroidManifest.xml:6793-6794`).
  - It does so with `startWatchingMode(appOpCode, null, mAppOpsCallback)` (`PermissionPolicyService.java:299`).
  - On every change of such an op (`opChanged` -> `:247`) and every package change (`:216`), it runs
    `resetAppOpPermissionsIfNotRequestedForUid` (`:724-772`). That sets the op back to its default mode for any
    package that doesn't request the permission (`:758-769`).
  - `android14-release` and `android15-release` have the same code.
- **So on the old build, which doesn't declare `PACKAGE_USAGE_STATS`, the grant doesn't stick.**
  `adb shell appops set me.vibb.launcher GET_USAGE_STATS allow` is reverted within milliseconds of being set.
- **The new launcher then starts with no access on every field phone,** even where the parent followed the release
  note.

Fix:
- **Roll out in two releases.**
  - A prep launcher release carries only the manifest's `<uses-permission PACKAGE_USAGE_STATS>`, the
    `usage_access_granted` report with `voip_usage_v1`, and the Settings row.
  - That release, the server's warning and the runbook line go out first. The parent grants, and the PWA confirms
    `true`.
  - Only then does the gating release ship.
- If the user prefers one release: say "grant right after the update". Expect every phone to lose VoIP over the lock
  until it is granted, and don't put the pre-update `appops` line in the release note or PLAN.
- Strike device check 6 or turn it into a confirmation of this.
- Keep the manifest test: dropping the `<uses-permission>` in a later build would reset the grant at its update.

**57-6 (High) - "The ring ended into the grace" confirms a Decline, so the app's main screen (the chats) can be
learned as `call`.**
- **The confirmation rule.** Step 3 (note:149-153) confirms a hand-off `pending` as `call` when "the ring ended into
  the grace". The grace follows *every* ring end (`VoipCallPlan.kt:374-380`): Decline, a timeout and a reply-message
  all count.
- **What a Decline can leave in front.** Declining on the app's own ring screen finishes that activity.
- **Where the ring screen shares the app's main task, what resumes next is the app's main activity.** That may be
  the chat list.
- **It passes the hand-off.** That resume comes milliseconds after the ring screen paused, well inside the 2 s
  hand-off. It is then confirmed by the ring's end and allowed (row 4) for the rest of the grace.

Q3 (note:554-558) mentions only "reply".

Failure scenario: the finder rings the kid's WhatsApp from any phone (any caller rings, r7) and taps Decline. If
`VoipActivityV2` sits in WhatsApp's main task, the chat list with previews is over the lock for up to 15 s. This
repeats with every ring.

Fix:
- Only the package's IN_CALL notice confirms a pending.
- **The trade-off stays the user's.** Allowing an unconfirmed pending through the grace (Element's widget load) keeps
  the Decline exposure, bounded at `VOIP_ANSWER_CONFIRM_MS`. Not allowing it costs Element one tap on "Tilbake til
  samtalen" after its own Answer. Restate Q3 with the Decline case and this choice (Q-B).
- Reset `CallScreens` per ring (`ringId`), not per record. The record survives a second ring in the grace
  (`VoipCallPlan.kt:346-352`), and a class learned from a declined ring must not carry over.
- Step 1 must record whether WhatsApp's ring/call screen shares the main task (`dumpsys activity activities`).
- Tests: Decline -> main activity resumed -> COVER at the ring end, and a mutation that restores the "ring ended"
  confirmation.

**57-7 (Medium) - The watcher's state model is underspecified, and the literal reading breaks every call.**

As written, "what a poll sees" is the events of one query window, reduced to `AppFront` (note:135-137). But the
window starts at `max(lastEnd - 1 s, now - 10 s)` (note:234-235). This goes wrong in two ways:
- **A steady call screen.** From the second poll on, a window has no event for it, so "nothing resumed" -> row 7 COVER.
  Even a 10 s window covers any call screen untouched for 10 s.
- **The whole sequence must be folded.** A window can hold RESUMED(chat) and RESUMED(call) together. Its last state
  (the call) allows, although "any other activity in front" (the user's rule) happened.

Three more gaps:
- **Split screen.** Android 10+ resumes several activities at once. `resumed: String?` keeps one, so the call
  resumed last plus chats in the other half reads as ALLOW.
- **A death while visible.** An activity that dies while visible gets `ACTIVITY_DESTROYED` (24, `@hide`) without
  PAUSED (`ActivityRecord.java:6012-6017`). Querying only RESUMED/PAUSED/STOPPED leaves a dead call screen "resumed"
  for ever.
- **The window reaches back before the lock.** A first window of `now - 10 s` can include the kid's own use of the
  app while unlocked. That breaks the privacy limit "only during a VoIP call over the lock". It also feeds learning
  rule 2 ("first RESUMED after the lock-facing RINGING began" - that begins before LOCKED when the ring began unlocked).

Fix (§2.2/§3.2):
- `AppFront` is a fold kept in `CallScreens` memory across polls: a **set** of resumed classes, a set of visible
  (paused, not stopped) classes, and a "non-allowed class resumed since the last verdict" flag. Events are deduped by
  (timestamp, type, class) for the 1 s overlap.
- The query window starts no earlier than the later of the LOCKED edge, the lock-facing phase start and the
  watcher's start. Rule 2 learns only inside it.
- The fold resets when the watcher restarts. Our own lock in front is the known state then.
- Query DESTROYED (24) too, treated as "gone".
- Verdict: COVER if any non-allowed class resumed since the last verdict, or any non-allowed class is resumed now.
- Tests:
  - a call resumed 60 s ago with no new events -> ALLOW;
  - two resumed classes -> COVER;
  - RESUMED(chat), RESUMED(call) in one window -> COVER;
  - DESTROYED without PAUSED;
  - no event before LOCKED is used.

**57-8 (Medium) - Row 6 ("paused, not stopped" = ALLOW) lets Recents, overlays and dialogs of other packages stay
over the lock. (c)'s claim that the watcher ends the BlockedAppActivity flash is wrong.**
- **Row 6's own examples are a permission dialog and PiP** (note:166). The same state covers anything translucent or
  transient of another package over the call screen:
  - Recents' live tile only pauses the activity under it (`RefrontPolicy.kt:94-101`, bug 25b-A);
  - the share sheet (Direct Share shows the kid's chat contacts);
  - `BlockedAppActivity` itself. It is `Theme.Dialog.Confirmation` (`core/res/AndroidManifest.xml:9040-9045`), so the
    call screen under it stays paused and visible. Row 6 holds and nothing covers it. (c)'s step 1 (note:309-310) does
    not do what it says.
- **Where Recents is reachable.** Kiosk off, with a call answered while unlocked, no lock task runs during the call
  (`lockTaskEntry` reads `callPhase`). Design 17 accepted this gap ("Recents stays usable until it ends"), but it
  contradicts the user's rule now.
- **What Recents gives a finder:** other apps' thumbnails (the chat list's snapshot), "Split screen", and other
  packages, which the watcher can't see at all.

Failure scenario: kiosk off. A WhatsApp call was answered unlocked and the phone locked. The finder taps "Tilbake til
samtalen" (the call is learned), then swipes up to Recents. Recents shows WhatsApp's chat thumbnail, and the
re-front yields "voip".

Fix (pick one, Q-D):
- **(a) Start lock task over the call** once the watcher has seen the call screen RESUMED (not PiP), with the package
  pinned and the block bit, as for a ring while LOCKED. Thanks to `NO_USER_ACTION` our own start never makes a PiP.
  The only remaining risk is a PiP the kid made before the lock, which the fold can see (paused, never stopped) - then
  don't start. Home and Overview are then off for the call, as with the kiosk on.
- **(b)** Row 6 ALLOWs only while lock task is LOCKED (`ActivityManager.getLockTaskModeState`); otherwise COVER. A
  kid-made PiP keeps floating over the lock either way.

Rewrite (c) step 1 accordingly: with (a) the flash case is in lock task and the dialog is harmless; without, say it
stays until OK.

**57-9 (Medium) - A failing query fails open.** "Every call is wrapped (`try`/`catch Throwable` -> no verdict, the
next poll tries again)" (note:243-244). A query that throws every time leaves the lock yielding for the whole call.
Examples:
- `NoSuchMethodError` on a build where the `@FlaggedApi` `UsageEventsQuery` isn't there;
- a parcel or runtime error;
- a `SecurityException` from a vendor change.

A refused query returns empty, not `null` (`UsageStatsManager.java:615-623`). That case already ends in row 7 and is
fail-closed.

Fix:
- After `VOIP_WATCH_FAIL_LIMIT` consecutive failed polls (e.g. 3, about 1.5 s), COVER and log once.
- On API 35+, fall back to the API 34 query path on `NoSuchMethodError`/`LinkageError` (subject to 57-11).
- Test: N throwing polls -> COVER. Mutation: no limit.

**57-10 (Medium) - Row 9's 1 s wait counts from the lock leaving the front. Our own starts and our own activities
trip it.**
- **Cold starts.** After our FSI, answer or reopen send, the app's activity may need its process cold-started, often
  1-3 s on a slow phone. Row 9 expires after 1 s with no event, so row 7 COVERs. On a ring the cover means
  `voipRingUi` CARD: the app's own ring screen (17b) is lost on slow phones. On a reopen the button "fails" and covers.
- **Our own pauses.** `voipWatchDue` starts the watcher on any non-resumed lock, our own pauses included:
  - `VoipWakeActivity` over the lock at a ring's wake (`PinLockRuntime.kt:1293-1297`);
  - the lock's new-intent pause;
  - the time-rule screen.

Fix:
- Row 9's base is `max(lock left the front, last SendMark)`, with `VOIP_SCREEN_LEARN_MS` (3 s, measured in step 1)
  after a send and 1 s otherwise.
- `voipWatchDue` also requires none of our own activities in front (`ownOthersResumed == 0`, already kept in
  `PinLockRuntime`).
- Tests: send at t, first RESUMED at t + 2.5 s -> learned, no COVER; the wake activity over the lock -> not due.

**57-11 (Medium) - On API 34 the platform hands every package's activity events to our process.** The note's API 34
path (note:237-238) is `queryEvents(begin, end)`. The service returns every package's events for the window
(`UsageStatsService.java:2458-2466`, a null package filter). They are parcelled into our process and copied event by
event by `getNextEvent`.

That doesn't meet the privacy limit "only for the call app's package" as the user wrote it. "Skipped without being
read further" understates it.

`minSdk` is 34 (`launcher/app/build.gradle.kts:96`). Design 56 discusses 33, where the same applies.

Fix (Q-C): either the watcher runs on API 35+ only, and on 34 the gate behaves as "no access" (`usage_access_granted`
still reported, plus a "needs Android 15" note); or the user accepts it and §0.5 and launcher/CLAUDE.md say it
plainly. Either way, add a source-scan test that the API 35 path is used whenever `SDK_INT >= 35`.

**57-12 (Low) - The read-site table misses readers.** `VoipCalls.phase` is also read at:
- `PinLockActivity.kt:366`: `voipListener`'s `holds` re-runs `ensureLockTask`. It must read `callPhase` like `:550`,
  or the two churn.
- `PinLockRuntime.kt:350`: `bootMarkExempt`.
- `:522`: ProcessStart.
- `:742`: `screenOffSilencesRing`.

`ringId`/`ringingPackage` derive from `phase` (`VoipCalls.kt:97-99`, `:199`). Name each one and its column, and add
it to the read-site source scan (note:487-488).

**57-13 (Low) - Without access, the camera of a video call answered while unlocked is turned off when the phone
locks.**
- **The cause.** `callForCamera` reads the lock-facing phase (note:125), so `cameraCallActive` turns false.
  `CameraLock` then disables the camera while LOCKED, and the live call loses its video.
- **The change.** Today the phase keeps it enabled (`PinLockRuntime.kt:819-826`).
- **Moving the camera is safe.** The camera gesture over the lock is still re-fronted (`voipYields` false).

Fix: move the camera to the `callPhase` column.

**57-14 (Low) - (d) scope and revoke.**
- **Scope.** The note grants READ_PHONE_STATE to every allowed requester (note:335-338). The reason it gives is the
  greyed Phone group, which our denial causes only for apps that also request CALL_PHONE or ANSWER_PHONE_CALLS
  (`callPermissionTargets`, `EnforcementPlan.kt:275-276`). Narrowing to those keeps READ_PHONE_STATE, with call state
  and, for apps targeting below 30, the line number, from going silently to every allowed game. The PLAN text says
  "every allowlisted app", so this is the user's call (Q-E).
- **Revoke.** A runtime revoke (`DENIED`) kills the app's process. Releasing when calls stop being managed ends a
  WhatsApp call in progress. Say so.
- Add the mutation "write order reversed (`[DEFAULT, DENIED]`)", which leaves the app policy-denied.
- The two-write release itself is right: `DEFAULT` alone keeps the grant (`OwnPermissions.kt:52-55`).

**57-15 (Low) - The Settings row and the runbook.**
- **No fallback to the plain list.** "The list if the build ignores the data" (note:380-381) can't happen. An intent
  with a `package:` URI only matches filters with data, and the list filter has none (Settings manifest
  `:2376-2380` vs `:2393-2397`). Resolve the package intent first, else the plain action.
- **The restricted-settings steps are in the wrong order.** Android offers "Allow restricted settings" only after the
  greyed toggle was tapped and its dialog dismissed (state `GUARDED_AND_ACKNOWLEDGED`,
  `EnhancedConfirmationService.java:320-346`, `:356-364`). The runbook's order must be: tap the toggle, OK, App info ›
  ⋮ › Allow restricted settings, then the toggle.
- **ECM's guard also depends on the OEM.** With no trusted-installer list, every installer is trusted (`:434-436`,
  `:463-469`). Keep device check 7.

**57-16 (Low) - The access check differs from the service's.** `granted` = `MODE_ALLOWED` only (note:216-218). The
service also accepts `MODE_DEFAULT` with `PACKAGE_USAGE_STATS` granted (`UsageStatsService.java:2252-2256`). That
permission has the `development` flag (`core/res/AndroidManifest.xml:6793-6794`), so `pm grant` works once it is
declared. Mirror the service, or name `pm grant` as unsupported in the runbook.

`startWatchingMode`'s listener runs on a binder thread. Post to main, as the note implies.

**57-17 (Low) - The poll's binder call runs on the main thread.** The query takes `UsageStatsService`'s lock, which a
disk flush also holds. r7-3 removed binder calls from the lock's main-thread path for this reason.

Fix: run the query on a small background thread and post the verdict. Log only when the verdict changes: a COVER
whose start doesn't take would otherwise log twice a second.

**57-18 (Low) - The device-page warning.**
- **When it shows.** Show it only when VoIP can ring at all: calls managed and on, or unmanaged. With calls off,
  usage access changes nothing.
- **Attention level.** Name `Level::Warning`, as `NoNotificationAccess` (`attention.rs:288`), and say whether
  design 35's parent notifications fire for it.
- **The capability.** `voip_usage_v1` is never read by the server. Either use it, for example to explain the new rule
  only for capable launchers, or drop it.
- **Expected behaviour without access.** With the kiosk on, Android launches a ring's FSI itself on a dark screen.
  That wakes the phone and shows the app's ring screen for up to one re-front (§7.3 says "silent"; §10.4 "no wake").
  Say what to expect.

**57-19 (Low) - Two analysis gaps.**
- **Our other automated starts aren't covered.** §2.4's source scan covers only `PinLockActivity`. Our other starts
  that can land over a live call carry no `NO_USER_ACTION`:
  - `VoipWakeActivity` (`VoipWakeActivity.kt:51`);
  - the time-rule screen at a screen-on re-check;
  - `HomeFront.bring`'s START_HOME with the kiosk on.

  With Element's `canEnterPip()` unknown (§13), any user-leave over its call can hang it up, even in lock task
  (`PictureInPicturePresenter.kt:99-101`). Either give the flag to every automated start, or show none can run over
  a call.
- **(e) path 1's fix may not touch the symptom.** It restores the HOME/OVERVIEW *features*. The symptom, a HOME start
  refused with 101, is a lock-task **auth** refusal, which comes from the target task's root package and the
  package list, not the features (see "What holds"). Label the fallback-features change as a separate hardening, and
  make the `Unlock check` line (with `oursPinned`) the deliverable that finds the cause.

**57-20 (Nit)**
- "When a PiP exits while the activity isn't started (`:210`)" (note:91) is `ElementCallActivity.kt:209-211`, not
  `PictureInPicturePresenter.kt` (105 lines).
- `EnforcementPlan.kt:239` (note:55) is `lockTaskFeatures`. The denial is `:246`.
- "The privacy dashboard shows Vibb One using usage access" (note:64): `GET_USAGE_STATS` is an app-op without a
  permission group. `noteOp` lands in AppOps history (`dumpsys appops`). I didn't find it in the dashboard; unverified.
- §13's "whether an app-op mode set before the permission is declared survives" is answered (57-5).

## Tests - add to §9

Covered above, collected:
- **57-1**
  - the launcher or main activity is never learned;
  - a class that caused COVER is never learned by the button.
- **57-2**
  - covered + expecting -> Yield;
  - the reopen clears `cover`;
  - the re-front check uses a fresh verdict.
- **57-3**
  - `sosGestureLikely` holds during a phase with no own start;
  - no COVER under the SOS mark.
- **57-4**
  - `voipFacing(false|null)` keeps `callPackage`;
  - `LockTaskChrome.voipPackages` reads it.
- **57-6**
  - Decline -> COVER at the ring end;
  - `CallScreens` resets per `ringId`.
- **57-7**
  - a steady call keeps ALLOW;
  - multi-resume -> COVER;
  - an in-window detour -> COVER;
  - DESTROYED;
  - no pre-LOCKED events.
- **57-8**: the row-6 rule chosen (lock task LOCKED, or the lock-task start over a seen call).
- **57-9**: a fail streak -> COVER.
- **57-10**
  - the send-based wait;
  - not due under our own activity.
- **57-11**: SDK path selection by source scan.
- **57-14**: the write-order mutation.

Mutation targets to add:
- the guard in 57-1;
- the `expect` window;
- the SOS clause;
- the `callPackage` pin;
- the "ring ended" confirmation;
- the multi-resume set;
- the fail limit.

## Device checks - add to §10

- **Step 1 gate (57-1, 57-6).**
  - What WhatsApp's arrow and Back do, as `wm_resume_activity` lines.
  - Whether its ring and call screens share the main task.
  - What resumes after Decline on its ring screen.
- **57-3.** During a WhatsApp call over the lock, five power presses: the SOS countdown stays up and dials.
- **57-4.** Kiosk off, a ring while LOCKED, answered, `am crash $P`: the call survives. Revoke access during that
  call: the call survives and the lock comes back.
- **57-5.** On the current release, `appops set ... allow` then `appops get` a second later shows `default`. On the
  prep build it stays `allow` across the next update.
- **57-8.** Kiosk off, a call answered while unlocked, then locked: Recents and split-screen on the call screen.
- **Android 34 vs 35/36 (57-11).** On the emulator's 34 image, the watcher's mode.

## Questions for the user

- **Q-A (57-1).** The design can only tell activities apart. It can't see a call screen that switches to the chats
  inside the same activity, or an app whose call screen is its main screen. Should such apps be denied calls over the
  lock (the kid unlocks to talk)? Recommend yes, via the launcher-activity guard, plus the step 1 gate for WhatsApp.
- **Q-B (replaces Q3, 57-6).** After an answer on the app's own ring screen, the next screen is trusted until the call
  is confirmed. The same window also lets a Decline that falls back to the app's chats stay up to 15 s, with every
  ring. The choice:
  - (a) trust it up to 15 s, confirmed only by the call notice (the Decline exposure remains);
  - (b) trust only screens our own sends opened, so Element's own Answer needs one tap on "Tilbake til samtalen".

  Recommend (b), and (a) only if step 1 shows no app on the phone falls back to its chats on Decline.
- **Q-C (57-11).** On Android 14, reading the events means Android hands us every app's screen changes for the last
  seconds. Recommend: calls over the lock on Android 15+ only, or accept it in writing.
- **Q-D (57-8).** With the kiosk off, a call answered before the phone locked runs without lock task, so Recents and
  split screen work over the lock. Recommend: start lock task over the call once its screen is seen, as for a ring
  while locked.
- **Q-E (57-14).** READ_PHONE_STATE to every allowed app that asks, or only to those whose Phone permission our call
  denial greys out? Recommend the latter.
- The note's Q1 and Q2 stand as written.

## Verdict

**Needs rework (bounded).** The rule, the privacy fence, the start flag, the status contract and fixes (a), (c)-(e)
are close.

The watcher's model and its seams don't hold yet:
- the state it keeps between polls (57-7);
- its handshake with the re-front loop (57-2);
- emergency precedence (57-3);
- the pin split (57-4);
- the hand-off confirmation (57-6);
- the rollout (57-5);
- the guarantee itself (57-1).

Each has a contained fix, but 57-1, 57-6, 57-8 and 57-11 change what the note promises, and need the user's answers
(Q-A to Q-E) first. Then:
1. revise §2.1-§3.3, §5(b)/(c) and §7.4;
2. take the Lows into the note;
3. run step 1's measurements as the gate before step 2.
