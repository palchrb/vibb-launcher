# 57 - Only the call's own screen over the PIN lock (usage access), and five small lock fixes

Asked 2026-10-21 after the user's emulator run (S-M4 confirmed Medium: WhatsApp's call screen has an arrow
straight into the chats over the lock) and the security review `docs/review/security-review-2026-10-21-launcher.md`
S-M4. Builds on design 17/17b (`docs/design/17-voip-calls-over-lock.md`) and round 7 §1
(`docs/design/r7-launcher-small-items.md`). Launcher plus one status object in both directories.
Paths below are under `launcher/app/src/main/java/com/kidslauncher/mdm/` unless they name `server/`.
**Revised the same day after `docs/review/qa-57-design.md`** and the user's answers (the last section maps every
finding to its fix).

## 0. The user's decisions (2026-10-21)

1. **Usage access is mandatory for VoIP over the lock** ("D samtykke må gis uansett, det er en integral del av at
   voip skal funke"). Without it no VoIP app is let over the PIN lock: no wake, no card, no app screen, no exemption.
   The kid unlocks to answer. There is no E fallback and no Element X exception.
2. **Without access the locked phone still rings quietly** (Q1): a quiet ringtone, no card, no wake, nothing opens;
   it stops when the app's ring ends. It tells the kid to unlock and answer.
3. **With access, the D rule for every app, Element X included**: only the call's own activity may be over the
   LOCKED lock. Any other activity in front during the call -> the lock comes back, with "Tilbake til samtalen".
   The guarantee is **activity-level** (QA 57-1): the launcher sees which activity is in front, never what an
   activity draws inside itself.
4. **Only screens our own sends opened are trusted** (Q-B). The activity our full-screen-intent send opened is
   trusted through the answer as the call screen, with no second tap (WhatsApp rings and talks in one
   `VoipActivityV2`). A Decline that shows the chats is another activity and is covered.
5. **An app whose call screen is its main (launcher) activity doesn't ring over the lock** (Q-A): its launcher
   activities are never trusted.
6. **The learned call screen is remembered per app** (new, approved): package + activity class + the app's
   versionCode + its signer, learned only from our own sends; never a launcher activity, never a class that once
   caused a cover; forgotten on any app update or signer change. From the second call on, answering on Element X's
   own ring screen needs no extra tap.
7. **Android 15+ only** (Q-C): the per-package event query is API 35. On Android 14 the phone behaves as without
   usage access (and says so to the parent).
8. **Kiosk off: lock task over the call** once its screen is safely behind our lock (Q-D), ordered so Element X
   can't have entered PiP first (a refused PiP doesn't hang up; removing a PiP it entered does).
9. **The lock's starts over a live call are never a user leave** (`FLAG_ACTIVITY_NO_USER_ACTION`): a test target
   and an emulator check.
10. **The parent is told** (`lock_state.usage_access_granted`, a device-page warning like the notification-access
    one, the PIN-gated Settings row, the setup step). **Rollout in two launcher releases** (QA 57-5, §7.4).
11. **Privacy limits** (non-negotiable): usage events are read only while a VoIP call is live over the lock (LOCKED,
    phase RINGING/IN_CALL), only events since then and at most the last seconds, only the call app's package (the
    service filters, API 35), memory only, never sent; one file reads `UsageStatsManager` (source scan). The only
    thing stored is the remembered call screen (decision 6: package, class, versionCode, signer - not sensitive).
12. Small fixes (PLAN): (a) say why there was no call screen to bring back, (b) no SOS guess for a VoIP ring's own
    screen, (c) the BlockedAppActivity flash on Home in a kiosk-off call, (d) READ_PHONE_STATE for allowed apps whose
    Phone group our call denial greys (Q-E; Q2: the revoke on release also removes a grant the kid gave earlier -
    accepted), (e) the stuck-in-Recents analysis.

## 1. Facts (checked)

Code, as on `main` 20320912:
- The lock yields to whatever is in front while a VoIP phase is on: `RefrontPolicy.kt:86`
  (`inputs.voipCall -> Yield("voip")`), and `step` never shows it over IN_CALL (`PinLockState.kt:153` ProcessStart,
  `:161` ScreenOff, `:171` ScreenOn, `:198` RemoteLock). A LockResumed during IN_CALL re-sends the call notice's
  content intent (`PinLockState.kt:177`, `PinLockRuntime.kt:608-613`, `VoipCalls.reopenCall` `:387-391`): the
  only "failure" there is a `false` logged as "No VoIP call screen to bring back" (`PinLockRuntime.kt:611`).
- The IN_CALL notice is Element-only by channel: `voipNoticeKind` counts a foreground-service notification only on
  `ELEMENT_CALL_CHANNEL` or of category `call` (`VoipCallPlan.kt:22`, `:143`).
- The phase drives the lock **and** non-lock things: `VoipCalls.liveCall` (pinned package) for the self-update gate
  (`server/SelfUpdate.kt:219`, `:338`), the boot Home (`lock/BootHome.kt:49`) and the relock's Home
  (`server/WindowRelock.kt:539`); `VoipCalls.phase` for music's CALL_HOLD (`music/MusicCalls.kt:56`,
  `MusicMediaPlan.kt:155-156`), screen time (`timerules/ScreenTimeTracker.kt:74`), the camera policy
  (`PinLockRuntime.kt:819-826`), the boot mark (`:350`), ProcessStart (`:522`), the ring's power-button silence
  (`:742`), the keyguard cover (`KeyguardCover.kt:298`), the notice wake (`PinLockRuntime.kt:1070`) and kiosk-off lock
  task (`PinLockActivity.kt:366`, `:550` -> `lockTaskEntry(voipCall)`, `LockTaskRoot.kt:50`). `ringId` and
  `ringingPackage` derive from `phase` (`VoipCalls.kt:97-99`, `:199`).
- Kiosk off, a VoIP package is pinned with the permission controller and the app-block bit
  (`server/LockTaskHelpers.kt:191-201`); the lock starts no lock task during a phase, but one already running (the
  ring came while LOCKED) goes on. Kiosk on, the kiosk list is untouched (`:188-190`).
- The lock's only start is `PinLockRuntime.show` (`:1285-1286`): `NEW_TASK | REORDER_TO_FRONT | NO_ANIMATION`. Our
  other automated starts: `VoipWakeActivity` (`VoipWakeActivity.kt:51`), the time-rule screen (`ui/LockActivity.kt:287-291`),
  `HomeFront.bring` (`lock/HomeFront.kt:39`).
- The probable-SOS mark is set by any interactive stop of the lock within 3 s of a screen-on
  (`PinLockRuntime.noteProbableSos` `:1395-1403`, `sosGestureLikely` `RefrontPolicy.kt:219-220`); a VoIP ring's
  FSI is sent 300 ms after the woken lock resumes, so its screen stops the lock inside that window.
- Third-party call permissions: `applyCallPermissions` (`server/AppEnforcer.kt:635-670`) sets CALL_PHONE and
  ANSWER_PHONE_CALLS DENIED for every non-system requester while calls are managed (`EnforcementPlan.kt:246`,
  targets `:275-276`), DEFAULT otherwise. `shouldResetOwnPermissions` (`server/OwnPermissions.kt:52-55`) accepts that
  DEFAULT keeps a grant; music's `BLUETOOTH_CONNECT` uses DENIED for that reason (launcher/CLAUDE.md, music "Badge").

AOSP (main unless named, read 2026-10-21 via `?format=TEXT`):
- **Usage events need the app-op.** `UsageStatsService.hasQueryPermission` (`UsageStatsService.java:2245-2259`):
  SYSTEM_UID, else `noteOp(OP_GET_USAGE_STATS)` ALLOWED, or DEFAULT with `PACKAGE_USAGE_STATS` granted. The
  device-owner exemption is only in `hasObserverPermission` (`:2271-2284`). `queryEvents(UsageEventsQuery)` with
  `setPackageNames` is public API 35 (`UsageStatsManager.java:612-615`, `UsageEventsQuery.java:246`), filtered in the
  service (`queryEventsWithFilter` `:2469-2493`); on 34 `queryEvents(begin, end)` hands every package's events to
  the caller (`:2458-2466`). A refused query returns an empty result (`UsageStatsManager.java:615-623`, per QA).
- **The op is reset for a package that doesn't declare the permission** (QA 57-5): `PermissionPolicyService` watches
  every app-op permission's op and runs `resetAppOpPermissionsIfNotRequestedForUid` on each change
  (`PermissionPolicyService.java:216`, `:247`, `:299`, `:724-772`; the same in 14 and 15). An `appops set` on a build
  without `<uses-permission PACKAGE_USAGE_STATS>` is undone at once.
- **No user leave with `FLAG_ACTIVITY_NO_USER_ACTION`**: `ActivityStarter.java:2719`; `TaskFragment.java:1803-1806`
  auto-enters PiP only with `userLeaving`, `:1829` passes it to the client (`onUserLeaveHint`); with Shell
  transitions `ActivityStarter.java:1835-1837` also sets `setCanPipOnFinish(false)`. `adb shell am start
  --activity-no-user-action` sets it (`Intent.java:8923`).
- **PiP refused is not a hang-up by itself**: in lock task `checkEnterPictureInPictureState` is false
  (`ActivityRecord.java:3380-3397`) and `enterPictureInPictureMode` returns `false` (`ActivityTaskManagerService.java:3776-3779`).
  **Starting lock task removes every pinned (PiP) task** (`LockTaskController.java:674`), and only the focused top
  task may start it (`ActivityTaskManagerService.java:2631-2634`, "Invalid task, not in foreground").
- **Unpinning a package clears its locked tasks** (`LockTaskController.java:764-788`, `performClearTaskForReuse`).
  An allowlisted task joins lock task only when an activity is *launched* in it while LOCKED
  (`ActivityTaskSupervisor.java:892-897`); bringing an existing task forward doesn't, but the mode's status-bar flags
  (no Home, no Overview) hold for the whole screen.
- **BlockedAppActivity** comes only from `ActivityStartInterceptor.interceptLockTaskModeViolationPackageIfNeeded`
  (`:364-374`): lock task LOCKED **and** the block bit **and** a start of a non-pinned package
  (`LockTaskController.isActivityAllowed` `:419-430`). It is a dialog theme (QA: `core/res/AndroidManifest.xml:9040-9045`),
  so what is under it stays visible and paused. The `START u0 {...} from uid` log line is built from the original
  intent before the interceptor (`ActivityStarter.java:1089` vs `:1342`).
- **101** (`START_RETURN_LOCK_TASK_MODE_VIOLATION`) means the target task's lock-task auth isn't allowlisted
  (`ActivityStarter.java:2236-2246`, `LockTaskController.java:339-350`, `:380-402`); the auth comes from the
  package of the task's **root** activity (`:816-823`) - not from the HOME feature.
- **Restricted settings (ECM, Android 15+)**: `EnhancedConfirmationService` lists `OPSTR_GET_USAGE_STATS` among
  `PROTECTED_SETTINGS` (`EnhancedConfirmationService.java:261`). A package is guarded when installed from a local or
  downloaded file, or by an installer that is neither preinstalled nor allowlisted (`:389-437`; with no trusted
  list from the OEM every installer is trusted, `:434-436`, `:463-469`). "Allow restricted settings" is offered only
  after the greyed toggle was tapped and its dialog dismissed (`:320-346`, `:356-364`). `appops set` and `pm grant`
  from adb are not affected.
- **Usage access page**: `ACTION_USAGE_ACCESS_SETTINGS` with `package:` has its own activity in AOSP Settings
  (`AppUsageAccessSettingsActivity` -> `UsageAccessDetails`, Settings `AndroidManifest.xml:2389-2403`); the list's
  filter has no data, so a `package:` intent never falls back to it (`:2376-2380`).
- **Usage events are activity lifecycle events** (`ActivityRecord.java:5979-6017`): RESUMED, PAUSED, STOPPED and
  DESTROYED (24, `@hide`, also sent without a PAUSED for an activity that dies while visible), keyed by class. A
  fragment, Compose or window change inside an activity makes none. Two handler hops of latency, timestamp at report
  time (QA, "What holds").

Element X (main 2a8ce3ef, read only):
- `ElementCallActivity` is singleTask in its own task (`io.element.android.features.call`), `setShowWhenLocked(true)`
  in code (`ElementCallActivity.kt:100`). A user-leave hint asks PiP (`:191-198`); auto-enter on S+ (`:319`). It
  hangs up when the widget's `canEnterPip()` says no (`PictureInPicturePresenter.kt:95-101`) and when a PiP exits
  while the activity isn't started (`ElementCallActivity.kt:209-211`). Its `onDestroy` stops the call service
  (`:226-231`).
- The answer action (`IncomingCallActivity` + `EXTRA_ANSWER_IMMEDIATELY`) answers and `finish()`es in `onCreate`
  (`IncomingCallActivity.kt:98`, `:152-158`), so the first activity it resumes is the call screen. Answering again
  for the same call leaves the call screen as it is (`ElementCallActivity.setCallData`: "Starting the same call
  again, do nothing", `ElementCallActivity.kt:264-265`).
- The call foreground service starts when the widget has loaded (`setCallIsActive` `:176-184`, `isCallActive =
  isWidgetLoaded` `CallScreenPresenter.kt:192`): seconds after the call screen resumed, before the ring notice goes.

**Measured on the user's emulator (2026-10-21, PLAN):**
- Element X rings in `io.element.android.features.call.impl.ui.IncomingCallActivity` and talks in
  `...ui.ElementCallActivity`, both in the call task, separate from its main screens; one activity for audio and video.
- WhatsApp rings and talks in the **same** `com.whatsapp/.calling.ui.VoipActivityV2` instance, audio and video, a new
  task per call: answering on its own ring screen raises no new RESUMED event.
- WhatsApp's call-screen arrow starts `com.whatsapp/.home.ui.HomeActivity` in another task: a new activity.
- Manifest `showWhenLocked` is no shortcut (Element X sets it in code; WhatsApp's only one is
  `VoiceMessagingActivity`).
- Element X's ongoing call: a plain notification on `call_foreground_service_channel`, flags
  NO_CLEAR|FOREGROUND_SERVICE, content intent only. WhatsApp's: CallStyle callType 2 with `android.hangUpIntent`.

So the real Element hang-up path is **a PiP it entered, then removed** (our lock-task start, a swipe-away) - possible
only where PiP is allowed: kiosk off with no lock task running.

## 2. The rule (pure)

### 2.1 The gate: access, Android 15, and what a live call still means

`voipExemption` (`VoipCallPlan.kt:344`) stays: it says whether a VoIP call rings or lives. A new pure function
splits its verdict:

```kotlin
/** Design 57: the lock lets a VoIP call over itself only with usage access on Android 15+ ([overLock]); the call
 * itself still counts as a live call, and its package stays pinned while it may live (QA 57-4, design 17 QA #4). */
data class VoipFacing(val lockPhase: VoipPhase, val callPhase: VoipPhase, val callPackage: String?)
fun voipFacing(verdict: VoipVerdict, overLock: Boolean): VoipFacing
/** Usage access ([granted]: null = unreadable) on a phone that can filter events per package (API 35+). */
fun voipOverLock(granted: Boolean?, sdkInt: Int): Boolean = granted == true && sdkInt >= 35
```

| `overLock` | `lockPhase` | `callPhase` / `callPackage` (pin) |
|---|---|---|
| true | verdict's phase | verdict's phase / pinned |
| false (no access, unknown, or Android 14) | NONE | verdict's phase / pinned |

`VoipCalls.phase` stays the **lock** value (`lockPhase`); `VoipCalls.callPhase` and `liveCall` (= `callPackage !=
null`) are the call. `pinnedPackage` is `callPackage` - the kiosk-off pin follows the call, never the gate. Every
read site, with a source scan in `VoipCallPlanTest`:

| Reader | Takes |
|---|---|
| `PinLockRuntime` events (VoipRinging/VoipEnded, ScreenOff/ScreenOn/RemoteLock/ProcessStart `:522`), `ringId`/`ringingPackage`, the FSI send and the card, `voip_rang`, `screenOffSilencesRing` (`:742`), `KeyguardCover` (`:298`), the boot mark (`:350`), `ScreenTimeTracker` (`exempt`), the notice wake (`:1070`) | `phase` (lock) |
| `VoipRinger` | `phase` for the full ring; `callPhase` for the quiet ring (§2.8) |
| `LockTaskChrome.voipPackages` (`:148-151`), `lockTaskEntry` (`PinLockActivity.kt:366`, `:550`), `callLiveNow`/`lockLeave` (`PinLockRuntime.kt:1665`), the screen-off lock-up's `callLive` (`:630`), the camera (`updateCallForCamera` `:822-827`, QA 57-13) | `callPhase` / `pinnedPackage` (= `callPackage`) |
| `MusicCalls` (`:56`), `SelfUpdate` (`:219`, `:338`), `BootHome` (`:49`), `WindowRelock` (`:539`) | `callPhase` / `liveCall` |

Without access the call app stays pinned (kiosk off) while its call may live, so nothing kills its task; the
re-front loop still keeps its screens behind the lock (the pin decides only what the platform may remove).

### 2.2 The IN_CALL notice, generic

`ELEMENT_CALL_CHANNEL` goes (`VoipCallPlan.kt:22`, `:143`). `voipNoticeKind`'s IN_CALL is now: a foreground-service
notification on **any** channel, of any category, that is neither a ring nor an incoming CallStyle. Only the
record's package counts (`VoipInputs.inCall`), and after the 15 s grace only together with audio
`MODE_IN_COMMUNICATION` (`continueCall`, `VoipCallPlan.kt:377-379`) - so "the package that just rang has a
foreground-service notification and audio is in communication". Element X's measured shape (plain notification,
channel `call_foreground_service_channel`, NO_CLEAR|FOREGROUND_SERVICE, no category) still counts; an incoming
CallStyle never does.

Two places pick one notice of the package, and an app may have more than one foreground notification: the reopen
(§3.4) and `ringCallServiceUp` (the ring stops when the call service is up). Both rank: an ongoing CallStyle
(`EXTRA_CALL_TYPE` = 2) first, then category `call`, then any other foreground notification posted after the ring
began (`VoipNotice.postedAtMs`, from `sbn.postTime`, not text).

### 2.3 Trusted call screens (`lock/CallScreenPlan.kt`, new, pure)

A class is **trusted** for the current ring (`ringId`, QA 57-6: reset per ring, not per record) when:
1. **Our send opened it**: the first RESUMED of the call package at or after our send, within `VOIP_SCREEN_LEARN_MS`
   (3 s, confirmed in step 1). The sends: the ring's full-screen intent (17b), the answer action (the card's Svar),
   the reopen ("Tilbake til samtalen"). A class trusted by the FSI stays trusted through the answer and the call
   (WhatsApp's one `VoipActivityV2`).
2. or **it is remembered** for this package at its current versionCode and signer (§3.3).

Never trusted (`neverTrusted`): the package's launcher activities (every `MAIN`/`LAUNCHER` activity and
`getLaunchIntentForPackage`'s component, resolved through aliases to their target class; read with PackageManager at
the ring's start, off the main thread) and any class in the package's `covered` set (§3.3). A first RESUMED after our
send that is never-trusted is not learned: it is a stray (and covered).

Nothing else is trusted. In particular a screen the app opened by itself (Element's call screen after its own
Answer, a SystemUI-launched FSI) is not, unless remembered. Element's first call: the kid answers on
`IncomingCallActivity` (trusted by the FSI), `ElementCallActivity` comes up untrusted, the lock covers it with 17b's
card (the ring notice lives until Element joins); Svar sends the answer action, which Element treats as the same call,
so `ElementCallActivity` resumes as our send's - trusted and remembered. If the ring has already ended, the cover
shows "Tilbake til samtalen" and the reopen trusts it for this call (remembered only per §3.3's rule).

### 2.4 The fold and the verdict

The watcher keeps one fold per ring in memory (`CallFold`, QA 57-7), fed by every new event (deduped by
timestamp, type and class for the 1 s window overlap):
- `resumed: Set<String>` - classes RESUMED and not since PAUSED/STOPPED/DESTROYED (several at once in split screen);
- `visible: Set<String>` - classes PAUSED and not since STOPPED/DESTROYED (under a translucent dialog, or in PiP);
- `strayResumed: Boolean` - a non-trusted class RESUMED since the last verdict;
- `lastTrustedEvent` - the last event type of a trusted class, and when.

The fold starts empty when the lock leaves the front (our lock in front is the known state), keeps across a
screen-off and screen-on (an ear-blank keeps the screen interactive, a power-off pauses and resumes the activity,
both seen), and resets at the lock's next resume and at a new ring. No event from before the window start is ever
used (§3.2).

`callScreenVerdict(fold, trusted, lockTaskLocked, sinceMs, nowMs) -> ALLOW | COVER | WAIT | SOS`:

| # | Fold | Verdict |
|---|---|---|
| 1 | a stray resumed since the last verdict, or a non-trusted class in `resumed` now | COVER |
| 2 | a trusted class in `resumed`, nothing else in it | ALLOW |
| 3 | nothing resumed, a trusted class in `visible`, lock task LOCKED | ALLOW (a dialog over the call screen in lock task; QA 57-8) |
| 4 | nothing resumed, a trusted class in `visible`, no lock task | COVER (Recents' live tile, a share sheet, another package's dialog) |
| 5 | a trusted class STOPPED within `SOS_GESTURE_WINDOW_MS` of a screen-on, an SOS app present, nothing of the package resumed | SOS (mark a probable SOS, no cover; QA 57-3) |
| 6 | nothing of the package resumed or visible, less than the wait after `since` | WAIT |
| 7 | nothing of the package resumed or visible after the wait (another app, Recents, a dead activity) | COVER |
| 8 | no trusted class at all in an IN_CALL ring | COVER (the button learns one) |

`since` = the later of the lock leaving the front and our last send; the wait is `VOIP_SCREEN_LEARN_MS` (3 s) after
a send, `VOIP_EVENT_LAG_MS` (1 s) otherwise (QA 57-10: a cold-started app needs seconds). A COVER whose cause is a
stray RESUMED adds that class to `covered` (§3.3) unless it is a launcher activity (always untrusted anyway).

### 2.5 The lock and the re-front loop: one decider

```kotlin
/** Design 57: whether the lock stays out of the way of a VoIP call now. [phase] is the lock-facing one; [expecting]:
 * our own send less than VOIP_SCREEN_LEARN_MS ago (QA 57-2); [verdict]: the watcher's, fresh (younger than one poll)
 * or STALE. */
fun voipLockYields(phase: VoipPhase, expecting: Boolean, verdict: CallVerdict, trustedKnown: Boolean): Boolean
```

| `phase` | `expecting` | `verdict` | `trustedKnown` | yields |
|---|---|---|---|---|
| NONE | - | - | - | false |
| RINGING/IN_CALL | true | - | - | true |
| RINGING/IN_CALL | false | ALLOW, WAIT, SOS | - | true (SOS: the "sos" yield takes over, below) |
| RINGING/IN_CALL | false | COVER | - | false |
| RINGING | false | STALE | - | true |
| IN_CALL | false | STALE | true | true |
| IN_CALL | false | STALE | false | false |

- **Our send opens the expectation and clears the cover** (QA 57-2): `SendMark(RING_SCREEN | ANSWER | REOPEN)` sets
  `expect = send + VOIP_SCREEN_LEARN_MS`; while it runs the re-front check yields to the package and the watcher
  decides what came up. The lock's 200 ms pause re-front no longer races the button.
- **One decider**: during a lock-facing phase the re-front check doesn't read a cover or a learned class left by an
  older poll: it asks `CallScreenWatch.verdictNow` (the last verdict if younger than `VOIP_SCREEN_POLL_MS`, else one
  query on the watch thread, the check re-run in its callback).
- `step` keeps its `voip: VoipPhase` inputs (VoipRinging/VoipEnded and their precedence are untouched) and gains
  `voipYields: Boolean` on ProcessStart, ScreenOff, ScreenOn and RemoteLock: every "not over IN_CALL" test
  (`PinLockState.kt:153`, `:161-162`, `:171`, `:198`) reads it. `RefrontInputs.voipCall` becomes `voipYields`
  (`RefrontPolicy.kt:86`), still after our call, Telecom's, the emergency flow and the alarm, and before "sos".
- **LockResumed loses `showVoipCall`** (`PinLockState.kt:177`): the lock no longer re-sends the content intent on its
  own; the button does (§3.4). That removes a loop (cover -> auto reopen -> the app navigates away -> cover).

### 2.6 Kiosk off: lock task over the call (Q-D)

Today the lock never starts lock task during a VoIP phase with the kiosk off (`lockTaskEntry(voipCall)`), because
starting it removes a PiP and Element then hangs up; a call answered before the phone locked therefore runs over the
lock with Home, Recents and split screen working. Lock task can only be started by our lock while it is the focused
top task (§1), so the order is: the lock covers the call screen first, then starts lock task, then the kid goes back.

```kotlin
/** Design 57 (Q-D): the lock (resumed, kiosk off, not in lock task) may start lock task during a VoIP call only when
 * the call app has no PiP that the start would remove: its last event since the LOCKED edge is STOPPED or DESTROYED
 * (fully covered by our NO_USER_ACTION lock), never PAUSED-without-STOPPED (perhaps PiP) or unknown. */
fun lockTaskOverCall(kioskOn: Boolean, running: Boolean, permitted: Boolean, overLock: Boolean, lastCallEvent: CallEvent?): Boolean
```

| kiosk | running | `overLock` | `lastCallEvent` since LOCKED | start lock task |
|---|---|---|---|---|
| on | - | - | - | n/a (Home roots it, as today) |
| off | yes | - | - | no (already) |
| off | no | false | - | no (no events read; today's behaviour) |
| off | no | true | STOPPED or DESTROYED | **yes** |
| off | no | true | PAUSED, RESUMED or none | no |

`lockTaskEntry`'s `voipCall` becomes `callPhase != NONE && !lockTaskOverCall(...)`. The read is one query from the
lock's `onResume` during a lock-facing phase (LOCKED, call live: inside the privacy limit), on the watch thread, then
`ensureLockTask` on the main thread. With lock task running, Home and Overview are off for the whole screen (the
mode's status-bar flags), the pinned call package has the block bit, and row 3 holds. A PiP the kid made before the
lock floats over it; the lock starts no lock task then, and tapping the PiP expands the call screen (a trusted class
-> ALLOW, else row 1).

### 2.7 Every automated start is no user leave

```kotlin
/** Design 57 (QA 57-19): every start of ours that can land over a live call - the PIN lock, the wake window, the
 * time-rule screen, Home - is an automated launch: no onUserLeaveHint, no auto-PiP for the app under it. */
fun automatedStart(flags: Int): Int = flags or Intent.FLAG_ACTIVITY_NO_USER_ACTION
```

Used by `PinLockRuntime.show` (`NEW_TASK | REORDER_TO_FRONT | NO_ANIMATION`), `VoipWakeActivity.intent`
(`VoipWakeActivity.kt:51`: it is started right after the lock, possibly before the lock has resumed, so the app
under could still be the resumed one), `LockActivity.start` (`ui/LockActivity.kt:287-291`) and `HomeFront.intent`
(`lock/HomeFront.kt:39`). Unconditional: with no call the only change is that the app under gets no
`onUserLeaveHint`. A source scan checks that every `startActivity` of these four classes passes through it.

### 2.8 The quiet ring without access (Q1)

```kotlin
/** Design 57 Q1: without over-the-lock access, a ring on a LOCKED phone still sounds - quietly, nothing opens. */
fun voipQuietRingWanted(callPhase: VoipPhase, overLock: Boolean, locked: Boolean, silenced: Boolean,
    otherCall: Boolean, emergencyFlow: Boolean, alarmRinging: Boolean, ringLimitOver: Boolean): Boolean
```

True only for `callPhase == RINGING`, `!overLock`, LOCKED, not silenced (power button), no other call, emergency
flow or alarm, within `VOIP_RING_LIMIT_MS`. `VoipRinger` plays the same ringtone and vibration (`ringPlan`: ringer
mode and DND) at `VOIP_QUIET_RING_VOLUME` (0.3 of the stream volume, `MediaPlayer.setVolume`). No wake, no card, no
FSI send, no exemption; it stops when the app's ring ends. With the kiosk on, Android may launch the app's
full-screen intent itself on a dark screen: the screen lights with the app's ring screen until the next re-front
(<= 0.5 s), then the lock with the quiet ring (QA 57-18).

## 3. Glue, states, persistence

### 3.1 `lock/UsageAccess.kt` (new)

- `granted: Boolean?` (`@Volatile`), mirroring the service (QA 57-16): `unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS,
  uid, pkg)` is `MODE_ALLOWED`, or `MODE_DEFAULT` with `checkSelfPermission(PACKAGE_USAGE_STATS)` granted (so `adb
  shell pm grant $P android.permission.PACKAGE_USAGE_STATS` works too); `null` when it throws.
- Read at `PinLockRuntime.init` (IO), every `apply()`, every status report, and on change:
  `AppOpsManager.startWatchingMode(OPSTR_GET_USAGE_STATS, packageName, listener)`, registered once; the listener
  (a binder thread) posts the re-read to the main thread, which re-runs `VoipCalls.evaluate` (a grant mid-call: no
  trusted class -> the lock stays, the button learns one; a revoke mid-call: lock-facing NONE -> `VoipEnded`, the
  lock comes back, the call goes on behind it, still pinned).
- `VoipCalls.eligible` reads the cached flag: no binder call on the main thread (r7-3).
- Manifest: `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />`
  - without it Vibb One isn't on Android's Usage access page and the op is reset (§1). A manifest test keeps it: a
  later build without it would reset the grant at its update.

### 3.2 `lock/CallScreenWatch.kt` (new - the one file that reads usage events)

- **When**: `voipWatchDue(locked, lockPhase != NONE, overLock, interactive, lockResumed, ownOthersResumed)`: LOCKED, a
  lock-facing phase, access on 35+, screen on, the lock not resumed, and none of our own activities in front
  (`ownOthersResumed == 0`: not the wake window, the time-rule screen or a new-intent pause; QA 57-10). Started and
  stopped from `VoipCalls`' listener, the mode listener, `onLockResumed`/`onLockPaused`/`onLockStopped`, the
  screen receiver and the activity callbacks. Plus the two one-shot reads: `verdictNow` (§2.5) and
  `lockTaskOverCall`'s last event (§2.6).
- **How**: every `VOIP_SCREEN_POLL_MS` (500 ms), on its own thread (`call-screen`, QA 57-17 - the query takes the
  service's lock, which a disk flush also holds), one query `UsageEventsQuery.Builder(begin, now).setPackageNames(pkg)
  .setEventTypes(ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED, 24 /* DESTROYED */)`. `begin` = the latest of
  the LOCKED edge, the lock-facing phase's start, the watcher's start (or our send, if earlier but after both), and
  `lastEnd - 1 s`, never more than `VOIP_EVENTS_WINDOW_MS` (10 s) back (QA 57-7: nothing from before the lock or the
  call). The events update the fold, then `callScreenVerdict`; the verdict is posted to the main thread.
- **Failures fail closed** (QA 57-9): a query that throws (a `LinkageError` on a build without the API included)
  counts; after `VOIP_WATCH_FAIL_LIMIT` (3, ~1.5 s) in a row the verdict is COVER, logged once. Below API 35 the
  watcher never runs (`voipOverLock`).
- **COVER**: `PinLockRuntime.coverVoipCall(reason)` -> `show()` (§2.7 flags) - not while `voipOtherScreen` (our call,
  Telecom's, the emergency flow, an alarm) and not while a probable SOS is marked (`sosFlowUntilElapsed`; QA 57-3).
  **SOS**: `noteProbableSos` from the watcher; the "sos" yield and its narrowing (`LockTaskChrome.sosYield`) take over.
- **Logging**: one line per verdict **change** (QA 57-17): `Call screen: <ALLOW|COVER|SOS> <pkg>/<class or "-">`, and
  `Call screen: trusted <pkg>/<class> (<send kind>|remembered)`. Nothing else from events is logged.
- **Privacy**: the events and the fold live in this file's memory and die with the ring; only a trusted class name
  leaves it (to §3.3's store). No event is sent or written; the report says only granted yes/no.
- **Battery**: two binder calls a second on a background thread, each returning a few events of one package from the
  service's in-memory day, only with the screen on, LOCKED, during a call over the lock and with the lock not in
  front - about 3,600 queries for a 30-minute call, a few CPU-seconds in all, small beside the call's audio, radio and
  screen. No wake lock, no alarm, nothing outside a call. Measured in check 12.

### 3.3 Remembered call screens (CE prefs `voip_screens`, keys pinned)

- `screens_v1`: a string set, each `"<package>|<class>|<versionCode>|<signerSha256>"`; `covered_v1`: the same format,
  classes whose RESUMED caused a COVER. At most 32 entries each, oldest dropped. Package and class names only - not
  sensitive (the user).
- **Learned** only from our own sends (§2.3 rule 1): the FSI and the answer action always; the reopen only through an
  ongoing CallStyle or a category-`call` notice (a plain foreground notice may be any service of the app, so a class
  it opens is trusted for this call but not remembered). Never a never-trusted class, never a class in `covered_v1`;
  a class added to `covered_v1` is dropped from `screens_v1`.
- **Valid** only while the installed package has the same `longVersionCode` and its signing certificate (SHA-256,
  `GET_SIGNING_CERTIFICATES`, the first signer) matches: checked at each ring's start (with the launcher-activity
  read, off the main thread); a mismatch drops that package's entries in both sets. `PACKAGE_REPLACED`/`REMOVED`
  (`Application`'s package callbacks) drop them too.
- `PinLockStore`-style test pins the file and key names (as `UpdateFenceTest` pins the fence's).
- Effect: WhatsApp's `VoipActivityV2` and Element's `IncomingCallActivity` are remembered at their first ring, and
  Element's `ElementCallActivity` at its first answer through the card; from then on Element's own Answer needs no
  tap.

### 3.4 The lock during a call ("Tilbake til samtalen")

- `PinLockActivity` shows a call row on both stages while LOCKED, lock-facing IN_CALL and an IN_CALL notice with a
  content intent exists: the app's icon, "Samtale i <app>" and the button "Tilbake til samtalen". The tap goes
  through `VoipCalls.reopenCall` (the ranked notice, §2.2; now returning `VoipReopen`, §5a), marks
  `SendMark(REOPEN)` and keeps 17b's 1.5 s check: the lock still in front after it -> "Klarte ikke å åpne samtalen.
  Prøv igjen." (`voip_ring_failed`).
- Without a content intent yet (Element's grace before its service) the row shows without the button.
- During RINGING a cover shows 17b's card (the lock came back during the same ring -> `voipRingUi` CARD): Svar sends
  the answer action (`SendMark(ANSWER)`), Avvis the decline.
- Back stays inert; Emergency call and Foreldrekode stay on the row's page.

### 3.5 Persisted

Only §3.3's `voip_screens`. The fold, the trusted set of a ring, the `SendMark`s and `UsageAccess.granted` are
memory: a new process mid-call starts with only the remembered classes trusted, so the lock covers anything else and
the button learns it again.

## 4. Interactions (unchanged unless named)

- **Precedence**: our call, a Telecom call (emergency included), the emergency flow and a ringing alarm still win -
  `refrontAction` checks them before `voipYields` (`RefrontPolicy.kt:80-86`), `VoipRinging`/`VoipEnded` keep their
  `otherScreen` recheck, `VoipCalls.send` keeps `voipCallOrEmergency`, and a COVER never starts the lock over them.
  **Emergency SOS** wins too: no cover under its mark, the watcher sets the mark for a call screen stopped by the
  gesture, and (b) no longer blanks the mark during a phase (§5b).
- **Re-front loop**: §2.5 - one decider, our send's expectation; the watcher is the fast path (<= 500 ms), the loop
  the backstop.
- **Crash guard**: the watcher runs off the main thread and catches everything; nothing new can throw on the main
  thread while the lock exists.
- **KeyguardCover**: reads the lock phase; the button's send is the resumed lock's (no `whenUnlocked` wait).
- **`lockTaskWhileLocked`**: the kiosk-off pin set follows `callPackage` (§2.1), with or without access.
- **Kiosk off**: §2.6 starts lock task over a covered call; without access, today's rule (no start during a call).
- **Kiosk on**: lock task runs, PiP is refused (no hang-up), Home/Overview are off while LOCKED; a link from the call
  screen to another allowed app is row 1 or 7 within one poll.
- **Unlock during a call**: `lockLeave(callLive = callPhase != NONE)` - no Home over the call, as today.

## 5. The small fixes

**(a) Why nothing came back.** `VoipCalls.reopenCall` returns `VoipReopen` = `SENT | NO_CALL_NOTICE` (no IN_CALL
notice of the record's package) `| NO_CONTENT` (a notice without a content intent) `| OTHER_CALL` (`voipCallOrEmergency`)
`| CANCELLED` (`CanceledException`) `| FAILED` (another throw, class name only). The tap logs
`Back to the VoIP call: <result>` (package only). The automatic reopen at LockResumed is gone (§2.5); the button is
the way back.

**(b) No SOS guess for our own VoIP start.** `sosGestureLikely(lastScreenOnAtMs, leftFrontAtMs, sosAppPresent,
ownVoipStartRecent)`: false only when **our own** wake, FSI, answer or reopen start came less than
`SOS_GESTURE_WINDOW_MS` before the stop (`voipWakeAskedAt`, the `SendMark`s) - the two logged cases (16:37:12,
17:31:44.917: the woken lock's FSI send 300 ms after its resume). No phase clause (QA 57-3): a real SOS during a ring
or call is still marked. Both callers pass it (`noteProbableSos`, `pauseRefrontDelayMs`).

**(c) BlockedAppActivity on Home, kiosk off.** From AOSP (§1): it needs lock task LOCKED, the block bit and a start
of a non-pinned package, i.e. the ring came while LOCKED (the lock's lock task kept running), the call app was pinned
with the bit, and the Home press made a non-pinned package start an activity - our HomeActivity is pinned, so it was
someone else's: most likely the stock launcher's quickstep (the 16d pattern) or the call app reacting to its leave
hint.
- It is a dialog (§1): the call screen stays visible and paused under it, and in lock task that is row 3 (ALLOW).
  The flash therefore stays until the kid taps its OK - harmless (nothing opens), and the watcher does not end it.
- Diagnostic for the emulator run: `adb logcat -s ActivityTaskManager:I PinLock:I` - the `START u0 {...} from uid ...`
  line right before BlockedAppActivity's names the blocked component and its caller (check 10).
- The bit stays (it keeps that activity out). If the line names quickstep's RecentsActivity: nothing on our side
  (pinning the recents provider while LOCKED would show Overview's snapshots, 16d). If it names the call app: noted
  for that app, no change.

**(d) READ_PHONE_STATE for allowed apps whose Phone group we grey** (Q-E). Pure (`server/EnforcementPlan.kt`):

```kotlin
/** Design 57 (d): while the call-permission denial is on, READ_PHONE_STATE is granted by policy to the allowed apps
 * whose Phone group the denial greys (they request CALL_PHONE or ANSWER_PHONE_CALLS too) and ask for it; anything
 * we granted goes back to the phone's own choice when the denial ends or the app stops being allowed. Returns the
 * grant-state writes, in order (none when nothing changes). */
fun phoneStateWrites(denyCalls: Boolean, target: Boolean, dpmState: Int): List<Int>
```

| `denyCalls` | `target` | current DPM state | writes |
|---|---|---|---|
| true | true | not GRANTED | [GRANTED] |
| true | true | GRANTED | [] |
| any | false (or `denyCalls` false) | GRANTED (ours) | [DENIED, DEFAULT] (revoke, then un-fix) |
| any | false (or `denyCalls` false) | DENIED (a release cut short) | [DEFAULT] |
| any | false (or `denyCalls` false) | DEFAULT | [] |

- `target` = in `callPermissionTargets` (`EnforcementPlan.kt:275-276`: non-system, not ours, requests CALL_PHONE or
  ANSWER_PHONE_CALLS), requests READ_PHONE_STATE, and allowed: in the effective allowlist
  (`effectiveAllowlist(..., WindowRelock.blockedPackages)`) or a contacts' messaging app; every such app while apps
  are unmanaged (allowlist `null`). The list is the one `applyCallPermissions` already builds (`AppEnforcer.kt:640-645`).
- CALL_PHONE and ANSWER_PHONE_CALLS stay DENIED (`setCallPermissions` unchanged). Written only when the state
  differs. The override and the pause change nothing (call rules are never lifted - the documented exception).
- Release in two writes: DEFAULT alone keeps the grant (`OwnPermissions.kt:52-55`); DENIED revokes and DEFAULT clears
  the policy fix, so the app asks again as on a fresh phone. A grant the kid gave before calls were managed goes too
  (Q2, accepted). **A runtime revoke kills the app's process**: releasing while a WhatsApp call is on (calls switched
  to unmanaged, or WhatsApp taken off the list) ends that call. We never set READ_PHONE_STATE DENIED otherwise, so a
  DENIED found later is our cut-short release.

**(e) Stuck in Recents after an unlock - not reproduced; analysis.** The symptoms (quickstep's RecentsActivity on top,
lock task LOCKED, `am start -c HOME` -> 101) say the HOME start's **target task wasn't allowlisted** (§1: 101 is
the task's root-activity package and the package list, not the HOME feature).
- **The deliverable is the diagnostic** (QA 57-19): on every UNLOCKED edge, after the chrome pass's listeners and
  again 1.5 s later, `Unlock check: lockTask=<NONE|LOCKED|PINNED> plan=<y|n> features=<int> oursPinned=<y|n>
  recentsPinned=<y|n> homeResumed=<y|n>` (DPM and `ActivityManager` reads off the main thread). With the kiosk on and
  our package not pinned or HOME missing from the features: `Chrome still locked after the unlock`, one
  `LockTaskChrome.refresh` + `requestApply` (self-heal, at most once per unlock).
- Paths in our code that leave lock task in its LOCKED shape after an unlock: (1) **no plan in this process**
  (`LockTaskChrome.applyFallback` `:227-262`) - the UNLOCKED pass keeps the platform's LOCKED features and list until
  `requestApply`'s `apply()` lands; (2) **features not verified** (`writeLockTask` `:295-301`) - nothing is pinned
  "this cycle"; (3) **Home's start refused at the leave** (`PinLockActivity.leave`/`go`, `HomeFront.bring`) - the lock
  finishes onto whatever lies under it, quickstep's latent RecentsActivity task; a refused start isn't seen by us.
- **Separate hardening, not the fix for 101**: `applyFallback` on an UNLOCKED kiosk-on pass takes its features from
  the cached `LastEnforcedPlan.lockTaskFeatures` (`server/PolicyGate.kt:113-137`, through
  `lockTaskFeatures(..., blockActivityStart)`) when readable, not the platform's LOCKED set.
- **Emulator recipe** (kiosk on, PIN lock on, calls managed, WhatsApp freshly installed and allowlisted): unlock;
  open WhatsApp; let its first-run permission dialogs run (the permission controller inside lock task); tap the
  greyed Phone permission ("not allowed by admin") to get "App is not available"; press power; unlock. Keep
  `adb logcat -s PinLock PinLockActivity LockTaskChrome ActivityTaskManager` and, when stuck, `adb shell dumpsys
  activity activities | grep -iE "lockTask|Task\{"` (the lock-task state and each task's root). Repeat once with `am
  crash $P` between the dialogs and the power press (path 1).

## 6. Settings row, setup, restricted settings

- **PIN-gated Settings** (`ui/settings/launcher/SettingsFragmentLauncher.kt`, next to "Background connection"
  `:167-182`, `:265-275`): row "Samtaler over låsen". Pure `usageAccessRowState(granted, sdkInt, pageAvailable)` ->
  `ON | CAN_OPEN | PAUSE_FIRST | NEEDS_ANDROID_15 | UNKNOWN`, like `batteryRowState` (`server/BatteryExemption.kt:33-38`).
  The tap resolves `ACTION_USAGE_ACCESS_SETTINGS` with `package:<ours>` first, else the plain action (the list - a
  `package:` intent never matches the list's filter, QA 57-15), each only when it resolves `MATCH_SYSTEM_ONLY` to an
  unsuspended system package that isn't ours (`dialogTarget`, `BatteryExemption.kt:25-26`) - so on a managed phone
  during a pause. On return the state is read again; a change requests a sync.
- **Setup runbook**: `docs/testing/emulator.md` §4, right after `cmd notification allow_listener`:
  `adb shell appops set $P GET_USAGE_STATS allow` (design 57: VoIP over the lock needs it; only on a build that
  declares `PACKAGE_USAGE_STATS`, else Android resets it at once; `pm grant $P android.permission.PACKAGE_USAGE_STATS`
  works too). And a short `docs/setup/usage-access.md` ("Bruksdata for Vibb One"): the adb line; or during a pause
  Settings › Apps › Special app access › Usage access › Vibb One › On; **if greyed** (restricted setting): tap the
  toggle, OK, then App info › ⋮ › Allow restricted settings, then the toggle again (QA 57-15's order).
- **Restricted settings**: which of our install paths are guarded (QR release, `adb install` debug, a self-updated
  build) also depends on the OEM's trusted-installer list: check 8.

## 7. Status contract, PWA, rollout

### 7.1 Contract (one commit in `launcher/` and `server/`)

- Launcher: `LockStateReport.usageAccessGranted: Boolean? = null` (`server/dto/StatusReportRequest.kt:252-267`),
  serialised as `usage_access_granted`, left out when `null`; and `voipOverLockUnsupported: Boolean? = null`
  (`voip_over_lock_unsupported`), `true` only below API 35, else left out. Filled in `PinLockRuntime.report`.
  Capabilities: `voip_usage_v1` (this launcher reports the grant), `voip_gated_v1` (this launcher lets VoIP over the
  lock only with it - the gating release). `PolicyResponseCompatTest` ("status report lock_state uses the server's
  keys") gains both keys and their absence for `null`.
- Server: `kid_lock::LockState.usage_access_granted: Option<bool>` and `voip_over_lock_unsupported: Option<bool>`
  (`#[serde(default, skip_serializing_if = "Option::is_none")]`, kept by `sanitize_lock_state`); `step10`'s lock-state
  round trip with `true`, `false` and missing.

### 7.2 The device-page warning

`LockNote::VoipNeedsUsageAccess { gated }` and `LockNote::VoipNeedsAndroid15` in `lock_card`
(`server/src/kid_lock.rs:409-460`), after `CallsCantRing`, only when the lock is active **and** VoIP can ring at all:
calls unmanaged, or managed with `calls_enabled` (QA 57-18). `VoipNeedsUsageAccess` when `usage_access_granted ==
Some(false)` (never for `None`); `gated` = the report's capabilities hold `voip_gated_v1`. `VoipNeedsAndroid15` when
`voip_over_lock_unsupported == Some(true)`. An Attention row from the same notes (`server/src/attention.rs`, beside
`no_notification_access` `:586`), `Level::Action` like `Kind::NoNotificationAccess` (`:288-302`); no parent
notification (Attention rows aren't `parent_events`). Words (the PWA is English; nb for the record):

| | en (PWA) | nb |
|---|---|---|
| title, gated | Calls in apps don't ring over the lock | Samtaler i apper ringer ikke over låsen |
| sentence, gated | WhatsApp, Signal and Element calls ring only quietly while the phone is locked, and the call screen opens only after unlocking, until Usage access is on for Vibb One. | WhatsApp-, Signal- og Element-samtaler ringer bare lavt mens telefonen er låst, og samtaleskjermen åpner seg først etter opplåsing, til Bruksdata er slått på for Vibb One. |
| title, prep | Turn on Usage access for Vibb One | Slå på Bruksdata for Vibb One |
| sentence, prep | From the next Vibb One update, calls in apps show over the lock only with Usage access. | Fra neste Vibb One-oppdatering vises samtaler i apper over låsen bare med Bruksdata. |
| ⓘ fix (both) | During a pause, on the phone: Vibb One's Settings (Parent PIN) › Calls over the lock (or Settings › Apps › Special app access › Usage access › Vibb One). If the switch is greyed: tap it, OK, then App info › ⋮ › Allow restricted settings. With USB debugging allowed: adb shell appops set me.vibb.launcher GET_USAGE_STATS allow (a debug build: me.vibb.launcher.debug). | Under en pause, på telefonen: Vibb Ones Innstillinger (foreldrekode) › Samtaler over låsen (eller Innstillinger › Apper › Spesiell apptilgang › Bruksdata › Vibb One). Er bryteren grå: trykk på den, OK, så Appinfo › ⋮ › Tillat begrensede innstillinger. Med USB-feilsøking: adb shell appops set me.vibb.launcher GET_USAGE_STATS allow (debug-bygg: me.vibb.launcher.debug). |
| title, Android 14 | Calls in apps can't show over the lock | Samtaler i apper kan ikke vises over låsen |
| sentence, Android 14 | This needs Android 15 or newer. Calls ring quietly while the phone is locked and open after unlocking. | Dette krever Android 15 eller nyere. Samtaler ringer lavt mens telefonen er låst og åpner seg etter opplåsing. |

The nb labels of Android's pages are check 9.

### 7.3 Kid-facing text

None beyond the quiet ring (§2.8). The kid can't grant access (Settings is suspended outside a pause) and the lock
stays calm.

### 7.4 Rollout: two launcher releases (QA 57-5)

An `appops` grant on a launcher that doesn't declare `PACKAGE_USAGE_STATS` is reset at once (§1), so the grant must
come after a build that declares it and before the build that gates on it.

1. **Prep release** - `launcher-v0.35.0` (the next launcher minor) with `server-v0.34.0`: the manifest's
   `<uses-permission PACKAGE_USAGE_STATS>`, `UsageAccess`, the report (`usage_access_granted`,
   `voip_over_lock_unsupported`, capability `voip_usage_v1`), the Settings row, the runbook line; the server's
   `LockState` fields, the "prep" warning and the Attention row. Nothing changes for the kid. The parent grants on
   each phone; the PWA's warning goes away when the phone reports `true`.
2. **Gating release** - `launcher-v0.36.0` (the next minor after it; the server needs nothing new): the gate, the
   watcher, the trusted screens and their store, the quiet ring, lock task over the call, `voip_gated_v1`, and fixes
   (a)-(e). Released only once every phone in the field reports `usage_access_granted: true` (or the parent accepts
   that a phone without it rings only quietly while locked).
- A phone that skips the prep release grants after the update; the "gated" warning shows from its first report.

## 8. Strings (launcher, nb + en)

| id | nb | en |
|---|---|---|
| `voip_call_ongoing` | Samtale i %1$s | Call in %1$s |
| `voip_call_back` | Tilbake til samtalen | Back to the call |
| `voip_ring_failed` (existing) | Klarte ikke å åpne samtalen. Prøv igjen. | Couldn't open the call. Try again. |
| `settings_mdm_calls_over_lock` (title) | Samtaler over låsen | Calls over the lock |
| `settings_mdm_calls_over_lock_on` | På - samtaleskjermen kan vises over låsen | On - the call screen can show over the lock |
| `settings_mdm_calls_over_lock_open` | Av - trykk og slå på Bruksdata for Vibb One | Off - tap and turn on Usage access for Vibb One |
| `settings_mdm_calls_over_lock_pause_first` | Av - sett telefonen på pause først | Off - pause the phone first |
| `settings_mdm_calls_over_lock_needs_15` | Krever Android 15 | Needs Android 15 |
| `settings_mdm_calls_over_lock_unknown` | Ukjent | Unknown |

The call row's button gets `ButtonRole` like the lock's other buttons; the row's text is a polite live region.

## 9. Tests and mutation targets

- `CallScreenPlanTest` (new):
  - trust: the first RESUMED within 3 s after each send kind; a resume at 3.1 s learns nothing; the FSI's class
    stays trusted through IN_CALL (WhatsApp's single activity); a SystemUI-launched ring screen is not trusted unless
    remembered; a launcher activity (and an alias's target) is never trusted, also when it is our send's first
    RESUMED; a `covered` class is never learned by the button; the trusted set resets per `ringId` (a second ring in
    the grace).
  - the fold and the verdict table (§2.4): a call screen resumed 60 s ago with no new events -> ALLOW; two resumed
    classes (split screen) -> COVER; RESUMED(chat) then RESUMED(call) in one window -> COVER; DESTROYED without
    PAUSED -> gone; paused-not-stopped ALLOW only in lock task; the SOS row; the 1 s / 3 s wait from `since`; no event
    before the window start is used; dedupe of the overlap.
  - Decline on an app's own ring screen -> its main activity resumed -> COVER (no "ring ended" confirmation exists).
  - **Mutation targets**: drop the never-trusted guard; trust a class the app opened itself; reset per record instead
    of per ring; a single `resumed` instead of the set; ALLOW for row 4; the 3 s window bound.
- `voipFacing`/`voipOverLock` (`VoipCallPlanTest`): the table of §2.1 (34 -> false, null -> false); `callPackage` kept
  without access. **Mutation**: the pin read from the lock phase.
- `voipNoticeKind`: Element X's measured shape (no category, `call_foreground_service_channel`, NO_CLEAR|FOREGROUND_SERVICE)
  -> IN_CALL; a foreground notice on another channel -> IN_CALL; an incoming CallStyle with the FGS flag -> NONE;
  the ranking for the reopen and `ringCallServiceUp`.
- `voipLockYields` (`VoipCallPlanTest`): the table of §2.5. `RefrontPolicyTest`: covered + expecting -> Yield,
  covered + expired -> Refront, stale + IN_CALL without a trusted class -> Refront; our call/system call/emergency/
  alarm still first; "sos" after a covering VoIP. `PinLockStateTest`: ProcessStart/ScreenOff/ScreenOn/RemoteLock
  with `voipYields` false during IN_CALL show the lock; LockResumed never reopens; VoipRinging/VoipEnded unchanged.
  **Mutation**: the `expecting` row.
- `lockTaskOverCall`: the table of §2.6. **Mutation**: start on PAUSED.
- `automatedStart` + a source scan: every `startActivity` of `PinLockActivity`, `VoipWakeActivity`, `LockActivity` and
  `HomeFront` passes through it; no other file starts `PinLockActivity`. **Mutation**: dropping `NO_USER_ACTION`.
- `voipQuietRingWanted`: the table, and that `VoipRinger` never wakes or sends anything for it.
- `CallScreenGuardTest` (new, like `LockGlanceGuardTest`): `UsageStatsManager`, `UsageEvents`, `UsageEventsQuery`,
  `queryEvents` only in `lock/CallScreenWatch.kt`; the query always has `setPackageNames` and is reached only behind
  `voipOverLock` (`SDK_INT >= 35`); the file's imports are exactly the reviewed ones; no `getSharedPreferences`, file
  or stream write, no `server.dto`/`MdmApi`/`SyncRunner`/crash-report reference; every `Log.` call is one of the two
  verdict lines; a class name leaves only to `CallScreenPlan` and `VoipScreens` (§3.3); the poll runs only behind
  `voipWatchDue`. `UsageAccess.kt` reads only the app-op and the permission.
- `voipWatchDue`: LOCKED x phase x access x interactive x lockResumed x our own activity in front. **Mutation**: polling
  with the lock resumed, the screen off or our wake window up.
- Fail streak: three throwing polls -> COVER. **Mutation**: no limit.
- `VoipScreens` (§3.3): the codec, the 32 cap, a versionCode or signer change drops the package, `covered_v1` blocks a
  learn, the reopen through a plain notice isn't remembered; the prefs file and keys are pinned.
- (a) `VoipReopen` from a fake notice set. (b) `sosGestureLikely` true during a phase with no own start, false within
  3 s of our own start. **Mutation**: restore the phase clause. (d) `phoneStateWrites` table (`EnforcementPlanTest`);
  targets: system apps and ours never, an app without CALL_PHONE/ANSWER_PHONE_CALLS never, every target with a `null`
  allowlist, a relock-blocked package not. **Mutations**: release as `[DEFAULT]` alone; the order reversed
  (`[DEFAULT, DENIED]`). (e) the `Unlock check` decision (`unlockCheckHeals`) and `fallbackFeatures` in `LockTaskHelpersTest`.
- `usageAccessRowState` table; the row opens only a `dialogTarget`, the package intent before the plain one.
- Contract: `PolicyResponseCompatTest` (both keys, absent for null, both capabilities); server
  `kid_lock::voip_usage_notes` (gated/prep words, `None`/`Some(true)` silent, inactive lock silent, calls off silent,
  Android 14 note), `step10` round trip, `attention::voip_usage_row` (`Level::Action`); the manifest check for
  `PACKAGE_USAGE_STATS` (a later build without it fails).
- `VoipCallPlanTest`'s read-site scan: every row of §2.1's table.

## 10. Device and emulator checks

Emulator (Android 16 image, `docs/testing/emulator.md` §6, WhatsApp and Element X from a second account), then the
Jelly Star.

**Step 1 - the gate before any code.** Already measured (2026-10-21, PLAN): Element's ring and call classes and their
task; WhatsApp's single `VoipActivityV2` per call and its new task per call; the arrow -> `home.ui.HomeActivity` in
another task; manifest `showWhenLocked` no use; both ongoing notices' shapes. Still to measure (`adb logcat -b events
| grep -E "wm_(resume|pause|stop|destroy)_activity"` and `dumpsys activity activities`):
1. **WhatsApp's Back** on the call screen and **its Decline** on the ring screen: what resumes, in which task. If
   either reaches the chats **without** a new RESUMED (in place), stop and go back to the user (the activity-level
   guarantee doesn't hold for WhatsApp).
2. **WhatsApp's ongoing notice**: is it a foreground-service notification (the generic IN_CALL needs it)? Any other
   foreground notice during a call?
3. **`--activity-no-user-action`**: kiosk off, no lock task, an Element call on screen: `adb shell am start
   --activity-no-user-action -a android.intent.action.MAIN -c android.intent.category.HOME` -> Element does **not**
   enter PiP; the same without the option -> it does. If the option doesn't stop PiP, stop and revise §2.6/§2.7.
4. **Element's first call through the card**: answer on its own screen; with Vibb's lock over `ElementCallActivity`
   (a plain `am start` of our HOME with the option), the call still connects and the card's Svar (`am`-sent answer
   action, `dumpsys notification --noredact` for the intent) brings `ElementCallActivity` back without a second call.
5. **Cold start**: the time from a send (FSI, reopen) to the RESUMED event with the app's process killed first -
   confirms `VOIP_SCREEN_LEARN_MS` = 3 s.
6. **The op reset** (§1, QA 57-5): on the current release, `appops set $P GET_USAGE_STATS allow`, then `appops get` a
   second later shows `default`.

Then, after step 2's code:
7. With access, LOCKED, screen off, both kiosk modes: a WhatsApp ring shows its ring screen (17b), answering keeps the
   call screen over the lock; the arrow brings the lock back within ~1 s with "Tilbake til samtalen"; the button
   returns to the call; hang-up brings the plain lock. Logcat `Call screen: trusted`/`COVER`. Element X: first call
   through the card (one extra Svar), second call answered on Element's own screen with no extra tap; audio both
   ways for a minute; Home and Recents on the call screen land on the lock; the call survives.
8. Restricted settings: emulator (adb-installed debug) and the Jelly Star (QR release, then after one self-update):
   is the toggle greyed; does the tap-OK-Allow order enable it.
9. The Settings row opens Android's page for Vibb One during a pause and says "pause first" outside one; the nb labels
   for the runbook and the ⓘ text.
10. Without access: a ring while LOCKED - a quiet ringtone, no wake, card or app screen (with the kiosk on a
    SystemUI-launched FSI may light the screen until the next re-front); it stops when the ring ends; unlocked, the
    app rings as usual. Kiosk off, a ring while LOCKED, answered unlocked, `am crash $P`: the call survives (the pin).
    With access, revoke during a call over the lock: the lock comes back, the call goes on.
11. Kiosk off, a call answered while unlocked, then locked: the lock covers it and starts lock task; after "Tilbake",
    Home, Recents and split screen do nothing. With the call put in PiP before locking: no lock-task start, the call
    survives. (c): Home on such a call - the `START` line names the blocked component.
12. Emergency SOS during a WhatsApp call over the lock and during its ring card: five presses keep the countdown up
    and it dials. (b): a ring that wakes the screen logs no "probably Emergency SOS".
13. (d): WhatsApp gets READ_PHONE_STATE by policy while calls are managed, CALL_PHONE stays denied; calls unmanaged ->
    not granted, the app may ask again. (e): the recipe in §5e; the `Unlock check` line after every unlock.
14. Android 14 image: no watcher, the quiet ring, `voip_over_lock_unsupported: true`, the PWA's Android 15 note.
15. Battery: a 10-minute WhatsApp call over the lock with the screen on - `dumpsys batterystats --charged $P` CPU time
    against the same call unlocked.

## 11. Steps

1. **Step 1's measurements** (§10, items 1-6). No code. A stop condition in 1 or 3 goes back to the user.
2. **Prep release** (§7.4.1), launcher and server in one commit for the contract: manifest, `UsageAccess`, the report
   fields and `voip_usage_v1`, the Settings row, the server's `LockState`, the prep note and Attention row, the
   runbook and `docs/setup/usage-access.md`. Tag `launcher-v0.35.0`, `server-v0.34.0`. The parent grants on each phone.
3. **Gating release, the rule**: `voipFacing`/`voipOverLock` and the read-site switch (§2.1), the generic IN_CALL
   (§2.2), `CallScreenPlan` + `CallScreenWatch` + `VoipScreens` (§2.3-§2.5, §3.2-§3.3), `voipLockYields` in `step` and
   `RefrontPolicy`, `lockTaskOverCall` (§2.6), `automatedStart` (§2.7), the quiet ring (§2.8), the call row and
   button, the removal of the automatic reopen; fixes (a) and (b); the tests of §9.
4. **Gating release, the rest**: (c)'s diagnostic note in the emulator doc, (d) `phoneStateWrites`, (e) the `Unlock
   check` line, its self-heal and the fallback-features hardening; `voip_gated_v1`. Tag `launcher-v0.36.0` once the
   phones report the grant (§7.4.2).
5. **Docs**: `docs/testing/emulator.md` §4 and §6, launcher/CLAUDE.md - "VoIP calls over the PIN lock" (the gate, the
   watcher, trusted and remembered screens, the button, the quiet ring, lock task over the call) and "Kiosk escapes",
   whose usage-stats sentence becomes: "...a home/device-owner app has no clean, non-polling signal for another
   package's activity in front (usage stats need the PACKAGE_USAGE_STATS app-op, which a device owner can't grant;
   ruled out for the notification rule - since design 57 read only for a VoIP call over the lock, with the parent's
   grant, on Android 15+, the call app's package only, memory only; `getRunningTasks` ...)". PLAN: S-M4 and the small
   fixes marked built, the two releases.
6. Code QA, fix all findings, the checks of §10, then the Jelly Star.

## 12. Open questions

None open. The QA's Q-A to Q-E and the note's Q1/Q2 are answered (§0).

## 13. Not verified here

- Step 1's items 1-6 (WhatsApp's Back and Decline, its ongoing notice's FGS flag, the user-leave flag against Element's
  PiP, Element's first call through the card while covered, cold-start time, the op reset on the current release).
- Which install paths ECM guards on the target phones (check 8).
- What the Home press in (c) started and the cause of (e): both need the diagnostics' logs.
- Element's `canEnterPip()` answer during a call (a `false` hangs up on any leave hint; §2.7 avoids the hint from our
  starts, not from the kid's own Home press).
- The privacy dashboard's view of the usage-access op (QA 57-20: likely only AppOps history).
- The battery estimate (check 15).

## Revision for qa-57-design (2026-10-21)

| Finding | Fix | Where |
|---|---|---|
| 57-1 (High) activity-level only | The guarantee is stated as activity-level; launcher activities (with aliases) and covered classes are never trusted (Q-A); trust only from our own sends (Q-B); step 1 item 1 is a stop gate for WhatsApp's Back/Decline (the arrow is measured: a new activity) | §0.3-0.5, §2.3, §10.1 |
| 57-2 (High) re-front races the button | `SendMark` opens an expectation that yields and clears the cover at the send; one decider (`verdictNow`), never a stale poll's state | §2.5 |
| 57-3 (High) SOS loses to the cover | (b) only our own starts; no cover under the SOS mark; the watcher marks a probable SOS (row 5) | §2.4, §3.2, §4, §5b |
| 57-4 (High) unpin kills the call | The pin follows `callPackage` with or without access; `voipFacing` keeps it | §2.1, §3.1, §4 |
| 57-5 (High) pre-update grant reset | Two releases: prep (permission + report) then gating; the pre-update `appops` advice and old check 6 removed; the reset is a step 1 confirmation; a manifest test keeps the permission | §1, §3.1, §7.4, §10.6 |
| 57-6 (High) "ring ended" confirms a Decline | Pending/hand-off and its confirmation removed (Q-B); trusted set per `ringId`; Decline -> COVER tested | §2.3, §9 |
| 57-7 (Medium) state model | `CallFold` kept across polls (sets of resumed and visible, the stray flag, dedupe), DESTROYED queried, the window never before LOCKED / the phase / the watcher | §2.4, §3.2 |
| 57-8 (Medium) row 6 and Recents | Lock task over the covered call with the kiosk off (Q-D, ordered against PiP); paused-not-stopped allowed only in lock task; (c) rewritten (the dialog stays until OK) | §2.4, §2.6, §5c |
| 57-9 (Medium) failing query fails open | Fail streak of 3 -> COVER; no API 34 path at all | §3.2 |
| 57-10 (Medium) 1 s wait, own pauses | Wait from `max(lock left, last send)`, 3 s after a send; `voipWatchDue` needs none of our own activities in front | §2.4, §3.2 |
| 57-11 (Medium) API 34 hands all events | Android 15+ only (Q-C), 14 behaves as without access and is reported; source scan for the filtered path | §0.7, §2.1, §3.2, §7 |
| 57-12 (Low) missing readers | `:366`, `:350`, `:522`, `:742`, `ringId`/`ringingPackage` placed in the table; scan covers every row | §2.1, §9 |
| 57-13 (Low) camera | Camera reads `callPhase` | §2.1 |
| 57-14 (Low) (d) scope and revoke | Only `callPermissionTargets` apps (Q-E); the revoke kills the app's process (a call in progress ends); reversed-order mutation | §5d, §9 |
| 57-15 (Low) Settings row and runbook | Package intent first, else the plain action; the restricted-settings order fixed; OEM dependence noted | §6, §7.2 |
| 57-16 (Low) access check | Mirrors the service (ALLOWED, or DEFAULT + the permission); the listener posts to main | §3.1 |
| 57-17 (Low) main-thread binder | The query on its own thread; log only on verdict changes | §3.2 |
| 57-18 (Low) warning | Only when VoIP can ring (calls unmanaged or on); `Level::Action` (the enum has Alarm/Action; `NoNotificationAccess` is Action), no parent notification; capabilities used for the prep/gated words; the kiosk-on FSI flash without access described | §2.8, §7.1, §7.2 |
| 57-19 (Low) other starts; (e) | `automatedStart` for the lock, the wake window, the time-rule screen and Home; (e)'s fallback features labelled a separate hardening, the `Unlock check` line is the deliverable | §2.7, §5e |
| 57-20 (Nit) | `ElementCallActivity.kt:209-211`; `EnforcementPlan.kt:246`; the dashboard claim moved to "not verified"; §13's answered item removed | §1, §13 |
| New (the user) | Remembered call screens per app (§3.3); the quiet ring without access (§2.8); the generic IN_CALL rule (§2.2) | |
