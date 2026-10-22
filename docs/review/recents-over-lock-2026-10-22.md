# Recents over the PIN lock (kiosk on, LOCKED) - 2026-10-22

Read-only investigation, no code changed. The bug: on `jellystar2` (Android 16 Google APIs, the Pixel launcher
`com.google.android.apps.nexuslauncher` as the recents provider, gesture nav, kiosk on, LOCKED) the Recents key and a
swipe up open Quickstep's `RecentsActivity` over the lock, with cards readable. The lock-task features are 113, and 112
during a heal. The user's follow-up test: tapping a card flashes the app (e.g. the music player) for a moment, then the
lock comes back (`topResumedActivity=...PinLockActivity`). So the PIN is not bypassed, but Recents and its thumbnails
can be reached.

Sources: AOSP `refs/heads/android16-release` on android.googlesource.com (`fw/` = `platform/frameworks/base`,
`L3/` = `platform/packages/apps/Launcher3`). Line numbers are from that branch as fetched today. [device] = needs an
on-device check. [unverified] = from memory, not read in source.

## 1. Why Recents opens with OVERVIEW off

**The heal is not the cause.** `writeHealFlip` (`launcher/.../lock/LockTaskChrome.kt:319-329`) writes only the
features, with SYSTEM_INFO flipped (`StatusBarHealPlan.kt:76-77`). Neither 113 nor 112 has HOME (4) or OVERVIEW (8).
`LockTaskController.getStatusBarDisableFlags` (`fw/services/core/java/com/android/server/wm/LockTaskController.java:1000-1013`)
starts from DISABLE_MASK and clears only the bits of the features that are set (map at :105-125). So DISABLE_HOME and
DISABLE_RECENT stay set in both writes. `updateLockTaskFeatures` (:866-881) posts `setStatusBarState` (:887-913), which
reads the current features when it runs and sends the whole `disable`/`disable2` set. The heal never opens a gap, and
`isRecentsAllowed` (:403-406, needs bit 8) is false throughout.

**The Recents key never looks at DISABLE_RECENT.** The path:
- `PhoneWindowManager` `KEYCODE_APP_SWITCH` (`fw/.../policy/PhoneWindowManager.java:3657-3667`, and the key-gesture
  path :4227-4234) checks only `keyguardOn()` (:3479-3481). Then `toggleRecentApps` (:4928-4934) goes to
  `StatusBarManagerInternal`.
- SystemUI's `OverviewProxyRecentsImpl.toggleRecentApps` (`fw/packages/SystemUI/.../recents/OverviewProxyRecentsImpl.java:84-107`)
  checks only whether the keyguard is showing.
- Quickstep's `TouchInteractionService.onOverviewToggle` (`L3/quickstep/.../TouchInteractionService.java:215-225`) refuses
  only `isScreenPinningActive()`. SystemUI sets `SYSUI_STATE_SCREEN_PINNING` only for `LOCK_TASK_MODE_PINNED`
  (`TaskbarDelegate.java:146-151`), never for a Device Owner's LOCKED mode. `OverviewCommandHelper` has no other gate.

**Swipe up.** Quickstep blocks a swipe only when SystemUI's flags say HOME **and** OVERVIEW are both disabled
(`RecentsAnimationDeviceState.canStartAnyGesture`, `L3/.../RecentsAnimationDeviceState.java:438-450`). Those flags come
from `TaskbarDelegate.disable` → `updateSysuiFlags` (`TaskbarDelegate.java:535-539`, :389-392). With 113 in force the
swipe should be refused. If it opens Recents, either SystemUI's copy is out of sync (the 16d desync the heal works
against), or the swipe followed a running Recents (`previousGestureState.isRecentsAnimationRunning` skips the check,
`L3/.../InputConsumerUtils.kt:148-150`). [device] Run `adb shell dumpsys activity service
com.google.android.apps.nexuslauncher/com.android.quickstep.TouchInteractionService | grep -iE "canStartSystemGesture|sysui"`
right after a fresh swipe from the lock.

**Why the lock-task allowlist doesn't stop it (the root cause).** While LOCKED, the kiosk list drops the recents
provider (`server/LockTaskHelpers.kt:189`). Any normal start of a package that isn't on the list, with the block bit 64
set, becomes BlockedAppActivity (`ActivityStartInterceptor.java:392-409`, `LockTaskController.isActivityAllowed`
:418-430). But Quickstep starts Recents as a transient launch (`fw/libs/WindowManager/Shell/.../RecentsTransitionHandler.java:221`,
`sendPendingIntent`), and `WindowOrganizerController` (`fw/.../wm/WindowOrganizerController.java:1261-1272`) tries
`ActivityStartController.startExistingRecentsIfPossible` first (`ActivityStartController.java:632-681`). If an attached
`RecentsActivity` record already exists, that path just calls `task.moveToFront` + `resumeTopActivity`. It never passes
through `ActivityStarter`, the interceptor or any `LockTaskController` check. The only things it refuses are: the
activity is already visible on top, it isn't attached, the component differs, the caller isn't recents, or
**`isKeyguardLocked()`** (:652-657).

The LOCKED edge clears only tasks that are in `mLockTaskModeTasks` (`LockTaskController.updateLockTaskPackages`
:765-787). A RecentsActivity task joins that list only in `realStartActivityLocked` (`ActivityTaskSupervisor.java:894-901`).
If it was created while lock task wasn't LOCKED yet (likely: quickstep's preload at TIS init after boot,
`RecentsAnimation.preloadRecentsActivity` :81-145) and was only ever reused through `startExistingRecents` afterwards,
it survives every LOCKED edge. [device] `adb shell dumpsys activity activities | grep -A12 LockTaskController` while
LOCKED: is the RecentsActivity task missing from `mLockTaskModeTasks`, and is nexuslauncher missing from
`mLockTaskPackages`? Also `dumpsys activity activities | grep -B1 -A4 "type=recents"`. If the record is gone, the start
falls through to the interceptor and gives the "stale BlockedAppActivity" residual that `launcher/CLAUDE.md` already
documents (16d).

## 2. Card tap from Recents: no PIN bypass, but a glimpse

A card tap → `startActivityFromRecents` (`ActivityTaskSupervisor.java:2860-2900`) → `moveTaskToFrontLocked`
(`ActivityTaskManagerService.java:2292-2340`). That refuses only `isLockTaskModeViolation(task)` (:2331). In kiosk the
LOCKED list keeps every allowed app (`LockTaskHelpers.kt:189`), and a locked or allowlisted task is not a violation
(`LockTaskController.java:338-350`, :379-401). So Home, the music app and WhatsApp come to the front. The lock is then
stopped, and `onLockStopped` (`PinLockRuntime.kt:1357`) re-fronts it after `PAUSE_REFRONT_MS` = 200 ms
(`RefrontPolicy.kt:102`), with retries forever after that (`refrontDelayMs`). This is what the user saw: the app flashes,
then the lock is back.

What is still open:
- The app is live for about 200 ms or more: it can be seen and touched.
- Within 3 s of a screen-on the pause waits 700 ms (`SOS_PAUSE_GRACE_MS`), and a stop marks a probable SOS. The "sos"
  yield then narrows the list (`sosNarrowedPackages`), which clears the app's task (qa-25b-25f H1), so the app is shut,
  not left up.
- [unverified] Swiping the lock's card away, or "Clear all", may remove the lock's task. The stop re-front starts the
  lock again, but this needs a [device] check.

## 3. The lock already re-fronts, but only after Recents has drawn

Bug 25b-A added re-front on a pause: `PinLockActivity.onPause` (`PinLockActivity.kt:507-520`) → `onLockPaused`
(`PinLockRuntime.kt:1341-1353`) → `runRefrontCheck` (:1417-1469) after 200 ms (700 ms within 3 s of a screen-on) →
`show()` (:1262-1303). The user's test shows it works. It can't stop the problem because it runs **after** Recents has
drawn: the cards are on screen for the transition plus at least 200 ms.

The thumbnails are only partly blank:
- The lock has `FLAG_SECURE` (`PinLockActivity.kt:268`).
- Home has `setRecentsScreenshotEnabled(false)` (`HomeActivity.kt:233`), but `AbsAppSnapshotController.getSnapshotMode`
  (`fw/.../wm/AbsAppSnapshotController.java:212-225`) returns REAL for a HOME-typed task before it checks app-theme. So
  this flag does nothing for our HOME-typed Home (`HomeFront.kt`). HOME tasks are normally left out of Recents
  [unverified], so a "home card" suggests a STANDARD Home task or a mislabelled card. [device] `adb shell dumpsys
  activity recents`.
- The music app has neither `FLAG_SECURE` nor `setRecentsScreenshotEnabled(false)` (grep of `music/app/src/main`).

## 4. Fix options for today's lock, smallest safe first

1. **Re-front at once when Recents shows while LOCKED.** Use `PAUSE_REFRONT_MS` = 0 outside the SOS window.
   - Closes: shortens the glimpse.
   - Doesn't close: Recents still draws for a few frames, and a fast card tap still works.
   - Cheap; keep it as a backstop.
2. **Blank our own thumbnails.**
   - Music app: `setRecentsScreenshotEnabled(false)` on its activities. App-theme snapshot, verified at
     `ActivityRecord.java:5603-5618` with `AbsAppSnapshotController.java:221-223`.
   - Home: needs `FLAG_SECURE` instead of the screenshot flag, or none if HOME tasks never show [device]. A secure
     window counts as `isSecureLocked`, but the HOME branch returns REAL first, so check on device whether
     `captureLayersExcluding` drops secure layers for Home [unverified].
   - Closes: our apps' content in Recents.
   - Doesn't close: other apps, or Recents itself.
3. **Device-wide `DevicePolicyManager.setScreenCaptureDisabled(admin, true)`.** This is the only lever a Device Owner has
   over other apps' thumbnails. `WindowState.isSecureLocked` returns true for every window when capture isn't allowed
   (`WindowState.java:1806-1821`), so every non-HOME task gets an app-theme (blank) snapshot from then on.
   - Closes: WhatsApp and every other app's readable cards, as long as capture stays disabled.
   - Doesn't close: Recents opening. Snapshots already taken while capture was allowed are kept;
     `refreshScreenCaptureDisabled` (`WindowManagerService.java:2120-2131`) refreshes only the secure surface state
     [unverified that the cache is dropped]. So switching it on only while LOCKED leaves the earlier cards readable.
     Only "always on" is reliable.
   - Cost: no screenshots or screen recording for the kid, and apps' screen sharing breaks. The user has to decide.
   - There is no public API to blank one other app's thumbnail.
4. **Remove the existing RecentsActivity at the LOCKED edge**, so every start goes to the interceptor.
   - A Device Owner has no clean tool: it can't force-stop apps. `killBackgroundProcesses` doesn't kill quickstep
     (SystemUI keeps it bound). `setApplicationHidden` or suspending quickstep would kill or break TIS and gestures, and
     suspension doesn't remove a live activity record anyway.
   - Not recommended. Even when it works it leaves the BlockedAppActivity dialog.
5. **Not possible:** a `SCREEN_PINNING`-style refusal (only `LOCK_TASK_MODE_PINNED` sets it; a Device Owner's
   `startLockTask` is LOCKED). Turning OVERVIEW on would make it worse. SystemUI and Quickstep read nothing else for the
   Recents key.

## 5. Under design 59's keyguard, Recents can't open over the lock (confirmed in source)

With Android's secure keyguard locked, occluded by our `showWhenLocked` glance or not, every layer refuses:
- **PhoneWindowManager:** APP_SWITCH is dropped when `keyguardOn()` (:3658, :4229). Whether that holds while occluded,
  through `inKeyguardRestrictedKeyInputMode`, is [unverified].
- **SystemUI:** `OverviewProxyRecentsImpl` (:99-103) runs the toggle through `executeRunnableDismissingKeyguard`, so the
  bouncer (PIN) comes first.
- **WM (existing Recents):** `startExistingRecents` refuses while `isKeyguardLocked()` (`ActivityStartController.java:656-657`;
  `KeyguardController.isKeyguardLocked` :150-153 is true whether or not the keyguard is occluded).
- **WM (new Recents):** a fresh transient launch while locked and occluded neither resumes nor moves to front
  (`ActivityStarter.java:2770-2777`).
- **Shell:** the recents transition carries `mKeyguardLocked` (`RecentsTransitionHandler.java:707`, :1391).
- **Quickstep gestures:** `STATUS_BAR_KEYGUARD_SHOWING` blocks gestures (`RecentsAnimationDeviceState.java:443`), and an
  occluded keyguard gets `DeviceLockedInputConsumer` (`InputConsumerUtils.kt:500-502`, :115-124).

So design 59 turns this into a platform guarantee, as its line 54 claims. [device] The 25b/59 QA should still press the
Recents key and swipe over the glance.

**Recommendation:**
- Now: option 1 (re-front at 0 ms) and option 2 (our own thumbnails).
- Ask the user about option 3 (always-on screen-capture block), the only cover for other apps' cards today.
- Treat design 59's keyguard as the real fix: it is the only one that keeps Recents from showing at all.
- Run the [device] checks in §1 to confirm the surviving-RecentsActivity mechanism before anyone builds on it.
