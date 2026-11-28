# 144 - What took the glance's front: certain Emergency SOS from usage events

Status: design rev 1, 2026-11-28 (PLAN 1p), branch `front-design`. Docs only; no code, not gated. The owner
(2026-11-28): "Sikker sos gjenkjenning ... bør undersøke ... kan vi vel også kjenne igjen hver gang en feil skjerm kommer
foran glance".

Marks: **[aosp14]** / **[aosp16]** read on 2026-11-28 in `android.googlesource.com`, branches `android14-release` and
`android16-qpr1-release` (`frameworks/base` unless named; file:line in that branch); **[sdk]**
`platforms/android-37.0/data/api-versions.xml` on this machine; **[gate]** `docs/testing/emulator.md` §6cl (main,
2026-11-28) or the section named; **[code]** this repo at 6e21bf988; **[assumed]** not checked, step 0 (§6) checks it.

## 0. Short answer

- **Yes, with usage access Vibb can see what took the front, by package and class.** Every activity's
  RESUMED/PAUSED/STOPPED goes into Android's usage events, system ones included: the SOS app, `BlockedAppActivity`
  (package `android`), the emergency dialer. SystemUI's keyguard and bouncer and a dialog window are not activities
  and send no activity event. The way there is two handler hops in one fixed order (§2.1). After that the event sits in
  memory and `queryEvents` returns it at once; nothing has to be flushed to disk. Usually that takes milliseconds. The
  worst seen is 17 s, on 14 under load (design 139 row 18). So the read can settle what is in front, but its lateness
  needs a rule.
- **A covered SOS:** AOSP's SOS app (`com.android.emergency`, 14 and 16) moves its countdown to a foreground service
  when its screen is stopped, and that service still places the call (the gate saw it call 7.5 s after the start while
  covered). Google's SOS app on the 16 image (`safetyhub`) shows a 3 s touch-and-hold screen. Covered, that screen is
  hidden and closes itself, **so SOS can't be reached** [gate]. What Unihertz's SOS app does is open (§6, J3).
- **Design:** a classifier fed by usage events when the access is on, and today's timing rules when it is off or the
  events say nothing (§4). The SOS app in front yields until it leaves. `BlockedAppActivity`, or nothing in front
  after it (Android's keyguard), gets a re-front within the budget. Our own screens get nothing. Any other app keeps
  today's rules. In the SOS window, a read that hasn't settled yet yields and looks again every 250 ms. The SOS
  guarantee becomes stronger, never weaker. This closes the open hole (a press-SOS from the dark with a cable change
  near press 1) and 1o-f's 0.7 s yield wherever the access is on.
- **No other signal is both reliable and allowed** (§3): the others name only our own tasks, are hidden settings, are
  batched, or need a system permission. An accessibility service would do it, but it is ruled out.
- Size: launcher only, about 300 lines plus tests. Optionally one server step for the nudge (Q1).

## 1. Today

The glance is Android's face (design 132). When it leaves the front while Android is locked, `androidLossStep`
(`launcher/app/src/main/java/com/kidslauncher/mdm/lock/RefrontPolicy.kt:272-280`) decides what happens. It runs these
in order: the screen off, Android open or waiting, the named exemptions (`exemptYield`, `:241-256`: a call, a system
call, "Emergency call", an alarm, VoIP, and a probable SOS last), ours, the cap, and then a re-front. Nothing names the
screen in front, so a probable SOS is a guess from timing (`:589-605`: "No app can see which activity is in front
(usage events need a permission a device owner can't grant itself), so the timing tells" - out of date since design
139: the parent grants the access on the phone, `timerules/UsageAccess.kt:18-36`):
- A pause or stop of the glance within `SOS_GESTURE_WINDOW_MS` (3 s) of a screen-on marks a probable SOS
  (`sosGestureLikely`, `:649-650`; `sosMarkAtPause`, `:629-630`; `PinLockRuntime.noteProbableSos`,
  `PinLockRuntime.kt:1720-1729`), and the glance yields `"sos"` for 15 s (`SOS_FLOW_MS`, `:610`).
- (4i) A power change's own wake is exempt from that rule (`powerWokeScreen`, `:728-732`; the screen-on's tag
  `powerWakeTag`, `:751-752`; `sosScreenOn`, `:756`). A loss in the change's window gets a re-front budget
  (`FaceRefronts`, `:101-155`). The change looks again 1 s later (`powerLook`, `PinLockRuntime.kt:1588-1603`).
- Anything else gets a re-front up to `ANDROID_REFRONT_CAP` (2) per screen-on. The stop's check runs 200 ms after the
  stop (`PinLockRuntime.kt:1685`).

Known holes, with the gate's data:
- **A press-SOS from the dark with a cable change near press 1** (the open M in `lock-and-glance.md`, "Not told
  apart"). The change tags press 1's screen-on, so the SOS start reads as the change's loss, and the glance re-fronted
  0.20-0.22 s after the SOS start on 14 and 16 [gate]. On 16 that leaves SOS out of reach. On 14 the call still came.
- **1o-f:** when the stamp arrives after the pause (2 of 6 tries), the pause sets the SOS mark first and the look
  clears it about 0.7 s later. The end state is right, but Android's lock shows in between [gate].
- **The guess the other way:** any loss just after a screen-on reads as SOS - an alarm app that isn't the system
  clock, a full-screen intent, or `BlockedAppActivity` when no power stamp explains it. Android's lock then stays
  until the screen goes off.

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
   `reportEventOrAddToQueue` then posts `MSG_REPORT_EVENT` to a handler on `BackgroundThread` [aosp14 `:300`,
   `:965-968`; aosp16 `:1074-1076`].
4. There `UserUsageStatsService.reportEvent` adds it to the in-memory daily stats. `queryEvents` reads those in-memory
   stats (`mCurrentStats`) [aosp14 `UserUsageStatsService.java:460-480`, `:538-560`]. The disk flush every 20 min
   (`UsageStatsService.java:152-155`) matters only across a reboot.
5. A user whose storage is still locked (after a reboot, before the first unlock) gets `null` from `queryEvents`
   [aosp14 `:1406-1412`; aosp16 `:1581`]. Its events wait in `mReportedEvents` until the unlock (`:969-983`).

**Order is kept.** Both hops are single handlers, so events are stored in the order they were reported. A read that
holds an event therefore holds every event reported before it. The classifier uses this as a fence (§4.2).
**Latency** is the time those two handlers take. The stamp is taken after hop 1, so hop 1's wait is not visible in it.
`BackgroundThread` is shared by many system services. On 14 design 139 saw up to 17 s at a "Pause all restrictions"
start (139 §18, row 18), when hundreds of packages are unsuspended. The latency at a screen-on with a plug-in is not
measured yet: that is step 0's P1-P3 (§6). Without the access `queryEvents` returns nothing and throws nothing (139
D6), so the access is read before and after each query, as `AppGroupUsage.kt:110-117` does.

### 2.2 What appears

| Screen | Activity event? | Package / class |
|---|---|---|
| Google's SOS (the 16 image) | yes [gate: `EmergencySosActivity`] | `com.google.android.apps.safetyhub` / `...EmergencySosActivity` [assumed: the full class name] |
| AOSP's SOS (14; also installed on the 16 image) | yes | `com.android.emergency`; the alias `.action.EmergencyAction` targets `.action.EmergencyActionActivity` (`showWhenLocked`, `noHistory`) [aosp14 `packages/apps/EmergencyInfo` `EmergencyGestureAction/AndroidManifest.xml:36-59`]. Which of the two names the event carries: [assumed] |
| Unihertz's SOS | yes, if it is an activity [assumed] | the handler of `com.android.systemui.action.LAUNCH_EMERGENCY` (J1) |
| `BlockedAppActivity` | yes | `android` / `com.android.internal.app.BlockedAppActivity`: lock task swaps a refused start for it in a task of its own [aosp16 `ActivityStartInterceptor.java:392-402`]. Which package was refused is in the intent only, not in the event |
| Emergency dialer (the bouncer's Emergency button) | yes [assumed] | `com.android.phone` / `...EmergencyDialer` (P5) |
| A call screen | yes | the dialer's in-call activity (Telecom's `isInManagedCall` already covers it) |
| SystemUI's keyguard and bouncer | no: windows of SystemUI. `KEYGUARD_SHOWN`/`HIDDEN` are separate device events (139 §4.1) | - |
| Unihertz's `ChargingDialog` (`com.agui.settings`) | probably a window, so none [assumed] (J2). The 4i loss itself was `BlockedAppActivity` | - |
| Our glance | yes [gate §6ca row 2: the glance's RESUMED/PAUSED/STOPPED] | `me.vibb.launcher(.debug)` / `com.kidslauncher.mdm.lock.PinLockActivity` |

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

Pure `lock/FrontClass.kt`: `frontOf(events, lossAtWall, ownPackage, sosPackages) -> Front`. Here `sosPackages` is
every system handler of `ACTION_LAUNCH_EMERGENCY`, the cached `sosPackage` among them (`server/AppEnforcer.kt:177-190`;
the 16 image has two). The rules below apply as the face with Android locked and the screen on, after the
Open/Wait rows. Calls, the system call, the emergency flow, the alarm and VoIP keep their place in front of these rows.

| `Front` | In the SOS window (3 s from the screen-on) | Outside it |
|---|---|---|
| `SOS` - the newest RESUMED after the loss is an SOS package, with no PAUSED or STOPPED of it after | yield `"sos"` until it leaves, with no 15 s cap (the screen-off ends it); the power tag can't cancel it | the same |
| `EMERGENCY_DIALER` - `com.android.phone` | yield `"emergency"` until it leaves | the same |
| `BLOCKED` - `android` / `com.android.internal.app.BlockedAppActivity` | the timing mark is dropped for this loss; then the face's rule: re-front within the power budget or the screen-on's cap | as today |
| `KEYGUARD` - the cover's PAUSED or STOPPED with no RESUMED after it (SystemUI's keyguard in front) | as `BLOCKED` | as today |
| `OURS` - our package | nothing (as `androidLossOurs`) | the same |
| `OTHER` - any other package | today's timing (a probable SOS yields 15 s) | today's |
| `UNSETTLED` - §4.2 | provisional yield `"sos"`: a fresh read every 250 ms; once settled, the row above applies; at 15 s it ends as today | today's |
| `NO_ACCESS` - not granted before or after the query, the read failed, or `null` (storage still locked) | today's rules, unchanged | today's |

**The SOS guarantee:** a loss is re-fronted in the SOS window only on a settled read whose front is certainly not SOS
(`BLOCKED`, `KEYGUARD`, `OURS`). A package we don't know, or a read that isn't settled, yields at least as today. A
`BLOCKED` or `KEYGUARD` front is treated as certain only when the read holds no event of an SOS package after the loss.
With the access on, the power tag (`sosScreenOn`) no longer decides the SOS question. The power budget and the cap stay
as the bound on re-fronts.

**After a certain SOS leaves** (its PAUSED or STOPPED, with no new RESUMED of it within 1 s): the next front is
classified. `KEYGUARD` gets one re-front, spent from the screen-on's cap, so the glance is back over Android's lock
(today the face stops after a yield, so Android's lock stays until the screen goes off). A call goes to `system_call`.
Owner question Q2.

`OTHER` keeps today's guess on purpose. The SOS flow may go through packages we don't know (Unihertz, a Google sharing
screen), so only screens known not to be SOS lift the guess. Step 0 on the Jelly may add to that list (e.g.
`com.agui.settings`, if `ChargingDialog` turns out to be an activity).

### 4.2 Settled, and the timing

- **The read:** one `queryEvents(lossWall - 5 s, now + 1 s)` on its own IO thread (a binder call, never on main), with
  the access read before and after it. Wall time is our elapsed time converted at the read, with 1 s of margin on each
  side. Events are used in Android's order and never re-sorted (139 QA L5).
- **Settled** = the read holds the glance's own PAUSED for this loss (our package, `PinLockActivity`) and, after it,
  a RESUMED of the cover or the cover's leave. For a stop's check, it must also hold the glance's STOPPED: the cover's
  RESUMED is reported before our stop, so by the fence (§2.1) every activity change up to our stop is in the read.
- **When it reads:** at the checks that run today - the stop's 200 ms (`PinLockRuntime.kt:1685`), the pause's delay,
  and the power look. The check waits for the read for at most `FRONT_READ_WAIT_MS` (150 ms), and a read that comes
  back later counts as `UNSETTLED`. The re-front delay doesn't change, so when events are stored within about 200 ms
  of the stop (P1-P3 check this), the look is as quick as today.
- **What's left open:** an SOS that starts in the few ms between a settled read and the re-front isn't seen before it
  is covered. Its RESUMED is stamped before the glance's resume, and the next read finds it. A covered SOS app can't be
  brought back: AOSP's entry alias needs `MANAGE_SENSOR_PRIVACY` (`EmergencyGestureAction/AndroidManifest.xml:39`).
  So that read only logs `AndroidLock: front: SOS covered`. Today's timing has the same race at every re-front.
- **Logs:** `AndroidLock: front: <class>`, with the package only for `SOS`, `EMERGENCY_DIALER` and `BLOCKED`, and
  `front: unsettled (<n> reads)`. Events are held in memory for one check, never stored or sent (139 D11). The report's
  exempt-yield count is unchanged.

### 4.3 What changes in code (for the build)

- `RefrontInputs` gains `front: Front` (default `NO_ACCESS`, so today's tests hold unchanged). `exemptYield` and
  `androidLossStep` read it at the places in §4.1. `sosScreenOn` and `powerRecheck`'s `clearSosMark` follow it while
  the access is on. The mark's own rules (`sosMarkKept`, `sosMarkLogDue`) take `"sos"` from both sources.
- `PinLockRuntime`: the read on its own IO executor, `runRefrontCheck` given the `Front`, the provisional yield's
  250 ms recheck, and the yield until the SOS leaves. The comment at `RefrontPolicy.kt:591-592` is corrected.
- Tests: `FrontClassTest` (each class from event lists recorded in step 0 on 14 and 16, unsettled reads, an SOS event
  after `BLOCKED`, a late read). Rows in `RefrontPolicyTest` and `PowerGlanceTest`: the covered case with the access
  is a yield, the same case without it is today's re-front, and 1o-f with the access gives no SOS line.

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
- **Unihertz (the Jelly Star, 16):** open. Row J3, if the owner allows it (Q4).
- **G4 - the gate agent's row:** a covered SOS's countdown and call, on 14 and 16. Done in §6cl with today's build
  (results above). With step 1's build and the access on, the same sequence must show `front: SOS (...)`, a `sos`
  yield with no re-front, and the SOS screen still reachable (on 16 the hold works and places the call; `system_call`
  follows).

## 6. Step 0 (emulator `compat34` = 14, `jelly36` = 16; then the Jelly)

Setup as §6cl: Device Owner, enrolled, Child PIN 2580, the face on, kiosk on, one emulator at a time, the owner's OK
first. Usage access on: `adb shell am start -a android.settings.USAGE_ACCESS_SETTINGS -d package:me.vibb.launcher.debug`
and the switch (`adb shell cmd appops get me.vibb.launcher.debug GET_USAGE_STATS` should then show `allow`).

The probe is a throwaway local branch `probe-144` (not pushed, as design 139's `probe-139`). It adds a debug-only
receiver `FrontProbeReceiver`, which for N s polls `queryEvents(now - 10 s, now + 1 s)` every 20 ms on an IO thread
and logs each new activity, screen or keyguard event once: `FrontProbe: <type> <pkg>/<class> stamp=<ms>
seen=+<ms>`. It also logs the access read, a `ContentObserver` on `content://settings/secure/emergency_gesture_ui_showing`,
and the result or exception of reading that key.

```
adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.debug.FrontProbeReceiver --ei seconds 15
adb logcat -s FrontProbe AndroidLock PinLock GestureLauncherService
# the SOS gesture: five presses from separate calls, 0.15 s apart (§6cl)
for i in 1 2 3 4 5; do adb shell input keyevent KEYCODE_POWER; sleep 0.15; done
```

| Row | Steps | Read |
|---|---|---|
| P1 | SOS from a lit glance, 5 tries per image | the SOS package and class; `seen` for its RESUMED; its order against the glance's PAUSED and STOPPED |
| P2 | the covered case: `dumpsys battery unplug`, `KEYCODE_SLEEP`, 4.5 s, `dumpsys battery set usb 1`, the five presses | is the SOS RESUMED stored before the stop's 200 ms check? the worst `seen` |
| P3 | the blocked start (16: `am start -a android.intent.action.SET_WALLPAPER`; 14: `am start -n com.android.wallpaper.livepicker/.LiveWallpaperActivity`), with and without a plug-in, 5 tries | `android`/`BlockedAppActivity`, its `seen`, then its PAUSED and STOPPED with no RESUMED after (`KEYGUARD`) |
| P4 | swipe up to Android's pad (`input swipe 240 700 240 200`), then back | expected: only our own events |
| P5 | the pad's Emergency button, then back | `com.android.phone` and its class |
| P6 | P3 during the first 20 s of "Pause all restrictions" (`/start-pause`) | the worst `seen` under load (139's 17 s) |
| P7 | `adb shell settings get secure emergency_gesture_ui_showing` and `emergency_gesture_ui_last_started_millis`, before, during and after P1; the probe's observer and read | does the key move; does an observer fire; does the read throw |
| P8 | during P2 on 14: `adb shell dumpsys notification --noredact \| grep -B2 -A8 EmergencyGesture`; the same on 16 for `safetyhub` | the covered countdown's notification |
| P9 | `adb reboot`, the probe broadcast before the PIN | `null` / no events (`UsageStatsService.java:1410`) |
| G4 | §5 | §5 |

**The Jelly** (release build, no probe: never a debug APK on the kid's phone). adb over Wi-Fi so the USB cable stays
free for the plug rows (`adb tcpip 5555`, `adb connect <ip>:5555`; "Block USB debugging" off on the Advanced page, §4):

| Row | Command | Read |
|---|---|---|
| J1 | `adb shell cmd package query-activities --components -a com.android.systemui.action.LAUNCH_EMERGENCY`; `adb shell settings get secure emergency_gesture_enabled` | Unihertz's SOS package and activity; whether the gesture is on |
| J2 | plug in and unplug with the glance in front, then `adb shell dumpsys usagestats -c android com.agui.settings me.vibb.launcher \| grep -E "ACTIVITY_(RESUMED\|PAUSED\|STOPPED)" \| tail -30` and `adb shell dumpsys window \| grep -E "mCurrentFocus\|ChargingDialog"` | `BlockedAppActivity`; is `ChargingDialog` an activity (an event from `com.agui.settings`) or a window |
| J3 | only with the owner's OK (Q4): the SOS gesture, then the same `dumpsys usagestats -c` for J1's package | its class; countdown or hold; what it does when covered |

## 7. Android 14-17

| | 14 | 15 | 16 | 17 |
|---|---|---|---|---|
| `queryEvents(long,long)`, RESUMED/PAUSED/STOPPED, `getClassName` | API 21/29 [sdk] | same | same | same [sdk 37] |
| The reporting path (§2.1) | read [aosp14] | not read; between 14 and 16 [assumed] | read [aosp16] (+ PAUSED at STOPPING) | not read [assumed] |
| `null` while storage is locked | `:1406-1412` | [assumed] | `:1581` | [assumed] |
| The usage switch | not restricted (§6ca) | restricted settings: "Allow restricted settings" first (`setup.how.usage_access`) | same | same |
| A covered AOSP SOS still calls | source + gate | [assumed] | AOSP source same; Google's `safetyhub`: lost (gate) | [assumed] |
| Hidden Secure keys unreadable to apps | yes (S+) | yes | yes [aosp16] | yes [assumed] |
| Gate rows | `compat34` | none | `jelly36` | none (no API 37 image runs here) |

Every API used is public at or under minSdk 34. The 35+ `UsageEventsQuery` (API 35 [sdk]) is not needed.

## 8. Cost

- Launcher: `lock/FrontClass.kt` (~120 lines, pure) + `FrontClassTest` (~150). Glue in `PinLockRuntime` (~80) and
  `RefrontPolicy` (~40), with test rows (~100). No server change, no contract, no policy JSON, unless Q1 (one
  `setup_status.rs` step entry plus an nb/en (i) line and its test, ~20).
- Step 0 with the probe, about half a day on two images. One gate on 14 and 16 after the build, then the Jelly rows.

## 9. Owner questions (with recommendations)

- **Q1 - nudge the parent towards usage access?** Today the setup step `usage_access` applies only with app groups
  (`server/src/setup_status.rs:253-266`). Recommended: the step applies to every phone as an **optional** step.
  Leaving it off is the parent's choice: no orange line, no warning (CLAUDE.md "Words in the UI"). One line goes
  behind its (i) - nb "Med denne på kjenner låseskjermen igjen Nød-SOS sikkert.", en "With it on, the lock screen
  knows Emergency SOS for certain." With app groups it stays required, as now.
- **Q2 - after a certain SOS closes into Android's lock, re-front the glance once?** Recommended: yes (§4.1). Today
  Android's lock stays until the screen goes off.
- **Q3 - in the SOS window, should a read that hasn't settled yield (provisionally, with 250 ms rechecks)?**
  Recommended: yes. The cost is a short look at Android's lock when events are late: about 0.2 s normally, at worst as
  late as the events (17 s seen under load on 14). That stays inside today's 15 s yield.
- **Q4 - row J3 on the Jelly (the real gesture)?** It may call 112 for real. Recommended: J1 and J2 only, and J3 only
  if Unihertz's SOS settings show a cancel or a hold before any call, with the owner holding the phone.
- **Q5 - without the access, narrow the open hole?** The timing can't tell a press-SOS from the dark from a power
  change's wake. Narrowing `POWER_WAKE_BEFORE_MS` (800 -> 400 ms) brings back the charging glimpse for a late stamp.
  Recommended: keep the timing as built and close the hole through the access (Q1). Phones without it keep the hole.
