# Design 59 hybrid prototype - emulator run, 2026-10-22

The prototype at `46cf0fa5` (branch `proto-59-hybrid`), run against `docs/design/59-prototype.md` §3 on the local
headless AVD `handy36`: Android 16, google_apis_playstore x86_64, 480x854 at 320 dpi, gesture navigation, English
locale (so "Lås opp" reads "Unlock" and "Nødsamtale" reads "Emergency call").

Setup:
- A local server 0.36.0 with a fresh data dir and the debug admin component.
- The PWA steps done over HTTP: add the phone, a setup code, Block USB debugging off (the other restrictions at their
  defaults), Parent PIN, Child PIN `1357`, calls managed, contact "Mamma" +4791234567.
- Chrome and Messages allowed, so Recents had thumbnails: a Messages chat "Hemmelig melding SECRET-42/43", Chrome's
  first-run page, Settings.
- Provisioned and enrolled as `docs/testing/emulator.md` §4.

Logs: tags `Hybrid59 PinLock PinLockActivity KeyguardCover LockTaskChrome CameraLock KidCallScreening`, kept across
reboots. Screenshots came from the emulator console (`adb emu screenrecord screenshot`), which does capture the
`FLAG_SECURE` glance. They are not committed.

Not available on this image: WhatsApp, Element X, Vibb music, and hand gestures. Steps 8, 14 (c) and 16 were not
possible here.

**Note for the next run:** the AVD is not fresh at each boot. `disk.dataPartition.path = <temp>` is ignored because
the emulator keeps `userdata-qemu.img.qcow2`. The first boot came up with an old 0.23.6-debug device owner from
2026-10-19 still installed. I restarted once with `-wipe-data`.

## Summary

| Area | Result |
|---|---|
| **Recents from the lock (step 14)**, hybrid | **PASS**. No Recents and no thumbnail in any of these tries: APP_SWITCH, a swipe up from the bottom edge, a swipe-up-and-hold (1.5 s), the square-button area in 3-button mode. Also with the kiosk off (parent pause), on SystemUI's own lock screen, and after a reboot. |
| Recents, today's lock (switch off), for comparison | **FAIL**, the user's bug reproduced. After a reboot, APP_SWITCH on the lock opened Quickstep's `RecentsActivity` over it, with a card of the other task. Without a reboot it gave the stale `BlockedAppActivity` instead. |
| The glance as the face at every wake (step 3) | **PASS**. 81 screen-offs: 80 had `keyguardLocked=true deviceLocked=true`, and one had a 400 ms `deviceLocked=false` race that did no harm. The screen-timeout case passed too. An emulator-side recording of 4 wakes from Messages showed no frame of the app, Home or SystemUI's lock screen. |
| `isKeyguardLocked`/`isDeviceLocked` while occluded (59 §4 Q3) | **PASS**. Both read `true` over the locked keyguard in every resumed/stopped line. `false` appears only after a real PIN or fingerprint. |
| Migration, "Lås opp" -> Android's PIN pad, row tap, Back, sleep on the pad (steps 1, 2, 5) | **PASS**, with one bug (B2 below). |
| Wrong PINs and lockout (step 6) | **PASS**. 30 s after the 5th failure, 30 s after the 10th, then 30 s after each failure. Emergency works during the wait. Whether the wait survives a reboot could not be told: the boot took longer than the wait. |
| Notifications, Home, shade and QS on the lock (steps 7, 13) | **PASS**. Nothing opened from the glance or SystemUI's lock screen, with the kiosk on or off. No notification showed on Android's lock screen. Home can't be brought over the glance at all. The device-owner line shows. One shade glitch (B2). |
| BFU (step 15) | **PASS**. Android's lock comes first (`RUNNING_LOCKED`) with the line "Skriv koden for å starte telefonen". The blocked caller was rejected by `KidCallScreening` before the first unlock. The allowed caller got our call screen (silhouette). The first unlock reaches Home. |
| Fingerprint (step 17) | **PASS**. From dark: Home. On the glance: ignored. On the PIN pad: unlocks. After remote lock: refused ("device was locked by work policy"). |
| Emergency call, SOS, camera gesture, torch (steps 9-12) | **PASS**. |
| Remote lock (step 18) | **PASS**. The PWA shows "locked (handy lock)". |
| Rollback `own` (step 19) | **PASS**, both with the keyguard up and unlocked. The PIN was cleared and our keypad is back. |

**Anything past the PIN or app content while locked: nothing found.** Every way out of the lock needed the kid PIN
(or the enrolled fingerprint) on Android's pad.

The two bugs below are about getting stuck behind the lock, not about getting past it:
- **B1**: after an unlock through SystemUI's own lock screen, our lock stays LOCKED and hidden.
- **B2**: once, "Lås opp" opened an empty shade instead of the PIN pad.

## Steps

### 1. Switch on: PASS

`adb shell am broadcast -n $H --es keyguard android` returned:

```
data="android -> switch=android face=false secure=false androidLocked=false token=true keyguard: mode=DISABLED features=0x2 maxTimeToLock=0 disabled=true armed=true dismissTest=NONE"
Hybrid59: apply: switch=on managed=true kidPin=true secure=false token=true face=false - the next correct kid PIN becomes Android's
```

### 2. Migration: PASS

"Unlock" showed our keypad, and the PIN taps opened the phone as usual.

```
Hybrid59: migration: the kid PIN set as Android's PIN: true (secure now=true, token=true)
PinLock : LOCKED -> UNLOCKED on Unlocked(checkedAt=3, screenOffs=3)
Hybrid59: apply: switch=on managed=true kidPin=true secure=true token=true face=true (changed)
Hybrid59: lock-screen line: "Skriv koden for å starte telefonen"
Hybrid59: keyguard: mode=HYBRID features=0x217 maxTimeToLock=60000 disabled=false (apply)
```

- `locksettings get-disabled` returned `false`.
- `disabledKeyguardFeatures=535` (0x217: WIDGETS_ALL, SECURE_CAMERA, SECURE_NOTIFICATIONS, TRUST_AGENTS, SHORTCUTS_ALL).
- The `maximumTimeToLock` and lock-screen-info fields aren't in this image's `dumpsys device_policy` grep. The log
  lines above stand for them.
- `maxTimeToLock=... (the setting changed)` is logged twice per apply. It is noise, not a loop.

### 3. Screen off and on: PASS

Cycles covered:
- More than 20 power off/on cycles from Home and from the glance (`keyevent 26` and SLEEP/WAKEUP).
- 4 cycles from Messages, recorded on the emulator side at 24 fps.
- One 60 s screen timeout from Messages.

What showed:
- Every wake had `PinLockActivity` on top and `isKeyguardShowing=true`.
- Classified frame by frame, the Messages recording goes Messages, fade, black, glance at each wake. The only other
  frames are the glance's own dimmed fade and two frames of its empty background.
- No frame of Messages, Home or SystemUI's lock screen.

```
Hybrid59: screen-off, lock-up LOCK: keyguardLocked=true deviceLocked=true secure=true interactive=false
Hybrid59: the glance resumed: keyguardLocked=true deviceLocked=true secure=true interactive=true
Hybrid59: Recents guard: lockTask=1 features=0x70 guarded=true
```

Timeout from Messages: `12:34:58 screen-off, lock-up LOCK: keyguardLocked=true deviceLocked=true`, so T took.

Of the 81 lock-up lines, one had `keyguardLocked=true deviceLocked=false`:
- It was the first screen-off after a PIN-pad unlock (11:56:04), and the decision was `HOME_THEN_LOCK` (kg=false).
- `deviceLocked` read `true` 400 ms later, before the wake. Home sat behind the locked keyguard, so this was harmless.

`guarded=false` was logged once (11:59:22, `lockTask=0 features=0x31`):
- It came in a parent pause, at the glance's first resume after a screen-off, with the screen still off. Lock task
  came back 300 ms later.
- The keyguard was locked throughout, and that alone blocks Recents (see BFU below).

### 4. The glance as the face: PASS

- An allowed call (`gsm call +4791234567`) showed our call screen ("Mamma") over the keyguard.
- After `gsm cancel`, the glance showed a "Mamma - Missed call" row, plus the clock, the date, Unlock and Emergency
  call.
- SMS and `cmd notification post` notifications don't show on the glance, since Messages isn't a glance app.
- A call from `4791234567` without `+` became US "(479) 123-4567" and was screened out. That is expected.

### 5. "Lås opp" and Android's PIN pad: PASS

(a) Unlock opens Android's PIN pad, then the PIN and Enter go Home:

```
Hybrid59: Lås opp: requestDismissKeyguard (Android's PIN pad): keyguardLocked=true deviceLocked=true ...
Hybrid59: Android's PIN pad: succeeded: keyguardLocked=false deviceLocked=false ...
PinLock : LOCKED -> UNLOCKED
```

- The pad is SystemUI's bouncer ("Enter PIN", a text field because the AVD has a hardware keyboard, "Emergency"). It
  is opaque, and none of our keys or dots show under it.
- The look is plain but acceptable. A stray 12 px icon shows at its left edge, from SystemUI.

(b) A tap on the missed-call row, then the PIN, opened `PhoneBookActivity`, the row's target.

(c) Back on the pad returned to the glance (`Android's PIN pad: cancelled`).

(d) A screen-off on the pad, then a wake, showed the glance and not the pad.

### 6. Wrong PINs and lockout: PASS

| Failures | What the pad showed |
|---|---|
| 1-4 | "Wrong PIN. Try again." |
| 5 | "Try again in 27 seconds." / "Too many attempts with incorrect PIN" (a 30 s wait) |
| 10 | 30 s |
| 11, 12, 13 | 30 s each |

That matches AOSP.

During a wait:
- The glance's Emergency call opened our "Call 112?" dialog.
- The pad's Emergency opened `safetyhub/.emergencydialer.ui.DialPadActivity`, a dial pad only. Back returned to the
  glance.

Reboot during a wait: after the boot the pad showed no wait. The reboot took longer than 30 s, so this can't tell
whether a wait persists.

`dumpsys lock_settings` showed `CredentialType: PIN` and `LSKF last changed: 11:53:47` (the migration).

### 7. SystemUI's lock screen, notifications and Home: PASS

`am start` of `HomeActivity` while locked returned "Activity not started, its current task has been brought to the
front". Focus stayed on the glance, so lock task keeps Home from coming up over the glance at all.

To reach SystemUI's lock screen I started a non-`showWhenLocked` activity that is on the LOCKED lock-task list:
`com.google.android.deskclock/com.android.deskclock.DeskClock`.

SystemUI's lock screen showed:
- The big clock, the lock icon, and an indication line rotating between "This device belongs to your organization"
  and **"Skriv koden for å starte telefonen"**.
- **No notification**, though the SMS and the shell notification existed. No media player (none playing). No Home,
  no clock app.

```
Hybrid59: the glance stopped: keyguardLocked=true deviceLocked=true ...
Hybrid59: the glance isn't in front - no re-front, Android's keyguard guards: ...
```

Swiping up gave the PIN pad, and the PIN unlocked into DeskClock. **The glance did not leave**: see B1.

This screen has the keyguard's 10 s user-activity timeout (`activityTimeoutWM=10000`), so it goes dark fast.

### 8. Music tile: not possible here

Vibb music isn't installed.

### 9. Torch and shortcuts: PASS

- No lock-screen shortcut shows on the PIN pad or on SystemUI's lock screen.
- The glance shows its torch button with the "unavailable" (struck-through) icon. I did not press it.

### 10. Emergency call (Nødsamtale): PASS

On the glance: Emergency call, "Call 112?", CALL.
- The Google dialer's emergency in-call screen showed over the keyguard (`com.google.android.dialer/...InCallActivity`,
  Telecom ACTIVE).
- `adb emu gsm cancel 112` did not end it, and the call wasn't in `gsm list`. Its own hang-up button did.
- After the call: **the glance**. `the glance resumed: keyguardLocked=true deviceLocked=true`.
- The pad's own Emergency button: see step 6.

### 11. Emergency SOS: PASS

- `input keyevent 26 26 26 26 26` in one command was "too fast" (25 ms) for `GestureLauncherService`.
- Five separate `keyevent 26` commands (576 ms) launched `safetyhub/.emergencysos.ui.EmergencySosActivity` ("Call
  911", hold to start) over the glance.
- Its settings gear did not open settings: the activity left, and SystemUI's lock screen showed.
- No re-front fight: no `Re-fronting the lock`, and only `the glance isn't in front - no re-front`. After the SOS
  screen: SystemUI's lock screen until the next screen-off, then the glance.
- `PinLock: The lock left the front just after a screen-on: probably Emergency SOS` is also logged when any other
  non-`showWhenLocked` start takes the front within 3 s of a screen-on (the DeskClock case in step 7).
  `PinLockRuntime.kt:1431`.

### 12. Camera gesture: PASS

With both gesture settings on, `keyevent 26 26` on the glance and from dark both logged `GestureLauncherService: Power
button double tap gesture detected, launching camera`.
- No camera showed in either case. Top stayed `PinLockActivity`.
- `CameraLock` logged no "camera apps suspended" and no "Camera disabled by policy". It logged only `Camera enabled by
  policy (locked false, call false)` at the process start.
- So `KEYGUARD_DISABLE_SECURE_CAMERA` alone refused the gesture.
- The settings were put back to off.

### 13. Shade and Quick Settings: PASS (one glitch, B2)

Tries: a swipe down from the top, `cmd statusbar expand-notifications` and `expand-settings`, with the kiosk on and
off (parent pause).
- On the glance: nothing opened.
- On SystemUI's lock screen: nothing opened.

In BFU (no lock task, our app not running):
- A swipe down opened the keyguard's empty notification panel ("No notifications").
- `expand-settings` opened nothing.
- No tile was reachable.

### 14. Recents and thumbnails (highest priority): PASS in the hybrid

Before each try the phone was unlocked in Messages, so a chat thumbnail with "SECRET-42/43" existed. Each try was
followed by a lock and a wake. After each try, the `topResumedActivity`/`mResumedActivity` lines and the console
screenshot were taken.

| Try | Hybrid, kiosk on | Hybrid, kiosk off (pause) | Hybrid, SystemUI's lock screen | Hybrid, after a reboot |
|---|---|---|---|---|
| Gesture nav (`navigation_mode` 2): swipe up from the bottom edge `swipe 240 852 240 427 150` | glance | glance | lock screen | glance |
| Gesture nav: swipe up and hold `swipe 240 852 240 569 1500` | glance | glance | lock screen | glance |
| Gesture nav: `KEYCODE_APP_SWITCH` | glance | glance | lock screen | glance |
| 3-button: APP_SWITCH, a tap on the square's place (300,830), swipe, hold | glance | glance | - | - |

In every cell, `topResumedActivity` stayed `PinLockActivity` (SystemUI's lock screen: focus `NotificationShade`).

In 3-button mode the glance's nav bar shows only Back: Home and Recents are hidden.

A primed run made sure a `RecentsActivity` record existed:
- Recents was opened while unlocked; it showed the Messages card with the readable "SECRET-43" thumbnail, which is
  normal unlocked.
- Then Home, a lock, and the three tries: glance each time.
- The lock-task LOCKED edge then cleared the recents task (`type=recents` count 5 -> 0).

The `Recents guard:` lines all read `guarded=true`, except the one transient in step 3.

**Android's keyguard alone also holds Recents.** In BFU (lock task `NONE`, our process not running):
- APP_SWITCH did nothing.
- A swipe up from the bottom gave Android's PIN pad.
- The hold returned to the lock screen.

Comparison with **today's lock (switch off)**:
- Fresh after provisioning: APP_SWITCH gave `android/com.android.internal.app.BlockedAppActivity`. Swipe and hold did
  nothing.
- **After a reboot** (the `RecentsActivity` task was preloaded at boot):

  ```
  == after offboot-g-switch
      topResumedActivity=ActivityRecord{... com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivity t10}
  ```

  The screenshot shows Quickstep's carousel over the lock: the lock's card, and a white card to its left (Messages).
  This is the user's bug, reproduced.
- In the hybrid, after a reboot, the same three tries gave nothing. In that run the boot had not left a recents task.
  The keyguard check in `startExistingRecentsIfPossible` (`isKeyguardLocked`) and in PhoneWindowManager's
  `keyguardOn()` cover that case anyway, as the BFU run shows.

(c), a held swipe by hand: not possible here (no window).

### 15. Reboot (BFU): PASS

(a) After `adb reboot`, `dumpsys user` showed `State: RUNNING_LOCKED`.
- SystemUI's lock screen came first: the clock, the lock icon, and the indication line **"Skriv koden for å starte
  telefonen"** (answer 1: yes, the device-owner line shows before the first unlock), alternating with "This device
  belongs to your organization".
- No glance: our process doesn't run before the unlock.

(b) Blocked caller `+4799999999`: **rejected without UI**.

```
KidCallScreening: Rejecting an incoming call
Telecom : Event: RecordEntry TC@1: SCREENING_COMPLETED, [Reject, logged, mCallBlockReason = 1, mCallScreeningAppName = Vibb One (debug) ...
TelecomFramework: onCallFilteringCompleted(TC@1_1, CallFilteringCompletionInfo{mIsBlocked=true, ...
```

(c) Allowed caller `+4791234567` showed our `InCallActivity` over Android's lock: "Calling you...", the silhouette,
the number (contacts are CE, so there is no name), Decline and Answer.

(d) PIN on Android's pad:

| After | Top activity |
|---|---|
| 1-3 s | `nexuslauncher/.NexusLauncherActivity` (the stock launcher, while our process starts) |
| 4-5 s | our Home |
| 7 s | the glance, about 1.4 s |
| 8 s on | Home |

```
Hybrid59: process start: switch=on face=true (secure=true, androidLocked=true, lock active=true)
PinLock : Re-fronting the lock (#1)
Hybrid59: the glance resumed: keyguardLocked=false deviceLocked=false ...
Hybrid59: Android's keyguard isn't locked at the glance's resume - the glance leaves
```

Then a lock and a wake showed the glance.

### 16. VoIP: not possible here

Neither Element X nor WhatsApp is installed.

### 17. Fingerprint: PASS

(a) Enrolled in a parent pause, started with the PWA's start-pause:
- `am start -a android.settings.FINGERPRINT_ENROLL` asked "Re-enter your PIN" (1357).
- Three `adb emu finger touch 1` gave "Fingerprint added".
- "Lock again" on Home's banner ended the pause: `WindowRelock: Relocked (lock_again, [pause]): cleared=true`.
- No `Removing the Android screen lock`, and `locksettings get-disabled` returned `false`.
- The earlier PWA "end windows" relock kept the PIN as well.

(b) Screen off, then a touch: Home.

```
Hybrid59: the glance resumed: keyguardLocked=false deviceLocked=false ...
Hybrid59: Android's keyguard isn't locked at the glance's resume - the glance leaves
```

This went through the resume path, not `USER_PRESENT` (B1).

(c) On the glance, a touch: nothing. The glance stays and the keyguard stays locked.

(d) On the PIN pad, a touch: `Android's PIN pad: succeeded`, then Home.

(e) After the PWA's Locate -> Lock: `primaryAuthFlags=2` (after `lockNow`). On the pad a touch did nothing, and the
pad showed "For added security, device was locked by work policy". The PIN was needed.

### 18. Remote lock: PASS

The screen went off, and the wake showed the glance. Unlock gave the PIN pad. The PWA's Locate page shows "locked
(handy lock)".

### 19. Rollback to "own": PASS

(a) With the glance up and the keyguard locked:

```
data="own -> switch=own face=false secure=false androidLocked=false token=true keyguard: mode=DISABLED features=0x2 maxTimeToLock=0 disabled=true ..."
Hybrid59: rollback: Android's PIN cleared: true (secure now=false)
Hybrid59: lock-screen line: cleared
```

`disabledKeyguardFeatures=2`.

(b) The glance was still in front (our lock stayed LOCKED). Unlock showed **our** keypad with "Parent code", and
`1357` opened it. `locksettings get-disabled` returned `true`.

(c) Switch on again, migrate on our keypad, then `own` while unlocked: the same lines, `get-disabled` `true`.

(d) The run ended on `own`. Today's lock works: wake, our keypad, PIN, Home.

Recents with `own`, after a run with Recents opened while unlocked: APP_SWITCH gave `BlockedAppActivity` again, not
Recents.

## Bugs found

### B1 (Medium): `USER_PRESENT` never reaches the launcher

When Android is unlocked anywhere but through our own ask, our lock stays LOCKED (not visible), and the face doesn't
go.

Cause:
- `PinLockRuntime.kt:455-464` registers `screenReceiver` (SCREEN_ON, SCREEN_OFF, USER_PRESENT) with
  `ContextCompat.RECEIVER_NOT_EXPORTED`.
- `USER_PRESENT` is sent by SystemUI (`caller=com.android.systemui ... uid=10187`), not by the system uid.
- In `dumpsys activity broadcasts` our filter (`BroadcastFilter{766440c ... me.vibb.launcher.debug}`) is resolved for
  `USER_PRESENT`, but it is **not among the delivered receivers** of the two `USER_PRESENT` records (12:04:47, 11:59:03).
  NexusLauncher and GMS receivers registered the same way were delivered.
- SCREEN_ON and SCREEN_OFF (sent by system_server) do arrive.

So neither the prototype's hook (`PinLockRuntime.kt:784-790`) nor the older QA 10 #15 backstop (`:800`) ever runs.

Seen:
- After unlocking through SystemUI's own lock screen (step 7), DeskClock was in front and Android was unlocked.
- No `USER_PRESENT` line. Our mode stayed LOCKED, with `The lock left the front ...: probably Emergency SOS` and no
  re-front.
- A press on Home brought the glance, which then left on its resume check
  (`Android's keyguard isn't locked at the glance's resume - the glance leaves`).

Not a way past the PIN: the PIN was typed. The fingerprint from dark (17b) and the first unlock after a boot (15d)
still worked through the resume path.

Fix direction: register `USER_PRESENT` exported, or in its own receiver registered `RECEIVER_EXPORTED`. It is a
protected broadcast, so only the system can send it.

### B2 (Medium): "Lås opp" opened an empty, expanded shade instead of the PIN pad, and the ask stayed open

Seen once, in a parent pause (kiosk off):
- "Unlock" was tapped about 5 s before the pause's apply (12:22:41; apply 12:22:47, lock-task features and status bar
  rewritten).
- SystemUI showed the notification shade, expanded and empty ("No notifications"), over the glance, not the bouncer.
  `statusBarState=SHADE`, `mShadeExpandedFraction=1.0`, `bouncerShowing=false`.
- A swipe down expanded QS (`qsPanelExpansion: 1.0`). The screen captured black, and taps where tiles would be changed
  nothing (airplane, Wi-Fi, Bluetooth and DND unchanged).
- No `Android's PIN pad: ...` answer came. For 30 s further taps on Unlock did nothing (`PinLockActivity.kt:276`,
  `if (androidAskOpen()) return`; `ANDROID_ASK_OPEN_MS = 30_000`, `HybridPlan.kt:85`).
- The next ask after 30 s gave the empty shade again.
- After a screen off/on, Unlock gave the PIN pad normally.
- The three pending callbacks then all fired `succeeded` at the next unlock (12:26:57, logged three times).

Whether the apply caused it or the first ask in a fresh pause did is not settled from one occurrence.

The kid is stuck on the glance until the screen is turned off and on. Nothing leaked: the shade was empty, and no QS
tile acted.

Fix direction:
- A second "Lås opp" while an ask is open should ask again, or collapse the shade first.
- Possibly hold an apply's lock-task feature write while the PIN pad is opening.

### Minor

- **The "probably Emergency SOS" heuristic** (`PinLockRuntime.kt:1431`) also fires when any non-`showWhenLocked`
  activity takes the front shortly after a screen-on.
- **The first unlock after a reboot** shows the stock Pixel launcher for about 3 s before our Home, then the glance
  for about 1.4 s. The script allows a flash, but the stock launcher in front is worth a look.
- **The PIN pad has no number pad on this AVD.** It is a text field, because the AVD has a hardware keyboard, so the
  pad's look wasn't judged at the Jelly Star's size (`wm density 240` was not used in this run).
