# 57 - Only the call's own screen over the PIN lock (usage access), and five small lock fixes

Asked 2026-10-21 after the user's emulator run (S-M4 confirmed Medium: WhatsApp's call screen has an arrow
straight into the chats over the lock) and the security review `docs/review/security-review-2026-10-21-launcher.md`
S-M4. Builds on design 17/17b (`docs/design/17-voip-calls-over-lock.md`) and round 7 §1
(`docs/design/r7-launcher-small-items.md`). Launcher plus one status field in both directories.
Paths below are under `launcher/app/src/main/java/com/kidslauncher/mdm/` unless they name `server/`.

## 0. The user's decisions (2026-10-21)

1. **Usage access is mandatory for VoIP over the lock** ("D samtykke må gis uansett, det er en integral del av at
   voip skal funke"). Without it no VoIP app rings, wakes, shows a card or is let over the PIN lock: as with calls
   off today, the kid unlocks to answer. There is no E fallback (call behind the lock with a call bar) and no
   Element X exception.
2. **With usage access, the D rule for every app, Element X included**: only the call's own activity may be over
   the LOCKED lock. Any other activity in front during the call -> the lock comes back, with "Tilbake til samtalen".
3. **The lock's start over a live call is never a user leave** (`FLAG_ACTIVITY_NO_USER_ACTION`), so a call app
   doesn't try PiP; a test target and an emulator check.
4. **The parent is told**: `lock_state.usage_access_granted` (left out when unknown) and capability
   `voip_usage_v1`; a device-page warning like the missing-notification-access one; a PIN-gated Settings row that
   opens Android's Usage access page; the adb grant in the setup runbook. Phones in the field lose VoIP over the
   lock at the update until it is granted: §7.4 designs the transition.
5. **Privacy limits** (non-negotiable): usage events are read only while a VoIP call is live over the lock (LOCKED,
   phase RINGING/IN_CALL), only the last seconds, only the call app's package, memory only, never stored or sent,
   logged only as the package and class of our decision; one file reads `UsageStatsManager`, guarded by a source
   scan.
6. Small fixes approved in the same round (PLAN "VoIP over the lock, small launcher fixes" and "Stuck in Recents"):
   (a) say why there was no call screen to bring back, (b) no SOS guess for a VoIP ring's own screen, (c) no
   BlockedAppActivity flash on Home during a kiosk-off VoIP call, (d) READ_PHONE_STATE for allowed apps while calls
   are managed, (e) the stuck-in-Recents analysis.

## 1. Facts (checked)

Code, as on `main` 9e399db5:
- The lock yields to whatever is in front while a VoIP phase is on: `RefrontPolicy.kt:86`
  (`inputs.voipCall -> Yield("voip")`), and `step` never shows it over IN_CALL (`PinLockState.kt:153` ProcessStart,
  `:161` ScreenOff, `:171` ScreenOn, `:198` RemoteLock). A LockResumed during IN_CALL re-sends the call notice's
  content intent (`PinLockState.kt:177`, `PinLockRuntime.kt:608-613`, `VoipCalls.reopenCall` `:387-391`): the
  only "failure" there is a `false` logged as "No VoIP call screen to bring back" (`PinLockRuntime.kt:611`).
- The phase drives the lock **and** non-lock things: `VoipCalls.liveCall` (pinned package) for the self-update gate
  (`server/SelfUpdate.kt:219`, `:338`), the boot Home (`lock/BootHome.kt:49`) and the relock's Home
  (`server/WindowRelock.kt:539`); `VoipCalls.phase` for music's CALL_HOLD (`music/MusicCalls.kt:56`,
  `MusicMediaPlan.kt:156`), screen time (`timerules/ScreenTimeTracker.kt:74`, `exempt`), the camera policy
  (`PinLockRuntime.kt:823`), the keyguard cover (`KeyguardCover.kt:298`), the notice wake (`PinLockRuntime.kt:1070`)
  and kiosk-off lock task (`PinLockActivity.kt:550` -> `lockTaskEntry(voipCall)`, `LockTaskRoot.kt:50`).
- Kiosk off, a VoIP package is pinned with the permission controller and the app-block bit
  (`server/LockTaskHelpers.kt:191-201`); the lock starts no lock task during a phase, but one already running (the
  ring came while LOCKED) goes on. Kiosk on, the kiosk list is untouched (`:188-190`).
- The lock's only start is `PinLockRuntime.show` (`:1262-1305`): `NEW_TASK | REORDER_TO_FRONT | NO_ANIMATION`.
- The probable-SOS mark is set by any interactive stop of the lock within 3 s of a screen-on
  (`PinLockRuntime.noteProbableSos` `:1395-1403`, `sosGestureLikely` `RefrontPolicy.kt:219-220`); a VoIP ring's
  FSI is sent 300 ms after the woken lock resumes, so its screen stops the lock inside that window.
- Third-party call permissions: `applyCallPermissions` (`server/AppEnforcer.kt:635-670`) sets CALL_PHONE and
  ANSWER_PHONE_CALLS DENIED for every non-system requester while calls are managed (`EnforcementPlan.kt:239`,
  `:255`, `:275`), DEFAULT otherwise. `shouldResetOwnPermissions` (`server/OwnPermissions.kt:54-55`) accepts that
  DEFAULT keeps a grant; music's `BLUETOOTH_CONNECT` uses DENIED for that reason (launcher/CLAUDE.md, music "Badge").

AOSP (main, read 2026-10-21 via `?format=TEXT`):
- **Usage events need the app-op.** `UsageStatsService.hasQueryPermission` (`UsageStatsService.java:2245-2259`):
  SYSTEM_UID, else `noteOp(OP_GET_USAGE_STATS)` ALLOWED (DEFAULT falls to `PACKAGE_USAGE_STATS`, not grantable to
  us). The device-owner exemption is only in `hasObserverPermission` (`:2271-2284`, app-usage observers, no
  events). `queryEvents(UsageEventsQuery)` with `setPackageNames` is API 35 (`UsageStatsManager.java:612-615`,
  `UsageEventsQuery.java:246`); on 34 only `queryEvents(begin, end)`, which returns every package's events.
  Every query notes the op (the privacy dashboard shows Vibb One using usage access).
- **No user leave with `FLAG_ACTIVITY_NO_USER_ACTION`**: `ActivityStarter.java:2719`
  (`mUserLeaving = (mLaunchFlags & FLAG_ACTIVITY_NO_USER_ACTION) == 0`); `TaskFragment.java:1803-1806` auto-enters
  PiP only with `userLeaving`, and `:1829` passes it to the client (`onUserLeaveHint`). `adb shell am start
  --activity-no-user-action` sets it (`Intent.java:8923`), so step 1 can test it without code.
- **PiP refused is not a hang-up by itself**: in lock task `checkEnterPictureInPictureState` is false
  (`ActivityRecord.java:3380-3399`) and `enterPictureInPictureMode` returns `false` (`ActivityTaskManagerService.java:3776-3779`).
  Starting lock task removes every pinned (PiP) task (`LockTaskController.java:674`).
- **BlockedAppActivity** comes only from `ActivityStartInterceptor.interceptLockTaskModeViolationPackageIfNeeded`
  (`:364-374`): lock task LOCKED **and** the block bit **and** a start of a non-pinned package
  (`LockTaskController.isActivityAllowed` `:419-430`). The `START u0 {...} from uid` log line is built from the
  original intent before the interceptor (`ActivityStarter.java:1089` vs `:1342`): it names what was blocked.
- **101** (`START_RETURN_LOCK_TASK_MODE_VIOLATION`) means the target task's lock-task auth isn't allowlisted
  (`ActivityStarter.java:2236-2246`, `LockTaskController.java:339-350`, `:380-402`); the auth comes from the
  package of the task's **root** activity (`:816-823`) - not from the HOME feature.
- **Restricted settings (ECM, Android 15+)**: `EnhancedConfirmationService` lists `OPSTR_GET_USAGE_STATS` among
  `PROTECTED_SETTINGS` (`packages/modules/Permission .../EnhancedConfirmationService.java:261`, with
  `OPSTR_ACCESS_NOTIFICATIONS` `:259`). A package is guarded when installed from `PACKAGE_SOURCE_LOCAL_FILE`/
  `DOWNLOADED_FILE`, or by an installer that is neither preinstalled nor allowlisted (`:389-437`) - our own
  self-update session makes us that installer. So the Usage access toggle may be greyed ("Restricted setting")
  until "Allow restricted settings" in App info; `appops set` from adb is not affected. Which of our install
  paths end up guarded is a **device check** (QR provisioning, `adb install`, a self-update).

Element X (main 2a8ce3ef, read only):
- `ElementCallActivity` is singleTask in its own task (`io.element.android.features.call`), `setShowWhenLocked(true)`
  (`ElementCallActivity.kt:100`). A user-leave hint asks PiP (`:198`); `enterPipMode` returns false unless RESUMED
  (`:297`); auto-enter on S+ (`:319`). It hangs up when the widget's `canEnterPip()` says no
  (`PictureInPicturePresenter.kt:100`) and when a PiP exits while the activity isn't started (`:210`).
- Answering on `IncomingCallActivity` starts `ElementCallActivity`; the call foreground service (our IN_CALL notice)
  starts when the widget has loaded (`setCallIsActive` `:176-184`, `isCallActive = isWidgetLoaded`
  `CallScreenPresenter.kt:192`) - seconds after the call screen resumed, before the ring notice goes.

So the real hang-up path is **PiP entered, then removed** (our lock task start, a swipe-away) - possible only where
PiP is allowed: kiosk off with no lock task running. `FLAG_ACTIVITY_NO_USER_ACTION` on our lock start keeps Element
from entering PiP there.

## 2. The rule (pure)

### 2.1 Usage access gates everything the lock does for VoIP

`voipExemption` (`VoipCallPlan.kt:344`) stays as it is: it says whether a VoIP call rings or lives. A new pure
function splits its verdict into what the lock may do and what "a call exists" means elsewhere:

```kotlin
/** Design 57: the lock lets a VoIP call over itself only with usage access ([granted]); without it the lock
 * behaves as with calls off - while the call itself still counts as a live call. */
data class VoipFacing(val lockPhase: VoipPhase, val lockPinned: String?, val callPhase: VoipPhase, val callPackage: String?)
fun voipFacing(verdict: VoipVerdict, granted: Boolean): VoipFacing
```

| `granted` | `lockPhase` / `lockPinned` | `callPhase` / `callPackage` |
|---|---|---|
| true | verdict's phase / pinned | verdict's phase / pinned |
| false | NONE / null | verdict's phase / pinned |
| unknown (the read threw) | NONE / null (fail closed) | verdict's phase / pinned |

`VoipCalls` keeps `phase` and `pinnedPackage` as the **lock** values (every lock-side reader keeps its code) and adds
`callPhase`/`liveCall` for the rest. Read sites:

| Reader | Takes | Why |
|---|---|---|
| `PinLockRuntime` events, ringer, FSI, card, `voip_rang` (`ringId`), `LockTaskChrome.voipPackages`, `KeyguardCover`, `ScreenTimeTracker` (`exempt`), camera (`callForCamera`), notice wake | `phase`/`pinnedPackage` (lock) | over-the-lock behaviour |
| `lockTaskEntry` (`PinLockActivity.kt:550`), `callLiveNow`/`lockLeave` (`PinLockRuntime.kt:1665`), the screen-off lock-up's `callLive` (`:630`) | `callPhase` | a call exists: no lock-task start that would remove its PiP, no Home over it at the unlock, no Home under the lock at a dark lock-up |
| `MusicCalls` (`music/MusicCalls.kt:56`), `SelfUpdate` (`:219`, `:338`), `BootHome` (`:49`), `WindowRelock` (`:539`) | `callPhase`/`liveCall` | unchanged meaning: a VoIP call is on |

Why not empty `voipCandidates` (the coordinator's "like calls off"): that also drops music's CALL_HOLD, the
self-update's live-call gate and the "no Home over a call" rule for a call taken on an unlocked phone. The lock's
behaviour is the same either way.

### 2.2 The call's activities (`lock/CallScreenPlan.kt`, new, pure)

What a poll sees of the call app (only its package): its activities' RESUMED/PAUSED/STOPPED events in the window,
reduced to `AppFront(resumed: String?, resumedAtMs, lastPaused: String?, pausedAtMs, visible: Set<String>)` -
`visible` = classes paused and not stopped since (a translucent dialog over them, PiP).

Memory per record (`CallScreens`, never stored): `ring: String?`, `call: String?`, `pending: Pending?(cls, atMs)`,
`cover: Boolean`, the ring's lock-facing start (wall ms) and our last sends (`SendMark(kind = RING_SCREEN | ANSWER |
REOPEN, atMs)`, wall ms - usage events carry wall time).

How the call's activity is learned (`learnCallScreens`), in this order:
1. **Our send**: the first RESUMED of the call package at or after our send, within `VOIP_SCREEN_LEARN_MS` (3 s).
   After the FSI (17b's ring screen) it is `ring`; after the answer action or a reopen (the button) it is `call`.
   This is the user's "the activity that came up for the call".
2. **The ring screen without our send** (SystemUI launched the FSI itself, 17b QA #7): the first RESUMED of the
   package after the lock-facing RINGING began is `ring`.
3. **Answered on the app's own ring screen** (the 17b default): the first RESUMED of another class within
   `VOIP_HANDOFF_MS` (2 s) after `ring` paused is `pending`; it becomes `call` when the call is **answered** (the
   package's IN_CALL notice exists, or the ring ended into the grace: `VoipCalls.ringCallServiceUp`/IN_CALL) within
   `VOIP_ANSWER_CONFIRM_MS` (15 s, Element's widget load - measured in step 1) of its resume; else it is dropped and
   counts as "another activity". At most one pending per ring.
4. **IN_CALL with `ring` resumed and no `call`**: `call = ring` (one activity for both, as WhatsApp's
   `VoipActivityV2` may be - step 1 measures it).

The verdict per poll (`callScreenVerdict(phase, screens, front, answered, nowMs)` -> `ALLOW | COVER`):

| # | Lock phase | What the call app shows | Verdict |
|---|---|---|---|
| 1 | RINGING | `ring` (or the first RESUMED after the ring began - learned now) resumed | ALLOW |
| 2 | RINGING | `pending` resumed, unconfirmed, younger than 15 s | ALLOW |
| 3 | RINGING | `pending` older than 15 s, or another class resumed | COVER (the lock with 17b's card) |
| 4 | IN_CALL | `call` resumed (learned by 1, 3 or 4 above) | ALLOW |
| 5 | IN_CALL | another class resumed | COVER |
| 6 | either | nothing of the package resumed, an allowed class in `visible` | ALLOW (a permission dialog or PiP over it) |
| 7 | either | nothing resumed, nothing allowed visible (another app's activity, Recents, BlockedAppActivity) | COVER |
| 8 | IN_CALL | no `call` known (a call answered before LOCKED, a new process, a dropped pending) | COVER - the button learns it |
| 9 | either | no event for the package yet, less than `VOIP_EVENT_LAG_MS` (1 s) after the lock left the front | ALLOW (wait) |
| 10 | NONE, or not LOCKED, or no access | - | no poll, no verdict |

COVER sets `cover`; a later ALLOW (the kid tapped "Tilbake til samtalen" and `call` resumed) clears it. A new
record (a new ring) starts with empty `CallScreens`.

### 2.3 When the lock steps aside

```kotlin
/** Design 57: the lock stays out of the way of a VoIP call only while it may - RINGING unless covered (17b's own
 * screen), IN_CALL only with the call's activity known and not covered. [phase] is the lock-facing one. */
fun voipLockYields(phase: VoipPhase, cover: Boolean, callKnown: Boolean): Boolean
```

| `phase` | `cover` | `callKnown` | yields |
|---|---|---|---|
| NONE | - | - | false |
| RINGING | false | - | true |
| RINGING | true | - | false |
| IN_CALL | false | true | true |
| IN_CALL | false | false | false |
| IN_CALL | true | - | false |

`step` keeps its `voip: VoipPhase` inputs (VoipRinging/VoipEnded and the precedence are untouched) and gains
`voipYields: Boolean` on ProcessStart, ScreenOff, ScreenOn and RemoteLock: every "not over IN_CALL" test there
(`PinLockState.kt:153`, `:161-162`, `:171`, `:198`) reads `voipYields` instead. `RefrontInputs.voipCall` becomes
`voipYields` (`RefrontPolicy.kt:86`). **LockResumed loses `showVoipCall`** (`PinLockState.kt:177`): the lock no
longer re-sends the content intent on its own - the button does (§3.3). That also removes a loop (cover -> auto
reopen -> the app navigates away -> cover).

### 2.4 The lock's start flags

```kotlin
/** Every start of the PIN lock ([PinLockRuntime.show]): its own task, to the front, no animation (25b-B), and never
 * a user leave (design 57) - the app under it gets no onUserLeaveHint and no auto-PiP. */
fun lockStartFlags(): Int = NEW_TASK or REORDER_TO_FRONT or NO_ANIMATION or NO_USER_ACTION
```

Unconditional, not only during a call: every lock start is an automated launch (Android's own in-call UI and alarm
use the flag for the same reason); with no call the only change is that a video app under the lock gets no
`onUserLeaveHint` at a lock-up (it pauses as before). `HomeFront.bring` is unchanged (a dark lock-up never runs over
a live call, `lockUpStarts`' `callLive`). `VoipWakeActivity` starts in the lock's own task, over the lock: unchanged.

## 3. Glue, states, persistence

### 3.1 `lock/UsageAccess.kt` (new)

- `granted: Boolean?` (`@Volatile`), read with `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, uid,
  pkg) == MODE_ALLOWED` (MODE_DEFAULT counts as not granted: `PACKAGE_USAGE_STATS` is never ours); `null` when it
  throws.
- Read at `PinLockRuntime.init` (IO), every `apply()`, every status report, and on change:
  `AppOpsManager.startWatchingMode(OPSTR_GET_USAGE_STATS, packageName, listener)` - registered once in `init`. A
  change re-runs `VoipCalls.evaluate` on the main thread (a grant mid-call: IN_CALL with no `call` known -> the lock
  stays, the button learns it; a revoke mid-call: lock-facing NONE -> `VoipEnded`, the lock comes back).
- No binder call on the main thread in `VoipCalls.eligible` (r7-3): `evaluate` reads the cached flag.
- Manifest: `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />`
  - without it Vibb One isn't listed on Android's Usage access page.

### 3.2 `lock/CallScreenWatch.kt` (new - the one file that reads usage events)

- Polls only while `voipWatchDue(locked, lockPhase != NONE, granted == true, interactive, lockResumed)`: LOCKED, a
  lock-facing phase, access, screen on, the lock **not** resumed (with the lock in front nothing else is). Started
  and stopped from `VoipCalls`' listener, `PinLockRuntime`'s mode listener, `onLockResumed`/`onLockPaused`/
  `onLockStopped` and the screen receiver. A proximity blank keeps the screen interactive, so the poll runs on
  during an ear call (cheap, see below).
- Every `VOIP_SCREEN_POLL_MS` (500 ms) on the main thread: one query of the window
  `[max(lastEnd - 1 s, now - VOIP_EVENTS_WINDOW_MS), now]` (10 s) - API 35+: `queryEvents(UsageEventsQuery.Builder
  (begin, end).setPackageNames(pkg).setEventTypes(ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED))`; API 34:
  `queryEvents(begin, end)`, every event of another package skipped in the `getNextEvent` loop without being read
  further or kept. The kept events become `AppFront`, then `learnCallScreens` and `callScreenVerdict`.
- COVER: `PinLockRuntime.coverVoipCall(reason)` -> `show()` (§2.4 flags) and `cover = true`. One log line per
  decision: `Call screen: COVER <pkg>/<class or "-">` and `Call screen: learned <ring|call> <pkg>/<class>` - the
  only place a class name is logged. Nothing else is logged from events; nothing stored, sent or handed to another
  object but the verdict and the learned class names (memory, dropped with the record).
- Every call is wrapped (`try`/`catch Throwable` -> no verdict, the next poll tries again): a throw on the main thread
  while the lock exists would count for the crash guard (`RefrontPolicy.kt:146-179`).
- **Battery**: two binder calls a second, each a few in-memory events (the service's current-day interval), only
  with the screen on, LOCKED, during a call over the lock and with the lock not in front - a 30-minute call is
  about 3,600 queries, a few CPU-seconds in all, small beside the call's own audio, radio and screen. Nothing runs
  outside a call; no wake lock, no alarm.

### 3.3 The lock during a call ("Tilbake til samtalen")

- `PinLockActivity` shows a call row on both stages while LOCKED, lock-facing IN_CALL and an IN_CALL notice with a
  content intent exists: the app's icon, "Samtale i <app>" and the button "Tilbake til samtalen". The tap goes
  through `VoipCalls.reopenCall` (now returning a `VoipReopen`, §5a), marks `SendMark(REOPEN)` and keeps 17b's
  1.5 s check: the lock still in front after it -> "Klarte ikke å åpne samtalen. Prøv igjen." (`voip_ring_failed`).
- Without a content intent yet (the 15 s grace before Element's service is up) the row shows without the button
  ("Samtale i <app>"); it appears at the next VoIP pass.
- During RINGING a cover shows 17b's card (the lock came back during the same ring -> `voipRingUi` CARD), so
  Svar/Avvis work as today; Svar marks `SendMark(ANSWER)`.
- Back stays inert; Emergency call and Foreldrekode stay on the row's page.

### 3.4 Persisted

Nothing new. `CallScreens` and `SendMark`s are memory only (privacy limit 5): a new process mid-call starts with no
`call` known, so the lock covers and the button learns it again (§2.2 row 8). `UsageAccess.granted` is memory,
re-read. (d) is stateless (§5d).

## 4. Interactions (unchanged unless named)

- **Precedence**: our call, a Telecom call (emergency included), the emergency flow and a ringing alarm still win -
  `refrontAction` checks them before `voipYields` (`RefrontPolicy.kt:80-86`), `VoipRinging`/`VoipEnded` keep their
  `otherScreen` recheck, `VoipCalls.send` keeps `voipCallOrEmergency`. A COVER never starts the lock over those:
  `coverVoipCall` returns without a start when `voipOtherScreen` (`PinLockRuntime.kt:911`) is true.
- **Re-front loop**: while covered, `voipYields` is false, so a pause/stop of the lock re-fronts (0.5 s, 2 s, 5 s)
  as for any app; the watcher is the fast path (<= 500 ms), the loop the backstop. The "sos" yield stays last.
- **Crash guard**: no new uncaught path (§3.2).
- **KeyguardCover**: reads the lock-facing phase (no cover in a VoIP call over the lock only); the button's send
  waits like any VoIP send (`KeyguardCover.whenUnlocked` is no longer needed there: the send is the resumed lock's).
- **`lockTaskWhileLocked`**: the kiosk-off pin set follows `pinnedPackage` (lock-facing): without access the call
  app isn't pinned and there is no block bit. With access unchanged.
- **Kiosk off, no lock task during the call**: `lockTaskEntry` reads `callPhase`, so the lock never starts lock task
  over a live call (with or without access) - that start would remove a PiP and Element would hang up. The lock's
  `NO_USER_ACTION` start means Element isn't in PiP because of us; a PiP the kid made (Home on the call screen) is
  row 6 (ALLOW) while it floats over the lock.
- **Kiosk on**: lock task runs, PiP is refused (no hang-up, §1); Home/Overview are off while LOCKED; a link from
  the call screen to another allowed app is row 7 within one poll.
- **Unlock during a call**: `lockLeave(callLive = callPhase != NONE)` - no Home over the call, as today.

## 5. The small fixes

**(a) Why nothing came back.** `VoipCalls.reopenCall` returns `VoipReopen` = `SENT | NO_CALL_NOTICE` (no IN_CALL
notice of the record's package) `| NO_CONTENT` (a notice without a content intent) `| OTHER_CALL` (`voipCallOrEmergency`)
`| CANCELLED` (`CanceledException`) `| FAILED` (another throw, class name only). The tap logs
`Back to the VoIP call: <result>` (package only). The automatic reopen at LockResumed is gone (§2.3); the button is
the way back.

**(b) No SOS guess for a VoIP screen.** `sosGestureLikely(lastScreenOnAtMs, leftFrontAtMs, sosAppPresent,
voipStartRecent)`: false when `voipStartRecent` - a lock-facing phase other than NONE, or our wake/FSI/answer/reopen
start less than `SOS_GESTURE_WINDOW_MS` before the stop (`voipWakeAskedAt`, the `SendMark`s). Both callers pass it
(`noteProbableSos`, `pauseRefrontDelayMs`). The two logged cases (16:37:12, 17:31:44.917) were the woken lock's FSI
send (300 ms after its resume) inside the 3 s window. A real SOS during a VoIP ring is still yielded to: "voip"
comes before "sos" in `refrontAction`.

**(c) BlockedAppActivity on Home, kiosk off.** From AOSP (§1): the flash needs lock task LOCKED plus the block bit,
i.e. the ring came while LOCKED (the lock's lock task kept running) and the call app was pinned with the bit; then
the Home press made some non-pinned package start an activity. Our HomeActivity is pinned, so the blocked start was
someone else's: most likely the stock launcher's quickstep (`RecentsActivity` of the navigation gesture, the 16d
pattern) or the call app reacting to its leave hint. Changes:
1. The watcher covers it: BlockedAppActivity isn't the call app, so row 7 brings the lock within one poll (the
   lock's start has `NO_USER_ACTION`).
2. Diagnostic for the emulator run: `adb logcat -s ActivityTaskManager:I PinLock:I` - the `START u0 {...} from uid
   ...` line right before BlockedAppActivity's names the blocked component and its caller.
3. If it is quickstep's RecentsActivity: nothing more on our side (Overview is off by the lock-task features; pinning
   the recents provider while LOCKED would show Overview's snapshots over the lock, 16d). If it is the call app's own
   start: the same - the bit is what keeps that activity out, the watcher what ends the flash.
   The bit stays: dropping it would let another package's activity run in the call app's task until the next poll.

**(d) READ_PHONE_STATE for allowed apps.** Pure (`server/EnforcementPlan.kt`):

```kotlin
/** Design 57 (d): while the call-permission denial is on, READ_PHONE_STATE is granted by policy to the allowed
 * apps that ask for it; anything we granted goes back to the phone's own choice when the denial ends or the app
 * stops being allowed. Returns the grant-state writes, in order (none when nothing changes). */
fun phoneStateWrites(denyCalls: Boolean, allowed: Boolean, dpmState: Int): List<Int>
```

| `denyCalls` | `allowed` | current DPM state | writes |
|---|---|---|---|
| true | true | not GRANTED | [GRANTED] |
| true | true | GRANTED | [] |
| any | false (or `denyCalls` false) | GRANTED (ours) | [DENIED, DEFAULT] (revoke, then un-fix) |
| any | false (or `denyCalls` false) | DENIED (a release cut short) | [DEFAULT] |
| any | false (or `denyCalls` false) | DEFAULT | [] |

- Targets: non-system packages that request READ_PHONE_STATE (the same `getInstalledPackages(GET_PERMISSIONS)` list
  `applyCallPermissions` already builds, `AppEnforcer.kt:640-645`), never ours. `allowed` = in the effective
  allowlist (`effectiveAllowlist(..., WindowRelock.blockedPackages)`) or a contacts' messaging app; every requester
  while apps are unmanaged (allowlist `null`), since the denial greys the Phone group for all of them.
- CALL_PHONE and ANSWER_PHONE_CALLS stay DENIED (`setCallPermissions` unchanged). Written only when the state
  differs. The override and the pause change nothing (call rules are never lifted - the documented exception).
- Why two writes on release: DEFAULT alone keeps the grant (the `shouldResetOwnPermissions` note); DENIED revokes
  and DEFAULT clears the policy fix, so the app asks again as on a fresh phone. A grant the kid gave before calls
  were managed is lost by that (question 2). We never set READ_PHONE_STATE DENIED otherwise, so a DENIED found later
  is our cut-short release.

**(e) Stuck in Recents after an unlock - not reproduced; analysis.** The symptoms (quickstep's RecentsActivity on top,
lock task LOCKED, `am start -c HOME` -> 101) say the HOME start's **target task wasn't allowlisted** (§1: 101 is
the task's root-activity package, not the HOME feature). Paths in our code that leave lock task in its LOCKED shape
after an unlock:
1. **No plan in this process** (`LockTaskChrome.applyFallback` `:227-262`): the UNLOCKED pass rebuilds the setting
   from the platform's state - the LOCKED features (no HOME/OVERVIEW/NOTIFICATIONS) and the list without the recents
   pin - and keeps them until `requestApply`'s `apply()` lands (`PinLockRuntime.kt` dispatch, `!hasPlan`). An apply
   that throws or waits behind a long sync leaves the phone like that.
2. **Features not verified** (`writeLockTask` `:295-301`): the pass refuses to pin "this cycle" - packages and
   features stay what the LOCKED pass wrote, until the next apply.
3. **Home's start refused at the leave** (`PinLockActivity.leave`/`go`, `HomeFront.bring`): the lock then finishes
   onto whatever lies under it - quickstep's latent RecentsActivity task. A start refused with 101 isn't seen by us.
Proposal:
- **Fix for 1**: `applyFallback` on an UNLOCKED pass with the kiosk on takes its features from the cached
  `LastEnforcedPlan.lockTaskFeatures` (`server/PolicyGate.kt:113-137`, through `lockTaskFeatures(..., blockActivityStart)`)
  when readable, not from the platform's LOCKED set.
- **Diagnostic line** on every UNLOCKED edge, after the chrome pass's listeners (`chromeListeners`) and again 1.5 s
  later: `Unlock check: lockTask=<NONE|LOCKED|PINNED> plan=<y|n> features=<int> oursPinned=<y|n> recentsPinned=<y|n>
  homeResumed=<y|n>` (DPM and `ActivityManager` reads off the main thread). On kiosk on with HOME missing from the
  features or our package not pinned: `Chrome still locked after the unlock` and one `LockTaskChrome.refresh` +
  `requestApply` (self-heal, at most once per unlock).
- **Emulator recipe** (kiosk on, PIN lock on, calls managed, WhatsApp freshly installed and allowlisted): unlock;
  open WhatsApp; let its first-run permission dialogs run (the permission controller inside lock task); tap the
  greyed Phone permission ("not allowed by admin") to get "App is not available"; press power; unlock. Keep
  `adb logcat -s PinLock PinLockActivity LockTaskChrome ActivityTaskManager` and, when stuck, `adb shell dumpsys
  activity activities | grep -iE "lockTask|mLockTaskModeTasks|Task\{"` plus `adb shell dumpsys activity lock-task`
  (the root task and the auth of each locked task). Repeat once with `am crash $P` between the dialogs and the
  power press (path 1: no plan in the new process).

## 6. Settings row, setup, restricted settings

- **PIN-gated Settings** (`ui/settings/launcher/SettingsFragmentLauncher.kt`, next to "Background connection"
  `:167-182`, `:265-275`): row "Samtaler over låsen". Pure `usageAccessRowState(granted, pageAvailable)` ->
  `ON | CAN_OPEN | PAUSE_FIRST | UNKNOWN`, like `batteryRowState` (`server/BatteryExemption.kt:33-38`). The tap opens
  `Settings.ACTION_USAGE_ACCESS_SETTINGS` with `package:<ours>` (the app's own page; the list if the build ignores
  the data), only when it resolves `MATCH_SYSTEM_ONLY` to an unsuspended system package that isn't ours - the same
  `dialogTarget` rule (`BatteryExemption.kt:25-26`), so on a managed phone during a pause. On return the state is
  read again (and `startWatchingMode` has already fired); a change requests a sync.
- **Setup runbook**: `docs/testing/emulator.md` §4, right after `cmd notification allow_listener`:
  `adb shell appops set $P GET_USAGE_STATS allow` (comment: design 57, VoIP over the lock needs it; Android may grey
  the toggle as a restricted setting, adb is not affected). The same line goes into the provisioning steps wherever
  the listener grant is documented (the PWA's fix text, §7.2), and into `docs/setup/` as a short
  `usage-access.md` ("Bruksdata for Vibb One": the adb line, or during a pause Settings › Apps › Special app access ›
  Usage access › Vibb One › On; if greyed: App info › ⋮ › Allow restricted settings, then the toggle).
- **Restricted settings**: from ECM's source (§1) the toggle is greyed for a guarded install; whether a QR-provisioned
  release, an `adb install` debug build and a self-updated build are guarded is device check 7.

## 7. Status contract, PWA, transition

### 7.1 Contract (one commit in `launcher/` and `server/`)

- Launcher: `LockStateReport.usageAccessGranted: Boolean? = null` (`server/dto/StatusReportRequest.kt:252-267`),
  serialised as `usage_access_granted`, left out when `null` (`ServerJson` drops nulls/defaults); filled from
  `UsageAccess.granted` in `PinLockRuntime.report`. Capability `voip_usage_v1` in the report's `capabilities`
  ("this launcher lets VoIP over the lock only with usage access"). `PolicyResponseCompatTest` ("status report
  lock_state uses the server's keys") gains the key and its absence for `null`.
- Server: `kid_lock::LockState.usage_access_granted: Option<bool>` (`#[serde(default, skip_serializing_if =
  "Option::is_none")]`, kept by `sanitize_lock_state` as is); `step10`'s lock-state round trip with `true`, `false`
  and missing.

### 7.2 The device-page warning

`LockNote::VoipNeedsUsageAccess` in `lock_card` (`server/src/kid_lock.rs:409-460`), pushed when the state is active
and `usage_access_granted == Some(false)` (never for `None`: an older launcher or an unreadable phone), after
`CallsCantRing`. An Attention row `voip_needs_usage_access` from the same note (`server/src/attention.rs` beside
`no_notification_access` `:586`), so the row and the Lock Screen & PINs page agree. Words (the PWA is English; nb for
the record and for a later nb PWA):

| | en (PWA) | nb |
|---|---|---|
| title | Calls in apps don't ring over the lock | Samtaler i apper ringer ikke over låsen |
| sentence | WhatsApp, Signal and Element calls ring only once the phone is unlocked, until Usage access is on for Vibb One. | WhatsApp-, Signal- og Element-samtaler ringer først når telefonen er låst opp, til Bruksdata er slått på for Vibb One. |
| ⓘ fix | During a pause, on the phone: Vibb One's Settings (Parent PIN) › Calls over the lock (or Settings › Apps › Special app access › Usage access › Vibb One). With USB debugging allowed: adb shell appops set me.vibb.launcher GET_USAGE_STATS allow (a debug build: me.vibb.launcher.debug). | Under en pause, på telefonen: Vibb Ones Innstillinger (foreldrekode) › Samtaler over låsen (eller Innstillinger › Apper › Spesiell apptilgang › Bruksdata › Vibb One). Med USB-feilsøking: adb shell appops set me.vibb.launcher GET_USAGE_STATS allow (debug-bygg: me.vibb.launcher.debug). |

The ⓘ text names Android's own path loosely ("Usage access"; the nb label is device check 8).

### 7.3 Kid-facing text

None. The kid can't grant it (Settings is suspended outside a pause) and the lock stays calm (launcher look). A ring
while LOCKED without access is the app's own notification on a locked phone - silent, since the lock's status-bar
flags mute notification sounds (design 17 QA #1). Question 1 asks whether the lock should still ring.

### 7.4 Transition (phones in the field)

- **Before updating** (recommended in the release note and PLAN): `adb shell appops set me.vibb.launcher
  GET_USAGE_STATS allow` on the old build - an app-op mode is kept per package across updates, so the new launcher
  starts with access and nothing changes for the kid. Whether the mode sticks for a package that doesn't yet declare
  `PACKAGE_USAGE_STATS` is device check 6; if it doesn't, grant right after the update.
- **After updating without it**: the first status report says `usage_access_granted: false`; the Attention row and
  the Lock Screen & PINs warning show at once. Until granted: VoIP rings only on an unlocked phone; `voip_rang`
  gets no new rows (nothing rang over the lock).
- No server pre-warning: the server can't know the grant before the new launcher reports it.

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
| `settings_mdm_calls_over_lock_unknown` | Ukjent | Unknown |

The call row's button gets `ButtonRole` like the lock's other buttons; the row's text is a polite live region.

## 9. Tests and mutation targets

- `CallScreenPlanTest` (new): every row of §2.2's table; learning by our send (FSI -> `ring`, answer/reopen ->
  `call`, a resume 3.1 s after the send learns nothing); the ring screen without our send; the hand-off within 2 s,
  confirmed by the IN_CALL notice and by the ring ending, dropped at 15 s, a second candidate never pending; `call =
  ring` at IN_CALL; `visible` (paused, not stopped) allows, stopped covers; the 1 s lag; a new record clears all.
  **Mutation targets**: the 15 s confirm (`<=` vs `<`), the 2 s hand-off, the stopped/paused distinction, row 8
  (no `call` known -> COVER, not ALLOW).
- `voipFacing` (in `VoipCallPlanTest`): the three rows of §2.1; mutation: unknown access must be NONE.
- `voipLockYields` (`VoipCallPlanTest`): the six rows of §2.3. `PinLockStateTest`: ProcessStart/ScreenOff/ScreenOn/
  RemoteLock with `voipYields` false during IN_CALL show the lock; LockResumed never sets a reopen (the field is
  gone); VoipRinging/VoipEnded precedence rows unchanged. `RefrontPolicyTest`: covered VoIP re-fronts, yielding VoIP
  yields, our call/system call/emergency/alarm still first.
- `lockStartFlags` (`PinLockManifestTest` or a new `LockStartTest`): contains `FLAG_ACTIVITY_NO_USER_ACTION` and the
  three old flags; a source scan that `PinLockRuntime.show` starts `PinLockActivity` with `lockStartFlags()` and that
  no other file starts `PinLockActivity`. **Mutation target**: dropping `NO_USER_ACTION` fails.
- `CallScreenGuardTest` (new, like `LockGlanceGuardTest`): `UsageStatsManager`, `UsageEvents`, `UsageEventsQuery`,
  `queryEvents` appear only in `lock/CallScreenWatch.kt`; that file has exactly its reviewed imports, no
  `getSharedPreferences`, file or stream writes, no `server.dto`/`MdmApi`/`SyncRunner`/crash-report reference; every
  `Log.` call there is one of the two decision lines; `getClassName()` results go only to `learnCallScreens`/
  `callScreenVerdict` and those lines; the poll is started only behind `voipWatchDue`. `UsageAccess.kt` reads only
  the app-op.
- `voipWatchDue` table: LOCKED x phase x granted x interactive x lockResumed; mutation: polling with the lock
  resumed or the screen off.
- (a) `VoipReopen` from a fake notice set: each case. (b) `RefrontPolicyTest`: `sosGestureLikely` false with
  `voipStartRecent`, true otherwise as before; `pauseRefrontDelayMs` passes it. (d) `phoneStateWrites` table
  (`EnforcementPlanTest`); targets: system apps and ours never, every requester with a `null` allowlist, a
  relock-blocked package not allowed; mutation: release as `[DEFAULT]` alone. (e) `fallbackFeatures` (the cached
  plan's features on an UNLOCKED kiosk-on pass, the platform's when the cache is unreadable) in `LockTaskHelpersTest`.
- `usageAccessRowState` table (`BatteryExemptionTest`'s sibling); the row opens only a `dialogTarget`.
- Contract: `PolicyResponseCompatTest` (the key, absent for null); server `kid_lock::usage_access_off_warns`
  (`Some(false)` warns, `None`/`Some(true)` don't, inactive lock doesn't), `step10` round trip,
  `attention::voip_needs_usage_access_row`, `PushManifestTest`-style manifest check for `PACKAGE_USAGE_STATS`.
- `VoipCallPlanTest`'s source scan that `MusicCalls`, `SelfUpdate`, `BootHome`, `WindowRelock`, `lockTaskEntry`'s
  caller and `callLiveNow` read `callPhase`/`liveCall`, and the lock-side readers `phase`.

## 10. Device and emulator checks

Emulator first (`docs/testing/emulator.md` §6, WhatsApp and Element X from a second account), then the Jelly Star.
1. **Step 1, before any code** (decides the constants and that the flag does what AOSP says):
   - `adb shell dumpsys usagestats` around a ring-answer-hang-up of each app (or `adb logcat -b events | grep
     wm_resume_activity`): the ring screen's and the call screen's classes, whether WhatsApp's ring and call screens
     are one activity, the time from Element's `ElementCallActivity` resume to its call foreground notification
     (sets `VOIP_ANSWER_CONFIRM_MS`; 15 s unless the measure says more).
   - Kiosk off, no lock task, an Element call on screen: `adb shell am start --activity-no-user-action -a
     android.intent.action.MAIN -c android.intent.category.HOME` -> Element does **not** enter PiP; the same without
     the option -> it does. Then, with the call in PiP, put the lock up and let it start lock task (or swipe the PiP
     away): the call hangs up - the path the flag avoids.
2. With access, LOCKED, screen off, both kiosk modes: a WhatsApp ring shows its ring screen (17b), answering keeps
   the call screen over the lock; its arrow to the chats brings the lock back within ~1 s with "Tilbake til
   samtalen"; the button returns to the call; hang-up brings the plain lock. Logcat: `Call screen: learned`/`COVER`.
3. Same with Element X: answer on its own screen (the hand-off), audio both ways for a minute, Home and Recents on
   the call screen land on the lock, the call survives (no PiP hang-up), the button returns.
4. Without access: a ring while LOCKED - no wake, card, ringtone or app screen over the lock (the re-front covers a
   SystemUI-launched FSI); unlocked, the app rings as usual. Grant during the call: the lock stays, the button
   works. Revoke during a call over the lock: the lock comes back, the call goes on behind it.
5. The permission dialog over a call screen (first video call: camera) stays answerable (row 6); PiP the kid made
   with the kiosk off floats over the lock and expands to the call screen.
6. Transition: `appops set ... GET_USAGE_STATS allow` on the current release, then the update -> `adb shell appops get
   $P GET_USAGE_STATS` still `allow`, and the first report says `usage_access_granted: true`.
7. Restricted settings: on the emulator (adb-installed debug build) and the Jelly Star (QR release, then after one
   self-update): is the Usage access toggle greyed? If so, "Allow restricted settings" in App info enables it.
8. The Settings row opens Android's page for Vibb One during a pause and says "pause first" outside one; the nb label
   of Android's page (for the runbook and the ⓘ text).
9. (b) a ring that wakes the screen logs no "probably Emergency SOS"; five power presses during a ring still reach
   SOS. (c) Home and the navigation gesture on a kiosk-off VoIP call that began LOCKED: the `START` line names the
   blocked component; the lock is back within ~1 s. (d) WhatsApp gets READ_PHONE_STATE by policy while calls are
   managed (no Phone-group dialog), CALL_PHONE stays denied, calls unmanaged -> the permission is not granted and
   the app may ask again. (e) the recipe in §5e; the `Unlock check` line after every unlock.
10. Battery: a 10-minute WhatsApp call over the lock with the screen on - `adb shell dumpsys batterystats
    --charged $P` CPU time before and after, against the same call unlocked.

## 11. Steps

1. **Emulator measurements** (§10.1): the activity sequences, Element's confirm time, the `--activity-no-user-action`
   check. No code. The constants in §2.2 are set from it.
2. **Launcher, the rule**: `voipFacing` + the `VoipCalls` split and the read-site switch (§2.1), `lockStartFlags`
   (§2.4), `UsageAccess` + manifest (§3.1), `CallScreenPlan` + `CallScreenWatch` (§2.2, §3.2), `voipLockYields` in
   `step`/`RefrontPolicy`, the call row and button, the removal of the automatic reopen (§2.3, §3.3), the tests of
   §9. Fixes (a) and (b) ride along (they touch the same code).
3. **Contract, both directories in one commit**: `lock_state.usage_access_granted`, `voip_usage_v1`, the server's
   `LockState`, `LockNote::VoipNeedsUsageAccess`, the Attention row, the tests (§7). Server version bump.
4. **Launcher, the rest**: the Settings row (§6), (d) `phoneStateWrites` in `applyCallPermissions`, (e) the fallback
   features, the `Unlock check` line and self-heal, (c)'s diagnostic note in the emulator doc.
5. **Docs**: `docs/testing/emulator.md` §4 (the grant) and §6 (the checks), `docs/setup/usage-access.md`,
   launcher/CLAUDE.md - "VoIP calls over the PIN lock" (the gate, the watcher, the button) and "Kiosk escapes",
   whose usage-stats sentence becomes: "...a home/device-owner app has no clean, non-polling signal for another
   package's activity in front (usage stats need the PACKAGE_USAGE_STATS app-op, which a device owner can't grant;
   ruled out for the notification rule - since design 57 read only for a VoIP call over the lock, with the parent's
   grant, the call app's package only, memory only; `getRunningTasks` ...)". PLAN: the S-M4 entry and the small
   fixes marked built, the transition note for the next launcher release.
6. Code QA, fix all findings, the emulator checks (§10), then the Jelly Star.

## 12. Open questions for the user

1. **Without usage access, should the locked phone still ring** (our ringtone and vibration, no card, no wake, no app
   screen) so the kid knows to unlock and answer? Proposal: no - "like calls off", and the PWA warns the parent.
2. **(d) on release**: when calls stop being managed (or an app leaves the allowlist), READ_PHONE_STATE is revoked and
   un-fixed, so the app asks again - also an app the kid had granted it before calls were managed. Proposal: accept
   (no record of earlier grants is kept).
3. **The hand-off window** (§2.2 step 3): after the kid answers on the app's own ring screen, the next activity of
   that app is trusted for up to 15 s until the call is confirmed. A finder who rings the phone from WhatsApp and
   taps "reply" on its ring screen (if it has one) gets at most that long once per ring. Proposal: accept; the
   alternative is to trust only activities our own sends opened, so Element's own Answer would need a second tap on
   "Tilbake til samtalen".

## 13. Not verified here

- WhatsApp's activity classes and sequence (ring vs call screen, the arrow's target) - step 1.
- How soon a RESUMED/STOPPED event is visible to `queryEvents` after the transition (the 1 s lag allowance).
- Which install paths ECM guards on the target phones (device check 7); whether an app-op mode set before the
  permission is declared survives the update (check 6).
- That `ACTION_USAGE_ACCESS_SETTINGS` with a `package:` URI opens the app's own page on the target builds.
- What the Home press in (c) started (quickstep vs the call app) and the cause of (e) - both need the logs the
  diagnostics add.
- Element's `canEnterPip()` answer during a call (a `false` hangs up on any leave hint; our flag avoids the hint).
