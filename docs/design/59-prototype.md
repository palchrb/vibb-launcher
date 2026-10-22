# 59 prototype - the hybrid behind a debug switch (59 §9 step 7 (ii))

Built 2026-10-22 on branch `proto-59-hybrid` (from `main` `fa33001d`). Launcher only: no server change, no PWA
change, nothing of today's lock deleted. The spec is `59-android-keyguard-assessment.md` (§3, §4, §9) with the
user's answers in `PLAN.md` (design 59 entry): no "Foreldrekode" on the lock for now (2b), Android's PIN pad and
fingerprint allowed (4), Fossify kept (5).

Not run on a device or emulator yet (no emulator reachable from the build machine). Everything §4 marks
[device] or [unverified] is logged under one logcat tag, `Hybrid59`, so the run in §3 answers it.

## 1. What is built

A debug-only switch (`HybridDebug`, prefs `hybrid59_debug`, set by `HybridDebugReceiver`; both only in
`src/debug`). The release `HybridDebug.enabled` is always `false`, so a release build never migrates, never
writes HYBRID, and runs every check below as today (`DebugHookAbsentTest`, `checkReleaseHasNoDebugHook`).

With the switch **on** (`android`):

| | What happens | Where |
|---|---|---|
| Token (59 §9 step 1) | Every apply while the phone has no Android credential sets the reset-password token if it isn't active. It activates at once (no credential). The token is the one `WindowRelockStore` already keeps; no second copy is stored. | `Hybrid59.configured` -> `WindowRelock.ensureResetToken` |
| Migration (59 §9 step 2) | Until then the lock is today's (our keypad). The next correct kid PIN typed on our keypad goes, as the same string, to `resetPasswordWithToken(pin, token, 0)`. It is never stored or logged. Then an apply. The kid sees one ordinary unlock. | `PinLockActivity.check` -> `Hybrid59.migrateIfDue` -> `WindowRelock.resetCredential` |
| The keyguard (59 §3.1, §4.3) | With the credential, `KeyguardCover` (still the only keyguard writer) writes HYBRID: `setKeyguardDisabled(false)`; features + WIDGETS_ALL, SECURE_NOTIFICATIONS, TRUST_AGENTS, SHORTCUTS_ALL (B2's SECURE_CAMERA stays); fingerprint allowed; T = the screen timeout (lock delay 0). The added bits are recorded first, so the rollback removes exactly them. A failed step is logged and never falls back. | `KeyguardCoverPlan.hybridWrite` |
| Our lock stays active as the face | `lockActivation(androidGate)`: a credential no longer switches our lock off (`android_credential`). Every screen-off still starts the glance; under the locked keyguard it starts the lock alone, without Home under it. | `PinLockState`, `PinLockRuntime.lockUpAtScreenOff` |
| "Lås opp" / a row (59 §4.1) | `requestDismissKeyguard` opens Android's PIN pad over the glance. Our keys, dots and "Foreldrekode" are hidden under it. On success the glance leaves through Home and a row's target opens, as today. Cancel returns to the glance. If Android's keyguard isn't locked, the glance leaves at once. If there is no credential now, our own keypad. | `PinLockActivity.askAndroid` |
| Android unlocked by other means | `USER_PRESENT`, or the glance resuming with Android unlocked (`!isDeviceLocked && !isKeyguardLocked` and a live credential) - a fingerprint from dark, or the first unlock after a boot - makes the glance leave (59 §4.4, §4.9). | `Hybrid59.unlockedUs` |
| No re-front (59 §4.2) | While Android's keyguard is locked the re-front loop stops: nothing that isn't `showWhenLocked` can show, and a re-front would fight the PIN pad. The glance comes back at the next screen-off and after our call. With Android unlocked the loop runs as today. | `RefrontInputs.androidGate` |
| Camera (59 §4.9) | The camera lock (25b suspension and `setCameraDisabled`) is off while the glance is the face. Only `KEYGUARD_DISABLE_SECURE_CAMERA` refuses the gesture. | `CameraLock`, `cameraLockEngaged` |
| Lock task (59 §1, §4.7) | Unchanged: lock task while LOCKED, `LOCK_TASK_FEATURE_KEYGUARD` forced (pinned by `LockTaskHelpersTest`, `HybridPlanTest`). OVERVIEW and NOTIFICATIONS stay out while LOCKED. At each resume of the glance the log says whether lock task guards Recents and the shade (`Recents guard: ... guarded=true`). | `LockTaskChrome` (unchanged), `Hybrid59.noteRecentsGuard` |
| Android's own lock screen | A device-owner line, "Skriv koden for å starte telefonen" (`setDeviceOwnerLockScreenInfo`; the user's answer 1 - does it show before the first unlock?). | `Hybrid59.configured` |
| No parent code (answer 2) | "Foreldrekode" is hidden on the PIN-pad stage and on the VoIP ring card. The parent types the kid's PIN. | `PinLockActivity` |

Unchanged on purpose (prototype scope): the VoIP machinery (design 17/17b: our ring card, the lock's own ring, the
FSI send, the call screen sent back from the lock) - so the run shows how a call screen behaves over the secure
keyguard; torch, Nødsamtale, the SOS app pinned, the music tile, the time-rule screen, the boot cover; the server and
the PWA.

With the switch **off** (`own`, 59 §9 step 6): the switch is stored off first, then `resetPasswordWithToken(null)`
clears Android's PIN (the token is active from step 1 - the keyguard may be up), then an apply: DISABLED, the
hybrid's feature bits out, T 0, the lock-screen line cleared - today's lock and keypad. Without an active token, or
if the clear fails, Android's PIN stays the gate and our lock is off (`android_credential`), as for any credential;
design 10's runbook removes it. So the phone is never left without a lock. Send `own` again to retry.

Known gaps (they belong to phase C, not this prototype):
- **A new Child PIN from the PWA doesn't change Android's PIN.** The phone only gets a hash; Android keeps the PIN
  from the migration. To test a new PIN: `own`, then `android`, then type the new PIN once on our keypad.
- A parent window (pause/override) opened in the hybrid doesn't remove the Android PIN at the relock: it was already
  there at the window's start (design 24b step 1 only removes one set inside the window). A PIN the kid *changes* in
  a window stays changed (59 §4.5's "put the parent's PIN back" isn't built).
- Our VoIP ring card and its ringer still run (see above). Design 59 §6 removes them only after this run.
- Remote lock still answers "locked (handy lock)", and the server shows the state as today.
- The notice wake's screen-off (round 7 §3) is `dpm.lockNow()`, which in the hybrid also asks for the PIN, not the
  fingerprint, at the next unlock (`STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW`). Phase C should turn the screen off
  another way.

## 2. Switching

```sh
P=me.vibb.launcher.debug
H=$P/com.kidslauncher.mdm.lock.HybridDebugReceiver
adb shell am broadcast -n $H --es keyguard android   # switch on (stored) + an apply; the next right PIN migrates
adb shell am broadcast -n $H --es keyguard own       # roll back: Android's PIN cleared, today's lock
adb shell am broadcast -n $H                         # just an apply and the report
adb logcat -s Hybrid59                               # the prototype's one tag
```

The broadcast's `data=` line (and logcat) reports `switch=android|own face=true|false secure=... androidLocked=...
token=true|false keyguard: mode=HYBRID|DISABLED features=0x... maxTimeToLock=... disabled=...`.

Don't combine it with §6k's keyguard cover switch (`KeyguardCoverDebugReceiver`): with the hybrid switch on the cover
is never written. And don't use §6e's lock-task override with it - it is the lock-task flags that keep Recents and
the shade away over the keyguard. T turns "Stay awake" off, as with the cover (`adb shell svc power stayon true` after
the rollback where a check needs it).

## 3. Emulator test script

Setup: the debug launcher provisioned as `docs/testing/emulator.md` §4, enrolled, Parent PIN and a 4-digit Child PIN
set (below `1357`), **no** Android screen lock (`adb shell locksettings get-disabled` -> `true`), kiosk on with
Element X and WhatsApp allowed and signed in, Vibb music installed. Gesture navigation (the AVD's default). Keep one
log for the whole run, and send it back at the end:

```sh
P=me.vibb.launcher.debug; H=$P/com.kidslauncher.mdm.lock.HybridDebugReceiver; PIN=1357
adb logcat -c; adb logcat -v time -s Hybrid59 PinLock PinLockActivity KeyguardCover LockTaskChrome CameraLock > hybrid59.log &
read W HGT < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
shot() { adb exec-out screencap -p > "$1.png"; }   # our glance is FLAG_SECURE, so it shows black; anything else shows
```

For each step: what to do, what to expect, and what to send back (**Send**). A screenshot means `shot <name>`. Where
it shows black and you need to see the glance, use `adb emu screenrecord screenshot <path>`.

1. **Switch on.** `adb shell am broadcast -n $H --es keyguard android`.
   Expect `face=false secure=false token=true`, and the log line `apply: switch=on ... token=true face=false - the
   next correct kid PIN becomes Android's`. **Send** the `data=` line. If `token=false`, stop here and send the log.

2. **The first PIN migration.** `adb shell input keyevent 26` twice. The glance comes up as today. Tap "Lås opp":
   **our** keypad, as today. Type `adb shell input text $PIN`. You are in, as with any unlock.
   Expect these log lines: `migration: the kid PIN set as Android's PIN: true (secure now=true, token=true)`, then
   `keyguard: mode=HYBRID features=0x... maxTimeToLock=<the screen timeout> disabled=false`, `face=true (changed)` and
   `lock-screen line: "Skriv koden for å starte telefonen"`.
   **Send** the output of `adb shell locksettings get-disabled` (expect `false`), and
   `adb shell dumpsys device_policy | grep -iE "disabledKeyguardFeatures|maximumTimeToLock|lockScreenInfo"`.

3. **Screen off and on (20 times).** `adb shell input keyevent 26`, then again. Repeat it from an app (WhatsApp) and
   from Home, and once by letting the screen time out.
   Expect our glance at every wake, and never a frame of the app, Home or SystemUI's own lock screen. The log should
   show `screen-off, lock-up LOCK: keyguardLocked=true deviceLocked=true ...` and `the glance resumed:
   keyguardLocked=true deviceLocked=true secure=true interactive=true`. It should also show
   `Recents guard: lockTask=... guarded=true`.
   **Send** any wake that showed something else (what, and how often), and any `keyguardLocked=false` at a lock-up
   after the timeout (that means T didn't take).

4. **The glance as the face.** Send the kid a WhatsApp and an Element X message, and call from an allowed number
   without answering (`adb emu gsm call +4791234567`, then `adb emu gsm cancel 4791234567`). Lock the phone and wake
   it. Expect the glance as today: the clock, the date, the rows (cleaned, sender/text per the parent's choice), the
   missed call, "Lås opp", "Nødsamtale". **Send** a screenshot taken with `adb emu screenrecord screenshot`.

5. **"Lås opp" and Android's PIN pad.**
   - (a) Tap "Lås opp". Expect Android's PIN pad over the glance, with none of our keys, dots or "Foreldrekode" under
     it. Type `adb shell input text $PIN; adb shell input keyevent 66` (4 digits need OK/Enter). Expect Home. The log
     shows `Lås opp: requestDismissKeyguard`, `Android's PIN pad: succeeded` and PinLock `LOCKED -> UNLOCKED`.
   - (b) Lock again and tap a message row, then the PIN. The conversation opens, as today.
   - (c) Lock again, tap "Lås opp", then Back on the PIN pad. Expect the glance (`Android's PIN pad: cancelled`).
   - (d) Tap "Lås opp" and let the screen go off on the PIN pad. At the next wake expect the glance, not the pad.
   **Send** a screenshot of the PIN pad (`adb emu screenrecord screenshot`) and say whether its look is acceptable.

6. **Wrong PINs and Android's lockout.** On the PIN pad, type `0000` + Enter five times. Expect Android's own wait
   (AOSP: 30 s after the 5th). Then type it five more times after the wait (another 30 s after the 10th), then 3 more.
   During a wait, "Nødsamtale" on the glance and the pad's "Emergency call" still work. Then `adb reboot` during a
   wait: is the wait still there after the boot?
   **Send** each wait's length as shown, and `adb shell dumpsys lock_settings | head -40`.

7. **Notifications aren't on Android's lock screen, and Home stays hidden.** With the glance up and the keyguard
   locked, `adb shell am start -n $P/com.kidslauncher.mdm.ui.HomeActivity`. Home isn't `showWhenLocked`, so SystemUI's
   own lock screen should show: the lock wallpaper, the clock, the line "Skriv koden for å starte telefonen", **no
   notification**, and **no Home**. The log shows `the glance stopped: keyguardLocked=true` and `the glance isn't in
   front - no re-front, Android's keyguard guards`. Swipe up: the PIN pad. Type the PIN: Home. The glance leaves too
   (`USER_PRESENT`, `Android's keyguard is gone`).
   **Send** a screenshot of SystemUI's lock screen (`shot sysui-lock`), and say whether any notification, the media
   player or Home was visible.

8. **The music tile.** Play something in Vibb music, then lock. Expect the glance's music tile with working controls
   (pause, play). Then repeat step 7's `am start`, with music playing.
   **Send** whether Android's own media player shows on SystemUI's lock screen, with a screenshot. This is 59 §5's
   question: does `SECURE_NOTIFICATIONS` also hide the player?

9. **The torch.** The emulator has no flash unit, so the glance has no torch button (expected). On the PIN pad and
   SystemUI's lock screen there are no shortcuts (`SHORTCUTS_ALL`). **Send** only if a torch or any lock-screen
   shortcut shows anywhere.

10. **Nødsamtale.** On the glance tap "Nødsamtale", then "Ring 112?", then call. Expect the emulator's emergency
    in-call screen over the glance. End it with `adb emu gsm cancel 112`. Also try the PIN pad's own "Emergency call"
    button: the emergency dialer opens, and nothing else of the phone does.
    **Send** what shows after the call ends (the glance or SystemUI's lock screen), and the log lines around it.

11. **Emergency SOS.** On the glance press power five times quickly: `adb shell input keyevent 26 26 26 26 26`
    (one command, so the presses come close together). Expect the SOS countdown over the glance; cancel it. There is
    no re-front fight (`no re-front` in the log, no `Re-fronting the lock`). **Send** what shows after the cancel.

12. **The camera gesture.** Turn the gesture on for this step:
    `adb shell settings put secure camera_double_tap_power_gesture_disabled 0; adb shell settings put secure double_tap_power_button_gesture_enabled 1`.
    Then on the glance, `adb shell input keyevent 26 26`. Try it with the screen off too.
    Expect no camera, in neither case (`KEYGUARD_DISABLE_SECURE_CAMERA` alone; the log has no `CameraLock` "camera
    apps suspended" and no "Camera disabled by policy").
    **Send** whether a camera opened at all (secure camera or full), and
    `adb shell dumpsys device_policy | grep -i camera`.
    Afterwards turn the gesture off again (§4's two settings).

13. **The shade and Quick Settings under lock task.** On the glance: `adb shell input swipe $((W/2)) 0 $((W/2)) $((HGT/2)) 200`,
    then `adb shell cmd statusbar expand-notifications` and `adb shell cmd statusbar expand-settings`. Repeat the
    same on SystemUI's lock screen (step 7).
    Expect nothing to open, in both places. On SystemUI's lock screen this is 59 §4.7's question: does its keyguard
    shade honour lock task's flags? Run it with the kiosk on and then off (the phone's Apps page: kiosk unpinned).
    **Send** a screenshot of anything that opened, and which Quick Settings tiles acted without the PIN (Airplane
    above all).

14. **Recents and its thumbnails (the user's check, 2026-10-22).** Today a swipe up from the bottom of the lock can
    show Recents, with readable app thumbnails (WhatsApp chats). In the hybrid, nothing that isn't `showWhenLocked`
    may become visible while the keyguard is locked.
    First open WhatsApp on a chat (unlocked), then lock and wake, so a chat thumbnail exists. On the glance (screen
    on, keyguard secure), in **gesture navigation**:
    - (a) Swipe up from the bottom edge: `adb shell input swipe $((W/2)) $((HGT-2)) $((W/2)) $((HGT/2)) 150`.
    - (b) Swipe up and hold: `adb shell input swipe $((W/2)) $((HGT-2)) $((W/2)) $((HGT*2/3)) 1500`.
    - (c) Do (a) and (b) by hand on the emulator too: a held swipe is how Recents opens.

    Then switch to **3-button navigation** (`adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton`),
    wake to the glance, and repeat (a) and (b). Then:
    - (d) the Recents key: `adb shell input keyevent KEYCODE_APP_SWITCH`, and a tap on the square button.

    Switch back with `adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural`. Repeat the whole
    step with the kiosk off.
    Expect: no Recents and no thumbnail, ever. At most Android's PIN pad (a swipe up on a secure keyguard). After each
    try, `shot recents-<n>` should be black (our `FLAG_SECURE` glance) or the PIN pad, never a thumbnail. The log's
    last `Recents guard:` line should say `guarded=true`; a `guarded=false` warning means lock task wasn't holding
    OVERVIEW off.
    **Send** after each try: `adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity"`
    and the screenshot.

15. **A reboot (BFU).**
    - (a) `adb reboot`. Expect Android's own lock screen first, with no glance: the lock wallpaper, maybe the "Skriv
      koden..." line, and Android's restart prompt for the PIN. `adb shell dumpsys user | grep -iE "State|RUNNING"`
      should show `RUNNING_LOCKED`.
    - (b) Before typing the PIN, call from a **blocked** number (one not in the phone book, or during a no-calls
      rule): `adb emu gsm call +4799999999`. Expect it rejected without any UI.
    - (c) Call from an allowed number. Expect our call screen (the silhouette) over Android's lock; then
      `adb emu gsm cancel ...`.
    - (d) Type the PIN on Android's pad. Expect Home. The glance may flash once and leave (`process start: switch=on
      face=true ... androidLocked=false`, `Android's keyguard isn't locked at the glance's resume - the glance
      leaves`). Then lock and wake: the glance.
    **Send** for (b): `adb logcat -b all -d | grep -iE "KidCallScreening|CallScreening|InCallController"`. Say whether
    (b) rang or showed anything, what (a) showed (with a screenshot), and whether the device-owner line was there
    before the first unlock.

16. **A VoIP call over the lock (Element X, then WhatsApp).** Lock the phone, screen off, and call the kid from a
    second account.
    Expect the app's call screen over the keyguard (Element X's is `showWhenLocked`), or our ring card on the glance.
    Answer, talk, then hang up: the glance comes back (or SystemUI's lock screen until the next screen-off).
    During the call, try to reach the chats: the back arrow, the app's "minimize"/chat button, Back. Expect Android's
    PIN pad, **never the chat list or a chat**.
    Repeat with WhatsApp. Its call screen may not be `showWhenLocked`, so expect SystemUI's lock screen or the PIN pad
    instead of the call screen; the call itself should still go on.
    **Send** for each app what showed at the ring, after Answer and after hang-up, with screenshots (`shot voip-*`),
    and the log lines `VoIP NONE -> RINGING (<package>)` and `the glance stopped: ...`.

17. **Fingerprint.**
    - (a) Enroll one in a parent window: open the phone (launcher Settings -> pause restrictions with the Parent PIN,
      or the PWA's 2-hour pause), then Settings -> Security -> Fingerprint. It asks for the PIN; then send
      `adb emu finger touch 1` until it is enrolled. End the window with "Lås igjen" (Home's banner). The relock must
      **not** remove the Android PIN: the log shows no `Removing the Android screen lock`, and
      `adb shell locksettings get-disabled` -> `false`.
    - (b) Screen off, then `adb emu finger touch 1`. Expect Home directly, with no glance or just a flash
      (`USER_PRESENT`, `Android's keyguard is gone`).
    - (c) Screen on with the glance up, then `adb emu finger touch 1`. Expect nothing: the sensor isn't listened to
      while the glance occludes the keyguard (59 §4.9). An unlock is also acceptable; say which.
    - (d) On Android's PIN pad (after "Lås opp"), `adb emu finger touch 1`. Expect an unlock.
    - (e) Remote lock (the PWA's Locate -> Lock), then wake, "Lås opp", `adb emu finger touch 1`. Expect it refused:
      the PIN is required after `lockNow`.
    **Send** what each one did.

18. **Remote lock.** Locate -> Lock from the PWA. Expect the screen off, and at the wake the glance. "Lås opp"
    opens the PIN pad. **Send** the Recent row's answer (expect "locked (handy lock)"; unchanged in the prototype).

19. **Rollback to "own".**
    - (a) With the glance up and the keyguard locked, send `adb shell am broadcast -n $H --es keyguard own`.
      Expect `own -> switch=own face=false secure=false ... keyguard: mode=DISABLED features=0x2 maxTimeToLock=0
      disabled=true`, and the log lines `rollback: Android's PIN cleared: true` and `lock-screen line: cleared`.
    - (b) Lock and wake. Expect today's lock: the glance, then **our** keypad after "Lås opp", and `$PIN` opens it.
      `adb shell locksettings get-disabled` -> `true`.
    - (c) Repeat (a) with the phone unlocked, after switching on and migrating again (steps 1-2).
    - (d) Then switch on again and leave it on for any later check.
    **Send** the `data=` lines and `adb shell dumpsys device_policy | grep -iE "disabledKeyguardFeatures|maximumTimeToLock"`.

At the end: `kill %1` and **send** `hybrid59.log`, the screenshots, and for each step a line: as expected / what was
different.

## 4. What the run decides

The top questions, in the order they would block phase C:
1. **The glance as the face at every wake** (steps 3, 14). Does a `showWhenLocked` activity started at `SCREEN_OFF`
   occlude the secure keyguard from the first frame? And do Recents, the shade and Quick Settings stay closed over
   it?
2. **No escape through un-occlusion** (steps 7, 13, 16). Home, chats or notifications must never show while
   Android's keyguard is locked. Does SystemUI's keyguard honour lock task's status-bar flags?
3. **`isKeyguardLocked`/`isDeviceLocked` while occluded** (the `the glance resumed: ...` lines). The glance leaves
   without a PIN only when both say unlocked. Both must read `true` over a locked keyguard. If either reads `false`
   there, stop: that would be a way past the PIN.
4. **BFU calls** (step 15b): the blocked number must be screened before the first unlock.
5. **VoIP** (step 16): which call screens show over the keyguard, and whether anything reaches a chat.
6. **The camera gesture and SOS while occluded** (steps 11, 12).
7. **Android's lockout and the PIN pad's look** (steps 5, 6), the media player and the device-owner line on
   SystemUI's lock screen (steps 7, 8, 15).
