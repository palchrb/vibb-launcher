# 2026-10-23 - design 59d §8.9: which input consumer takes the bottom-edge swipe over the glance

The user's third recording (rc.2, kiosk on, android mode, music playing): three swipes up on the glance, each time the
glance slides away upwards, SystemUI's face shows (the sharp photo, the clock, the media card), a black frame, the
glance is back - about half a second each. §8.9 asks: (a) lock task's flags out of sync, so the 16d heal is the place,
or (b) Quickstep's `DeviceLockedInputConsumer` un-occludes whatever the flags say, so a gesture loss should ask for the
pad at once.

**Verdict: (a).** With lock task's flags in sync Quickstep refuses the edge swipe over the locked glance - its consumer
is `TYPE_RESET_GESTURE` (`canStartSystemGesture=false`, `home_disabled`), nothing moves, the glance keeps the focus - in
every run: kiosk on (three runs, one after a full unlock and re-lock) and kiosk off (the face roots lock task itself
while Android is locked, step A). `DeviceLockedInputConsumer` is picked only when the Home disable is missing: with
HOME left in the LOCKED features (the debug override, standing in for 16d's desync of SystemUI's TaskbarDelegate) the
same swipe reproduced the user's frames exactly - and the mechanism shows in the log: the consumer **starts Home**
(`ActivityStartInterceptor: Starting home with component specified, uid=10182` - Quickstep), the keyguard un-occludes,
our Home's locked resume puts the lock back 20 ms later, the keyguard is occluded again ~0.8 s later. So the user's
swipes went through because SystemUI's copy of lock task's flags lacked `home_disabled` at that moment.

**Built** (59d §8.11): a Home start that isn't ours (`HomeFront.EXTRA_BROUGHT`) while our lock is LOCKED with the screen
on is the desync's proof - `HomeActivity.onNewIntent` -> `PinLockRuntime.homeIntent` -> pure `systemHomeHealDue` ->
the 16d heal at once (`HealTrigger.SYSTEM_HOME`, at most every 5 s), so the next swipe is refused. The first swipe's
flash stays: an app can't read SystemUI's flags before it (16d decision 1). (b)'s "ask for the pad after a gesture
loss" is not built: its premise (the consumer un-occludes whatever the flags) is false on this build.

## Setup

- AVD `handy36` (`-wipe-data -no-window -no-audio -gpu swiftshader_indirect`, port 5556 - another agent's `six35` ran
  beside it), `wm density 240`, the debug launcher of branch `d59d-jl` (steps J and L), provisioned per
  `docs/testing/emulator.md` §4, enrolled with a local server (port 3110; the server binary of 2026-10-23 07:51, the
  `d59d` target - no server change since that matters here), the PWA scripted over HTTP: Parent PIN, Child PIN `1357`,
  calls managed with one contact, Messages and Chrome allowed (kiosk on), then the Lock row to Android's lock (`face=true
  (changed)`). Kiosk off for one run by `kiosk_desired = 0` in the server's database and a policy nudge.
- Gesture navigation (`navigationBars` 36 px, `mandatorySystemGestures` bottom 48 px). Status bar 136 px on this AVD
  (the pixel_7 skin's cutout spec at 480x854).
- Each run (`edge.sh`): screen off, on (the glance up), Quickstep's
  `dumpsys activity service com.google.android.apps.nexuslauncher/com.android.quickstep.TouchInteractionService` and
  lock task before, a 24 fps console recording, `input swipe 240 850 240 400 150`, the focus every 0.4 s, the same dumps
  after; logcat main/system/events with epoch times.
- No music this run (the music app's recipe needs its own server data; it plays no part in Quickstep's choice).

## Evidence

Flags in sync, kiosk on (`kiosk-on-1`, `-2`, and `-3` after an unlock through the pad and a re-lock), and kiosk off
(`kiosk-off-1`) - the same in all four:

```
mLockTaskModeState=LOCKED
mDisabled1=0x7260000 mDisabled2=0x15 taskbar mDisabledFlags=119930880 (= 0x7260000)
canStartSystemGesture=false
systemUiFlagsDesc=overview_disabled|home_disabled|keygrd_occluded|allow_gesture|awake
setInputConsumer: TYPE_RESET_GESTURE. reason(s):
  -> cannot start system gesture and recents animation was not running, trying to use default input consumer;
     ResetGestureInputConsumer available, using ResetGestureInputConsumer
mCurrentFocus=Window{... me.vibb.launcher.debug/com.kidslauncher.mdm.lock.PinLockActivity}   (all six samples, 2.6 s)
```

69 frames at 12 fps, every one the glance unmoved (`kiosk-on-1`).

HOME left in the LOCKED features (`LockTaskOverrideReceiver --ei add_features 4`, features 53 instead of 49; kiosk off):

```
canStartSystemGesture=true
systemUiFlagsDesc=overview_disabled|keygrd_occluded|allow_gesture|awake
setInputConsumer: TYPE_DEVICE_LOCKED:TYPE_NAV_HANDLE_LONG_PRESS. reason(s):
  -> can start system gesture, trying to use base consumer; keyguard is showing occluded, trying to use device locked
     input consumer; device is in gesture nav mode ... using DeviceLockedInputConsumer
768.070 InputDispatcher: Channel [Gesture Monitor] swipe-up is stealing input gesture ... from [PinLockActivity]
768.074 ActivityStartInterceptor: Starting home with component specified, uid=10182
768.076 wm_new_intent: [... HomeActivity, android.intent.action.MAIN ...]
768.308 wm_pause_activity: [... PinLockActivity, userLeaving=true ...]
768.329 wm_new_intent: [... PinLockActivity ...]              (Home's locked resume shows the lock)
768.367 AndroidLock: the glance resumed: keyguardLocked=true deviceLocked=true ...
769.132 wm_set_keyguard_occluded: [1,0,0,setOccluded]
focus: PinLockActivity, NotificationShade, NotificationShade, PinLockActivity, ...
```

Frames (12 fps): the glance moving up (2 frames), SystemUI's face with the large clock (3 frames), one dark frame, the
glance again - the user's recording.

The build, on the same simulated desync (a second run after a fresh provision): the system's Home start is caught and
heals at once -

```
832.298 PinLock: The system's Home while LOCKED (a home gesture got through): lock task's flags healed
832.348 LockTaskChrome: Status bar heal (the system's Home while locked): lock-task features 117 -> 116 written, back in 250 ms
```

(the override keeps writing HOME, so here the next swipe still goes through - on a real desync the flip re-sends the
whole set and SystemUI's copy has `home_disabled` again).

Not reproduced here: a natural desync. Killing Quickstep (`am crash`) left the flags in sync; 16d's experiment 2 saw it
after some boots. The heal's effect on a real desync is 16d's (a SYSTEM_INFO flip re-sends the whole set to SystemUI;
experiment 2 and `smoke-test.sh REBOOT=1`).

Recordings, frames and dumps stayed in the scratchpad.
