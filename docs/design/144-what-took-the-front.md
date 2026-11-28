# 144 - What took the glance's front: a front classifier from usage events (certain Emergency SOS first)

Status: design rev 2, 2026-11-28 (PLAN 1p), branch `front-design`. Docs only; no code, not gated. Rev 1 (ad2a6d94f)
had a design QA of 2H 4M 7L; all of it is taken (§10). The owner's answers so far are in §9. The owner (2026-11-28):
"Sikker sos gjenkjenning ... bør undersøke ... kan vi vel også kjenne igjen hver gang en feil skjerm kommer foran
glance".

Marks: **[aosp14]** / **[aosp16]** read on 2026-11-28 in `android.googlesource.com`, branches `android14-release` and
`android16-qpr1-release` (`frameworks/base` unless named; file:line in that branch); **[sdk]**
`platforms/android-37.0/data/api-versions.xml` on this machine; **[gate]** `docs/testing/emulator.md` §6cl (main,
2026-11-28) or the section named; **[code]** this repo at 6e21bf988; **[assumed]** not checked, step 0 (§6) checks it.

## 0. Short answer

- **Detection is possible.** With usage access on, Vibb sees which screen took the glance's front, by package and
  class. Android writes every activity's RESUMED/PAUSED/STOPPED to its usage events, system ones included: the SOS
  app, `BlockedAppActivity` (package `android`), the emergency dialer, any app. The events are stored in the order they
  happen, usually within milliseconds, and `queryEvents` returns them from memory at once.
- **The one caveat:** an SOS that starts right after another cover (say a `BlockedAppActivity` from a plug-in) is seen
  a few ms late, and rarely more: the worst Android delay seen is 17 s, on 14 under load. In that gap the newest event
  still names the cover. So in the 3 s after a screen-on with no cable change, the glance keeps yielding as today and
  re-fronts only after the window, on a fresh read with no SOS event (§4.1). That is the cost in Q6.
- **The classifier is general, not only for SOS** (the owner's Q1 question): every cover of the glance gets a class
  and its own action (§4.1). SOS and the emergency dialer keep the front until they leave. `BlockedAppActivity` and a
  bare keyguard get the glance back. Our own lock screens are left alone. Any other app keeps today's rules. It can't
  see what isn't an activity: SystemUI's keyguard, bouncer and shade, dialog windows (Unihertz's `ChargingDialog`,
  probably), crash dialogs and overlays. A window over the glance leaves it resumed and the re-front can't cover it
  anyway. SystemUI's keyguard shows as `KEYGUARD`: a cover left and no activity resumed after it.
- **A covered SOS:** AOSP's SOS app (`com.android.emergency`, 14 and 16) moves its countdown to a foreground service
  and still calls (7.5 s on 14 while covered). Google's SOS app on the 16 image (`safetyhub`) shows a touch-and-hold
  screen, which is hidden and closes itself when covered, **so SOS can't be reached** [gate]. Unihertz's SOS app: J3.
- **The guarantee:** usage events add yields anywhere. They remove a timing yield only where today's rules already
  remove it (a screen-on tagged as a power change's wake), or after the SOS window, on a fresh read with no SOS event.
  So the SOS guarantee only gets stronger. Where the access is on, this closes the open hole (a press-SOS from the dark
  with a cable change near press 1) and 1o-f's wrong yield.
- Without the access, nothing changes (Q5: no tightening).
- No other signal is both reliable and allowed (§3). Size: launcher only, about 350 lines plus tests; Q1 adds a small
  server step.

## 1. Today

The glance is Android's face (design 132). When it leaves the front while Android is locked, `androidLossStep`
(`launcher/app/src/main/java/com/kidslauncher/mdm/lock/RefrontPolicy.kt:272-280`) decides what happens. It runs these
in order: the screen off, Android open or waiting, the named exemptions (`exemptYield`, `:241-256`: a call, a system
call, "Emergency call", an alarm, VoIP, and a probable SOS last), ours, the cap, and then a re-front. Nothing names the
screen in front, so a probable SOS is a guess from timing. The comment at `:589-605` says "No app can see which
activity is in front (usage events need a permission a device owner can't grant itself), so the timing tells". That
is out of date since design 139: the parent grants the access on the phone (`timerules/UsageAccess.kt:18-36`).
- A pause or stop of the glance within `SOS_GESTURE_WINDOW_MS` (3 s) of a screen-on marks a probable SOS
  (`sosGestureLikely`, `:649-650`; `sosMarkAtPause`, `:629-630`; `PinLockRuntime.noteProbableSos`,
  `PinLockRuntime.kt:1720-1729`), and the glance yields `"sos"` for 15 s (`SOS_FLOW_MS`, `:610`).
- (4i) A power change's own wake is exempt from that rule (`powerWokeScreen`, `:728-732`; the screen-on's tag
  `powerWakeTag`, `:751-752`; `sosScreenOn`, `:756`). A loss in the change's window gets a re-front budget
  (`FaceRefronts`, `:101-155`). The change looks again 1 s later (`powerLook`, `PinLockRuntime.kt:1588-1603`).
- Anything else gets a re-front, up to `ANDROID_REFRONT_CAP` (2) per screen-on. The stop's check runs 200 ms after the
  stop (`PinLockRuntime.kt:1685`).

Known holes, with the gate's data:
- **A press-SOS from the dark with a cable change near press 1** (the open M in `lock-and-glance.md`, "Not told
  apart"). The change tags press 1's screen-on, the SOS start reads as the change's loss, and the glance re-fronted
  0.20-0.22 s after the SOS start on 14 and 16 [gate]. On 16 that leaves SOS out of reach; on 14 the call still came.
- **1o-f:** when the stamp arrives after the pause (2 of 6 tries), the pause sets the SOS mark first and the look
  clears it about 0.7 s later. The end state is right, but Android's lock shows in between [gate].
- **The guess the other way:** any untagged loss just after a screen-on reads as SOS - an alarm app that isn't the
  system clock, a full-screen intent, or `BlockedAppActivity` when no power stamp explains it. Android's lock then
  stays until the screen goes off.

## 2. Q1 - usage events: what and how fast

### 2.1 The path (AOSP)

1. WM sets an activity's state. `ActivityRecord.setState` reports RESUMED, PAUSED and STOPPED at the server-side state
   change [aosp14 `services/core/java/com/android/server/wm/ActivityRecord.java:5788-5822`; aosp16 `:5661-5698`]. On
   16 a RESUMED-to-STOPPING step also reports a PAUSED (`:5679-5685`). RESUMED is set when WM resumes the activity,
   which is before that app's own `onResume` runs. The glance's PAUSED is reported after our `onPause` returns.
2. `ActivityTaskManagerService.updateActivityUsageStats` posts this to ATMS's handler `mH` [aosp14
   `ActivityTaskManagerService.java:4985-5001`; aosp16 `:5402-5417`].
3. On that handler, `UsageStatsService.LocalService.reportEvent` builds the event with the stamp
   `SystemClock.elapsedRealtime()`, the package, the class and the task root [aosp14
   `services/usage/java/com/android/server/usage/UsageStatsService.java:2936-2947`; aosp16 `:3238`].
   `reportEventOrAddToQueue` then posts `MSG_REPORT_EVENT` to the event handler [aosp14 `:965-968`; aosp16
   `:1074-1076`]. That handler runs on `BackgroundThread` on 14 (`:300`). On 16 it runs on a dedicated
   `UsageStatsHandlerThread` when the `useDedicatedHandlerThread` flag is on, else `BackgroundThread` [aosp16 `:157-159`,
   `:367`, `:511-515`]. Which one a given build uses is the build's flag [assumed]; P6 measures both images.
4. There `UserUsageStatsService.reportEvent` adds the event to the in-memory daily stats. `queryEvents` reads them
   (`mCurrentStats`) under `UsageStatsService.mLock` [aosp14 `UsageStatsService.java:1406`;
   `UserUsageStatsService.java:460-480`, `:538-560`]. It reads from disk only for the part of the window before the
   in-memory day began (`UserUsageStatsService.java:484-489`), which is rare for a read of the last seconds but takes
   hundreds of ms. The disk flush every 20 min (`UsageStatsService.java:152-155`) matters only across a reboot.
5. **`UsageStatsManager.queryEvents` never returns `null`.** A `null` from the service (no access, or storage still
   locked: [aosp14 `UsageStatsService.java:1406-1412`; aosp16 `:1581`]) or a `RemoteException` becomes an empty
   `UsageEvents` [aosp14 `core/java/android/app/usage/UsageStatsManager.java:570-581`; aosp16 `:592-603`]. So an
   empty read never means "nothing in front". The access is read before and after the query on the IO thread, as
   `AppGroupUsage.kt:110-117` does. With the access on, an empty read is `UNSETTLED`. The case before the first unlock
   doesn't arise: `PinLockActivity` isn't `directBootAware` (`launcher/app/src/main/AndroidManifest.xml:214`).

**Order is kept.** Both hops are single handlers, so events are stored in the order they were reported. A read that
holds an event therefore holds every event reported before it. That is the fence §4.2 uses. It vouches only for what
was reported before the newest event in the read. An activity change after that (an SOS starting just after a
`BlockedAppActivity`) is invisible until it is stored.

**Latency** is the time those two hops take. The stamp is taken after hop 1, so hop 1's wait is not visible in it. On
14 design 139 saw up to 17 s at a "Pause all restrictions" start (139 §18, row 18), when hundreds of packages are
unsuspended. The latency at a screen-on with a plug-in isn't measured yet (P1-P3, P6).

### 2.2 What appears

| Screen | Activity event? | Package / class |
|---|---|---|
| Google's SOS (the 16 image) | yes [gate: `EmergencySosActivity`] | `com.google.android.apps.safetyhub` / `...EmergencySosActivity` [assumed: the full class name] |
| AOSP's SOS (14; also installed on the 16 image) | yes | `com.android.emergency`; the alias `.action.EmergencyAction` targets `.action.EmergencyActionActivity` (`showWhenLocked`, `noHistory`) [aosp14 `packages/apps/EmergencyInfo` `EmergencyGestureAction/AndroidManifest.xml:36-59`]. Which of the two names the event carries: [assumed] |
| Unihertz's SOS | yes, if it is an activity [assumed] | the handler of `com.android.systemui.action.LAUNCH_EMERGENCY` (J1) |
| `BlockedAppActivity` | yes | `android` / `com.android.internal.app.BlockedAppActivity`. Lock task swaps a refused start for it [aosp16 `ActivityStartInterceptor.java:392-402`]. It is a floating dialog (`Theme.Dialog.Confirmation` [aosp16 `core/res/AndroidManifest.xml:9423-9427`]), so whatever is under it stays visible (paused, not stopped). The refused package is in the intent only, not in the event |
| Emergency dialer (the bouncer's Emergency button) | yes [assumed] | the system handlers of `com.android.phone.EmergencyDialer.DIAL`, resolved like `emergencySosPackage` (P5) |
| A permission prompt and other dialog-themed activities | yes | their own package. They float, so the screen under them only pauses |
| A call screen | yes | the dialer's in-call activity (Telecom's `isInManagedCall` already covers it) |
| SystemUI's keyguard, bouncer and shade | no: SystemUI windows. `KEYGUARD_SHOWN`/`HIDDEN` are separate device events (139 §4.1) | - |
| Unihertz's `ChargingDialog` (`com.agui.settings`) | probably a window, so none [assumed] (J2). The 4i loss itself was `BlockedAppActivity` | - |
| Our glance | yes [gate §6ca row 2] | `me.vibb.launcher(.debug)` / `com.kidslauncher.mdm.lock.PinLockActivity` |

Package visibility is no filter here: the launcher holds `QUERY_ALL_PACKAGES` (`launcher/app/src/main/AndroidManifest.xml:11`).

## 3. Q2 - other signals (no permission, or what a Device Owner has)

| Signal | What it tells | Verdict on 14-17 |
|---|---|---|
| `ActivityManager.getRunningTasks` / `getAppTasks` | only our own tasks since API 21 (`getRunningTasks` deprecated 21 [sdk]); a Device Owner gets no more (`REAL_GET_TASKS` is privileged) | no |
| Lock task (`getLockTaskModeState`, `DevicePolicyManager.getLockTaskPackages`) | the mode, and our own list. The SOS app is on that list (`HelperKind.EMERGENCY_SOS`, `server/LockTaskHelpers.kt:55`), so it is never blocked. Any start of a package not on the list becomes `BlockedAppActivity` | the cause, but not the front |
| `onTopResumedActivityChanged(false)` (API 29 [sdk]) | the glance lost top-resumed, sometimes before `onPause`; nothing about what took it | an earlier loss signal only |
| `onWindowFocusChanged(false)` with no pause | a window (a dialog, a system window) took focus while the glance stayed resumed | tells "a window, not an activity"; a re-front can't cover a window anyway |
| `AccessibilityService` | every window's package and class, at once | ruled out (the owner; restricted settings from 13) |
| `NotificationListenerService` (ours: `badges/BadgeListenerService.kt`) | AOSP's SOS posts a foreground notification (channel `EmergencyGesture`, `CATEGORY_ALARM`) only once its countdown runs in the background, i.e. when covered or stopped [aosp14 `EmergencyActionFragment.java:126-149`, `EmergencyActionForegroundService.java:96-101,160-190`]; Google's and Unihertz's [assumed] | a late sign of a covered SOS on AOSP, no more |
| The emergency gesture's own broadcast | none for apps: `GestureLauncherService` calls `StatusBarManagerInternal.onEmergencyActionLaunchGestureDetected` and SystemUI starts the activity [aosp16 `GestureLauncherService.java:763-764,910-931`] | no |
| `Settings.Secure` `emergency_gesture_enabled`, `emergency_gesture_ui_showing`, `emergency_gesture_ui_last_started_millis` | the gesture on or off, and the SOS UI showing | `@hide` without `@Readable` [aosp16 `core/java/android/provider/Settings.java:11532-11559`]; from S+ a non-system app's read throws (`:3708-3718`, `:3887-3902`). A `ContentObserver` on the URI might still fire [assumed] (P7). `adb shell settings get` reads them |
| Telecom | `isInManagedCall` / `isInCall` (public, ours); `isInEmergencyCall` isn't public [sdk] | a placed call: as today (`system_call`) |
| Security logs (Device Owner) | process starts, in batches | not real time |
| `UsageStatsManager.queryEvents` with the access | §2 | **the one** |

## 4. Q3 - the design

### 4.1 The classes and their actions

Pure `lock/FrontClass.kt`: `frontOf(events, ownScreens, sosPackages, dialerPackages, notSos) -> Front`, where:
- `sosPackages` is every system handler of `ACTION_LAUNCH_EMERGENCY`, the cached `sosPackage` among them
  (`server/AppEnforcer.kt:177-190`; the 16 image has two).
- `dialerPackages` is every system handler of `com.android.phone.EmergencyDialer.DIAL`.
- `notSos` is `BlockedAppActivity` plus any power screen J2 proves to be an activity (e.g. `com.agui.settings`, M4).
- `ownScreens` is only our `showWhenLocked` screens (`PinLockActivity`, the call screens, `LockActivity`), not every
  activity of our package.

The rules apply as the face with Android locked and the screen on, after the Open/Wait rows. Calls, the system call,
the emergency flow, the alarm and VoIP keep their place in front of these rows.
- **The SOS window** = 3 s from the raw `lastScreenOnElapsed`, not `sosScreenOn` (which is `null` for a tagged
  screen-on, M3b).
- **Tagged** = the screen-on's power-wake tag (`screenOnPowerWake`), set at the screen-on or later by a stamp, as the
  look already handles.

| `Front` | SOS window, tagged | SOS window, untagged | Outside the window |
|---|---|---|---|
| `SOS` - the newest RESUMED after the loss is in `sosPackages` | yield `"sos"` until it leaves (below) | the same | the same |
| `EMERGENCY_DIALER` | yield `"emergency"` until it leaves | the same | the same |
| `BLOCKED` - in `notSos` | re-front at once, within the power budget or the screen-on's cap (as today's tagged path) | **keep today's `"sos"` yield** until the window ends + 250 ms; then a fresh, settled read with no event of an `sosPackages` or unknown package since the loss -> re-front (a fresh loss, the screen-on's cap); otherwise the row its front now has | as today (re-front up to the cap) |
| `KEYGUARD` - the cover's PAUSED/STOPPED and no RESUMED after it | as `BLOCKED` | as `BLOCKED` | as today |
| `OURS` - one of `ownScreens` | nothing (as `androidLossOurs`) | the same | the same |
| `OTHER` - any other package | **yield `"sos"`** (M4: an SOS outside `sosPackages`, an OEM variant, a trampoline) | today's yield (`"sos"`, 15 s) | today's (re-front up to the cap) |
| `UNSETTLED` (§4.2) | yield `"sos"`, recheck every 250 ms; once settled, its row; at 15 s it ends as today | the same | today's |
| `NO_ACCESS` (not granted before or after the read, or the read threw) | today's rules | today's | today's |

**The guarantee, restated (H1):** events add yields anywhere: `SOS` and `EMERGENCY_DIALER` in any window, and `OTHER`
and `UNSETTLED` in a tagged window. They remove a timing yield only where today's rules already remove it (a tagged
screen-on: `BLOCKED`/`KEYGUARD` re-front as today), or after the window on a fresh read that shows no SOS or unknown
event. That read holds every event reported up to it, and by then an SOS from the gesture has long been reported (the
fifth press comes about 2 s after the screen-on, `RefrontPolicy.kt:592-595`). The cost: an untagged `BLOCKED` or
`KEYGUARD` cover in the window shows Android's lock until the window ends + 250 ms, up to about 3.25 s (today it
stays until the screen goes off; Q6).

**Yielding until the SOS leaves, and after it** (H2; the owner's "yes" to Q2 in this narrowed form):
- "Leaves" is the SOS package's STOPPED (or, for a read whose window no longer reaches its RESUMED, no event of it at
  all). A PAUSED is not leaving: `BlockedAppActivity` and permission prompts float, and the SOS screen stays visible
  under them.
- After a certain SOS has left, the glance re-fronts once (spent from the screen-on's cap) only when:
  - the front is a settled `KEYGUARD`, and
  - Telecom has shown no call for 2 s.
- `BLOCKED`, `OTHER` or `UNSETTLED` after an SOS keep today's stop (Android's lock until the screen goes off), as does
  a call (`system_call`, then today's).
- There is no 15 s cap while the SOS is in front; the screen-off ends the yield.

### 4.2 Reading, anchoring, settling

- **Anchor (M3a):** the loss is found by event order, not wall time. It is the last PAUSED of `PinLockActivity` with no
  RESUMED of it after (on 16 also the PAUSED reported at STOPPING, aosp16 `ActivityRecord.java:5679-5685`); on 14 our
  STOPPED counts too when no PAUSED is read. Wall time only bounds the query (`now - 30 s` to `now + 1 s`). Events are
  used in Android's order and never re-sorted (139 QA L5).
- **Settled:** the read holds the anchor and, after it, the cover's RESUMED, or the cover's PAUSED/STOPPED (`KEYGUARD`).
  A stop's check also needs the glance's STOPPED, so by the fence every activity change up to our stop is in the read.
  A cover resumed while we were still pausing (resume-while-pausing: its RESUMED before our PAUSED) never settles; it
  stays `UNSETTLED`, which is safe (a test row). An empty read with the access on is `UNSETTLED` (M1).
- **The read (M2):** never a blocking get on main (`queryEvents` takes `mLock` and may read from disk).
  - A read starts on its own IO thread at the glance's pause or stop callback, with the access read before and after it
    on that thread.
  - One read is in flight at a time; the 250 ms rechecks coalesce into it.
  - The check, at today's times (the stop's 200 ms, the pause's delay, the power look), uses the newest result. When
    there is none, it acts on `UNSETTLED`, and a result that comes later posts its own check.
  - `UNSETTLED` rechecks keep `androidLossPending` (M3c): they are the fresh loss's checks, so a re-front is allowed
    once the read settles.
- **Left open:** an SOS that starts in the few ms between a settled read and a re-front in a tagged window, or after
  the window, is covered before it is seen - as today at every re-front. The next read finds its RESUMED and logs
  `AndroidLock: front: SOS covered`. A covered SOS can't be brought back: AOSP's entry alias needs
  `MANAGE_SENSOR_PRIVACY` (`EmergencyGestureAction/AndroidManifest.xml:39`).
- **Logs:** `AndroidLock: front: <class>`, with the package only for `SOS`, `EMERGENCY_DIALER` and `BLOCKED`, and
  `front: unsettled (<n> reads)`. Events are held in memory for one check, never stored or sent (139 D11). The report's
  exempt-yield count is unchanged.

### 4.3 What changes in code (for the build)

- `RefrontInputs` gains `front: Front` (default `NO_ACCESS`, so today's tests hold unchanged), `sosWindow` (raw) and
  `tagged`. `exemptYield` and `androidLossStep` read them as in §4.1. A new post-window step does the delayed re-front.
  With the access on, `powerRecheck`'s `clearSosMark` follows the classifier. `sosMarkKept`/`sosMarkLogDue` take
  `"sos"` from both sources.
- `PinLockRuntime`: the read executor started at `onLockPaused`/`onLockStopped`, the check fed with the newest
  `Front`, the coalesced 250 ms recheck, the yield until the SOS leaves, and the one re-front after it. The comment at
  `RefrontPolicy.kt:591-592` is corrected.
- Tests: `FrontClassTest` - each class from event lists recorded in step 0 on 14 and 16; the anchor on 14 and 16's
  PAUSED-at-STOPPING; resume-while-pausing never settles; an empty read; an SOS event after `BLOCKED`; a floating
  dialog over the SOS (still in front). Rows in `RefrontPolicyTest`/`PowerGlanceTest`:
  - the covered case with the access yields, without it it re-fronts as today;
  - an untagged `BLOCKED` re-fronts only after the window;
  - `OTHER` in a tagged window yields;
  - 1o-f with the access gives no SOS line.

## 5. Q4 - does a covered SOS still count down and call?

- **AOSP `com.android.emergency`, 14 and 16:** yes. If its screen is stopped before it is cancelled or done,
  `EmergencyActionFragment.onStop` starts `EmergencyActionForegroundService` with the time left ("continuing countdown
  in background"). That service puts up its notification and sets an exact alarm for the call broadcast [aosp14
  `EmergencyActionFragment.java:126-149`, `:186-194`; `EmergencyActionForegroundService.java:96-101`, `:194-199`].
  The 16 branch's fragment is the same (it differs by one import). The activity is `noHistory`, so a covered SOS
  screen is finished and only the service goes on counting. Gate: covered on 14, `Starting to call emergency number`
  came 7.5 s after the start [gate].
- **Google's `safetyhub` (the 16 image):** there is no countdown, only "Touch & hold for 3 seconds". Left alone it
  closes after 20 s with no call. Covered, the screen is hidden at once and its task is gone about 20 s later, so no
  call [gate]. On Google-SOS phones a covered SOS is a lost SOS. This is the case that needs certainty.
- **Unihertz (the Jelly Star, 16):** open. Row J3, with the owner's OK (Q4).
- **G4 - the gate agent's row:** a covered SOS's countdown and call, on 14 and 16. Done in §6cl with today's build
  (results above). With step 1's build and the access on, the same sequence must show `front: SOS (...)`, a `sos`
  yield with no re-front, and the SOS screen still reachable (on 16 the hold works and places the call; `system_call`
  follows).

## 6. Step 0 (emulator `compat34` = 14, `jelly36` = 16; then the Jelly)

Setup as §6cl: Device Owner, enrolled, Child PIN 2580, the face on, kiosk on, one emulator at a time, the owner's OK
first, outside the evening quiet hours. Usage access on: `adb shell am start -a android.settings.USAGE_ACCESS_SETTINGS -d
package:me.vibb.launcher.debug` and the switch (`adb shell cmd appops get me.vibb.launcher.debug GET_USAGE_STATS`
should then show `allow`).

The probe is a throwaway local branch `probe-144` (not pushed, as design 139's `probe-139`). It adds a debug-only
receiver `FrontProbeReceiver`, which for N s polls `queryEvents(now - 10 s, now + 1 s)` every 20 ms on an IO thread
and logs each new activity, screen or keyguard event once: `FrontProbe: <type> <pkg>/<class> stamp=<ms> seen=+<ms>`.
It also logs the glance's own `onPause`/`onStop` elapsed time (so first-seen can be read against our callbacks), the
access read, a `ContentObserver` on `content://settings/secure/emergency_gesture_ui_showing`, and the result or
exception of reading that key.

```
adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.debug.FrontProbeReceiver --ei seconds 15
adb logcat -v monotonic -s FrontProbe AndroidLock PinLock GestureLauncherService
# the SOS gesture: five presses from separate calls, 0.15 s apart (§6cl)
for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_POWER; sleep 0.15; done
# the blocked start: 16 `am start -a android.intent.action.SET_WALLPAPER`, 14 `am start -n com.android.wallpaper.livepicker/.LiveWallpaperActivity`
```

| Row | Steps (both images unless named) | Read |
|---|---|---|
| P1 | SOS from a lit glance, 5 tries | the SOS package and class; its RESUMED's first-seen against `GestureLauncherService: Emergency gesture detected, launching.` and the glance's `onPause`/`onStop`; its order against our PAUSED/STOPPED |
| P2 | the covered case: `dumpsys battery unplug`, `KEYCODE_SLEEP`, 4.5 s, `dumpsys battery set usb 1`, the five presses | is the SOS RESUMED stored before the stop's 200 ms check? the worst first-seen |
| P2b | plug-in, the blocked start, then a press-SOS from the dark 0.1, 0.3, 0.6 and 1 s after `BlockedAppActivity`'s RESUMED | how late the SOS's RESUMED is seen after the cover's (the H1 gap) |
| P2c | no power change: `KEYCODE_WAKEUP`, the blocked start, then the five presses | the untagged order: `BLOCKED`, then SOS |
| P3 | the blocked start, with and without a plug-in, 5 tries | `android`/`BlockedAppActivity`, its first-seen, then its PAUSED/STOPPED with no RESUMED after (`KEYGUARD`) |
| P4 | swipe up to Android's pad (`input swipe 240 700 240 200`), then back | expected: only our own events |
| P5 | the pad's Emergency button, then back; `adb shell cmd package query-activities --components -a com.android.phone.EmergencyDialer.DIAL` | the dialer's package and class |
| P6 | P3 during the first 20 s of "Pause all restrictions" (`/start-pause`) | the worst first-seen under load (139's 17 s), on 14 and on 16 (the dedicated thread or not) |
| P7 | `adb shell settings get secure emergency_gesture_ui_showing` and `emergency_gesture_ui_last_started_millis`, before, during and after P1; the probe's observer and read | does the key move; does an observer fire; does the read throw |
| P8 | during P2 on 14: `adb shell dumpsys notification --noredact \| grep -B2 -A8 EmergencyGesture`; the same on 16 for `safetyhub` | the covered countdown's notification |
| P10 | the SOS's leave paths: 16 left to close itself (20 s), 14 cancelled (the slider), an SOS with a floating dialog over it (a blocked start during the SOS: `BlockedAppActivity` over it), a call placed from it; `dumpsys telecom` alongside | which events mark leaving (STOPPED vs PAUSED); Telecom's state through it |
| G4 | §5 | §5 |

(Rev 1's P9, the read before the first unlock, is dropped: the glance doesn't run then, §2.1.)

**The Jelly** (release build, no probe: never a debug APK on the kid's phone). adb over Wi-Fi so the USB cable stays
free for the plug rows (`adb tcpip 5555`, `adb connect <ip>:5555`; "Block USB debugging" off on the Advanced page, §4):

| Row | Command | Read |
|---|---|---|
| J1 | `adb shell cmd package query-activities --components -a com.android.systemui.action.LAUNCH_EMERGENCY`; the same for `com.android.phone.EmergencyDialer.DIAL`; `adb shell settings get secure emergency_gesture_enabled` | Unihertz's SOS and dialer packages; whether the gesture is on |
| J2 | plug in and unplug with the glance in front, then `adb shell dumpsys usagestats -c android com.agui.settings me.vibb.launcher \| grep -E "ACTIVITY_(RESUMED\|PAUSED\|STOPPED)" \| tail -30` and `adb shell dumpsys window \| grep -E "mCurrentFocus\|ChargingDialog"` | `BlockedAppActivity`; is `ChargingDialog` an activity (then it joins `notSos`) or a window |
| J3 | the owner's "yes" (Q4): only if Unihertz's SOS shows a cancel or a hold before any call, with the owner holding the phone. The gesture, cancel at once, then `adb shell dumpsys usagestats -c <J1's package>` and `adb shell dumpsys activity activities \| grep -B2 -A6 <J1's package>` | its class and task; countdown or hold |

## 7. Android 14-17

| | 14 | 15 | 16 | 17 |
|---|---|---|---|---|
| `queryEvents(long,long)`, RESUMED/PAUSED/STOPPED, `getClassName` | API 21/29 [sdk] | same | same | same [sdk 37] |
| The reporting path (§2.1) | read [aosp14]; hop 2 on `BackgroundThread` | not read; between 14 and 16 [assumed] | read [aosp16]; hop 2 on `UsageStatsHandlerThread` or `BackgroundThread` by flag; PAUSED also at STOPPING | not read [assumed] |
| `queryEvents` returns an empty set, never `null` | `UsageStatsManager.java:570-581` | [assumed] | `:592-603` | [assumed] |
| The usage switch | not restricted (§6ca) | restricted settings: "Allow restricted settings" first (`setup.how.usage_access`) | same | same |
| A covered AOSP SOS still calls | source + gate | [assumed] | AOSP source same; Google's `safetyhub`: lost (gate) | [assumed] |
| Hidden Secure keys unreadable to apps | yes (S+) | yes | yes [aosp16] | [assumed] |
| Gate rows | `compat34` | none | `jelly36` | none (no API 37 image runs here) |

Every API used is public at or under minSdk 34. The 35+ `UsageEventsQuery` (API 35 [sdk]) is not needed.

## 8. Cost

- Launcher: `lock/FrontClass.kt` (~150 lines, pure) + `FrontClassTest` (~200). Glue in `PinLockRuntime` (~110) and
  `RefrontPolicy` (~60), with test rows (~120). No contract, no policy JSON.
- Server, for Q1 only: the `step!` macro gives a step one fixed kind (`server/src/setup_status.rs:255-266`). So either
  two entries (Required with app groups, Optional otherwise) or a conditional kind, an nb/en (i) line, and a test (~40).
  An optional step that isn't done shows no warning line and no orange.
- Step 0 with the probe, about a day on two images. One gate on 14 and 16 after the build, then the Jelly rows.

## 9. Owner questions

Answered (2026-11-28):
- **Q2 - re-front once after a certain SOS closes into Android's lock:** yes, in H2's narrowed form (§4.1): only on
  the SOS's STOPPED, a settled `KEYGUARD`, and no call for 2 s.
- **Q4 - J3 on the Jelly:** yes, only with a cancel or hold before any call, the owner holding the phone.
- **Q5 - without the access, no tightening of `POWER_WAKE_BEFORE_MS`:** yes. Phones without the access keep the hole.

Open:
- **Q1 - nudge the parent towards usage access, now that it is a general front classifier.** The answer to the owner's
  question ("kan den brukes for andre apper også, så glance alltid vinner når den skal og ikke når den ikke skal?"):
  yes, as far as covers are activities (§0, §4.1):
  - SOS and the emergency dialer win over the glance, calls as today.
  - `BlockedAppActivity` and a bare keyguard lose to it (at once on a power wake, after the 3 s window otherwise).
  - Our own lock screens are left alone.
  - An unknown app keeps today's rules: in the window a yield, outside it the cap. It is not trusted more than today.
  - It can't see windows that aren't activities (SystemUI's shade and bouncer, dialog windows, crash dialogs,
    overlays). Those don't take the glance's front as an activity does; a re-front can't cover them either.

  Recommended: the `usage_access` step applies to every phone, as an **optional** step - required with app groups, as
  now. Off is the parent's choice: no orange line, no warning (CLAUDE.md "Words in the UI"). One line behind its (i):
  nb "Med denne på ser låseskjermen hva som kom foran den, og kjenner igjen Nød-SOS sikkert.", en "With it on, the lock
  screen sees what came in front of it and knows Emergency SOS for certain."
- **Q3 - in the SOS window, a read that hasn't settled yields (rechecks every 250 ms):** deferred by the owner.
  Recommended: yes. The cost is a short look at Android's lock when events are late (about 0.2 s normally, at worst as
  late as the events), inside today's 15 s yield.
- **Q6 (new, H1's cost) - an untagged `BlockedAppActivity` or bare keyguard in the SOS window:** the glance keeps
  today's `"sos"` yield until the window ends + 250 ms (Android's lock for up to about 3.25 s), then comes back on a
  fresh read with no SOS event. Today it doesn't come back until the screen goes off. Re-fronting at once would be
  quicker but could cover an SOS started in the gap the events haven't shown yet (a few ms, rarely more).
  Recommended: as designed - wait for the window.

## 10. Revision 2 (the design QA of rev 1, ad2a6d94f): 2H 4M 7L, all taken

| Finding | Where taken |
|---|---|
| H1 the fence vouches only for events before the glance's stop; an SOS after `BLOCKED`/`KEYGUARD` is invisible for the hop delay | §4.1: untagged `BLOCKED`/`KEYGUARD` keep the yield until the window ends + 250 ms and re-front on a fresh read with no SOS event; the guarantee restated; Q6 |
| H2 a PAUSED isn't leaving (floating dialogs over the SOS) | §4.1 "Yielding until the SOS leaves": STOPPED only; the one re-front needs a settled `KEYGUARD` and no call for 2 s; §2.2 `Theme.Dialog.Confirmation` |
| M1 `queryEvents` never returns `null` | §2.1 item 5; empty with the access on = `UNSETTLED`; P9 dropped (`PinLockActivity` not `directBootAware`) |
| M2 `queryEvents` holds `mLock`, may read disk | §4.2 "The read": started at the callback, one in flight, coalesced, never a blocking get; a late result posts its own check |
| M3 the anchor by event order; the raw SOS window; `androidLossPending` kept | §4.2 "Anchor"; §4.1 "The SOS window"; §4.2 "The read" |
| M4 `OTHER` in a tagged window yields; J2's power screens join `notSos` | §4.1 table, `notSos` |
| L 16's dedicated handler thread | §2.1 item 3, §7, P6 on both |
| L the emergency dialer from `EmergencyDialer.DIAL`'s handlers | §4.1 `dialerPackages`, P5, J1 |
| L `OURS` = our `showWhenLocked` screens only | §4.1 `ownScreens` |
| L resume-while-pausing never settles | §4.2 "Settled", §4.3 test row |
| L Q1's `step!` gives one fixed kind | §8 |
| L Android 17 [assumed] | §7 |
| L step-0 rows | P1/P2 first-seen against our callbacks and `GestureLauncherService`; P2b, P2c, P6 on both, P10; J3 adds `dumpsys activity activities` |

One correction to the QA's citation: `BlockedAppActivity`'s manifest entry is at `core/res/AndroidManifest.xml:9423-9427`
on `android16-qpr1-release` (the QA's ~7872 is another branch); the theme is the same.
