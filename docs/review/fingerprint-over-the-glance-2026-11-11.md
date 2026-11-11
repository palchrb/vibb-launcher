# The fingerprint while the glance is lit - a read of AOSP (2026-11-11)

A source read, no code. Android mode (design 59): Android's keyguard with the Child PIN is the gate, the glance
(`lock/PinLockActivity`) is a `showWhenLocked` activity over it. With the glance lit, a finger on the sensor does
nothing. The question: can the glance make the fingerprint work while it is lit, without weakening the lock?

**Short answer: no.** On Android 12 to 16 there is no call, flag, intent or Device Owner right that makes SystemUI
listen for a fingerprint under an ordinary app's occluding activity when the sensor is not under the display. What
stock Android does give is the power key itself: a press turns the screen off, and from that moment the keyguard
listens. Whether that is one move on the Jelly Star, only the phone can show.

## What was read

AOSP `platform/frameworks/base`, branches `android12-release`, `android13-release`, `android14-release`,
`android15-release`, `android16-release`, with single files from `android13-qpr1-release`, `android13-qpr3-release`,
`android14-qpr1/2/3-release`. "KUM" is `packages/SystemUI/src/com/android/keyguard/KeyguardUpdateMonitor.java`.
"Read" means the lines were read; "inferred" is marked. Nothing here says anything about Unihertz's SystemUI.

The rule itself, KUM `shouldListenForFingerprint` (12: 2231-2239, 13: 2491-2527, 14: 3056-3064, 15: 2824-2837,
16: 3017-3030). On 13:

```
mKeyguardIsVisible || !mDeviceInteractive || (mBouncerIsOrWillBeShowing && !mKeyguardGoingAway)
    || mGoingToSleep || shouldListenForFingerprintAssistant || (mKeyguardOccluded && mIsDreaming)
    || (mKeyguardOccluded && userDoesNotHaveTrust && (mOccludingAppRequestingFp || isUdfps))
```

13-qpr3 and later add `|| mAlternateBouncerShowing`; 14-qpr3, 15 and 16 add
`|| mAllowFingerprintOnCurrentOccludingActivity`.

## The routes

**1. Asking SystemUI to listen under the glance - does not work (12-16, read).**
`requestFingerprintAuthOnOccludingApp` (KUM 12: 642, 13: 671, 14: 848, 15: 811, 16: 847) has one caller,
`StatusBarKeyguardViewManager.requestFp` (12: 1262, 13: 1394, 14: 1525, 15: 1776, 16: 1798), and that has one
caller: SystemUI's own `wallet/ui/WalletActivity` in `onResume`/`onPause` (12: 205-220, 13: 204-219, 14: 208-219,
15: 212-222, 16: 212-226). It is a call inside the SystemUI process. The face twin (`requestFaceAuthOnOccludingApp`,
12-14, gone from 14-qpr3) has the wallet and the under-display sensor's controller as callers. Only SystemUI's `src`
was searched. Nothing reaches it from outside: `IKeyguardService.aidl` (12-16) carries only `setOccluded`, and
`KeyguardManager.java` (12-16) has no fingerprint method at all.
`mAllowFingerprintOnCurrentOccludingActivity` (14-qpr3: 3745-3762, 15: 3748-3765, 16: 4007-4024) is true when the top
activity's package is in SystemUI's resource `config_fingerprint_listen_on_occluding_activity_packages` - empty in
AOSP, present from 14-qpr1, absent on 12, 13 and 14's first release. It is set by whoever builds the system image; an
app or a Device Owner cannot. It would be the right switch for the glance, on a phone we do not have.

**2. The assistant and dream exceptions - not for us (12-16, read; one step inferred).** The rule also listens while
an assistant-type activity is on top (KUM 12: 2212-2217, 13: 2471-2476), once per showing. That activity type is given
only to the phone's active voice interaction service (`ActivityRecord.canLaunchAssistActivity`, 12: 2390-2395, 13:
2814-2819, 16: 2862-2867). The launcher would have to be the phone's assistant app and show the glance as its
session's activity: a borrowed exception, heavy and fragile. Not recommended. The dream type is given only to the
system's own `DreamActivity` (13: 2842-2845); that only the system starts a dream is inferred.

**3. Counting as "keyguard visible" without the glance leaving - does not work (13 and 16, read).** Any top
`showWhenLocked` activity occludes (`wm/KeyguardController.java`, 13: 596-616); there is no half state. Dropping
`showWhenLocked` for a moment would show Android's own lock screen at every wake: a blink, not offered.

**4. The alternate bouncer and `requestDismissKeyguard` - today's path, no more (12-16, read).** On 12 and 13 the
alternate bouncer exists only for under-display sensors (`setAlternateAuthInterceptor` is called only from
`UdfpsKeyguardViewController`, 12: 116, 13: 137/162), so on the Jelly Star the ask shows the PIN pad, and the sensor
listens there (the bouncer term). That is what the swipe up does today. On 14 a side sensor has an alternate bouncer
too (`SideFpsController.kt` 176-187), on 15 behind a flag, on 16 for under-display and power-button sensors
(`bouncer/domain/interactor/AlternateBouncerInteractor.kt` 66-69): the ask then shows "touch the sensor" before the
pad (`StatusBarKeyguardViewManager.java` 16: 781-794, 902-917). In every release it still takes the ask. Asking at
every wake puts Android's pad over the glance at every wake - ruled out.

**5. A fingerprint read by the launcher itself - works technically, not recommended (13, 14 and 16 read).**
`FingerprintService.authenticate` lets a foreground app authenticate while the phone is locked (13: 284-356,
`canUseFingerprint` 1134-1165; no "keyguard locked" check was found under `services/.../biometrics` on 13 or 16). The
strong-auth check after a boot or `lockNow` applies only to the keyguard's own client (13: 315, 16: 298). So an
app-side match says less than Android's own: by the source it can come after a remote lock, when Android itself
refuses the finger until the PIN (after a reboot, before the first PIN, was not read).
It unlocks nothing, and must never be turned into an unlock - the reset token after such a match is exactly the
insecure fallback the project forbids, and is not proposed. The one honest use is as "a finger is here", to make the
same ask the swipe makes. The costs: Android's pad appears at every stray touch of the power key; the kid must touch
a second time; wrong fingers count toward the keyguard's own lockout (`hidl/LockoutFrameworkImpl.java` 13: 103-123);
the app's session and the keyguard's cancel each other (`BiometricScheduler.java` 13: 372-399); and from 14 a side
sensor turns the silent call into the system's fingerprint dialog (`FingerprintService.java` 14: 321-327, 16:
331-337), during which a power press is swallowed (`SideFpsEventHandler.java` 14: 128-169). A second touch and a pad
that pops up by itself is worse than the swipe.

**6. The power key - stock Android does it; device only.** With the glance lit, a power press starts going to sleep:
`handleStartedGoingToSleep` sets `mGoingToSleep` and updates the listening (KUM 13: 1762-1774), and once asleep
`!mDeviceInteractive` holds. A match that arrives while going to sleep is kept and played when sleep is finished, as
wake-and-unlock (`statusbar/phone/BiometricUnlockController.java`, 12: 351-354 and 498-507, 13: 389-414 and 745-756,
14: 396-402 and 819-829, 15: 423-429 and 837-847, 16: 429-435 and 846-856). The launcher then hears `USER_PRESENT`
and leaves, as from dark today. So by stock source: **press the power key with the enrolled finger and keep it
there - the screen goes dark, then wakes unlocked.** The other direction explains what the user sees: waking updates
the listening (`handleStartedWakingUp`, KUM 13: 1748-1760), and under an occluding activity that stops it. A press
from dark that wakes the screen before the match lands ends on a lit glance with a deaf sensor.
What differs per release is the power-press filter for side sensors: none on 12 and 13's first release; on 13-qpr1 a
match waits 300 ms for a power press and is dropped if one comes, and matches are ignored for 400 ms after a press
(`services/.../fingerprint/aidl/FingerprintAuthenticationClient.java` 222-254, 477-494); from 13-qpr3 to 16 that hook
is empty in AOSP and left to the vendor ("rest to unlock", `FingerprintInteractiveToAuthProvider`). How a power press
reaches the 13-qpr1 hook was not read.
Not knowable from AOSP: which 13 the Jelly Star is built from, whether its HAL reads a finger that was already
resting when listening started, what MediaTek and Unihertz do at a power press, and what sensor type the HAL reports.

**7. The glance's side at its own notice wake - nothing honest to build.** The glance knows its notice wake, but it
has no public way to turn the screen off again except `lockNow`, which makes the fingerprint wait for the PIN
(design 59 §4.9). It already leaves that wake to the screen timeout as the face (`PinLockRuntime.noticeWakeOffCheck`,
1626-1650). Once dark, the finger works.

## The launcher today (read only)

- The ask: `PinLockActivity.askAndroid()` (367-385) calls `requestDismissKeyguard`, from the tap or swipe
  (`tapAsFace`, `askAfterGesture`); the steps are `faceStep` in `AndroidLockPlan.kt` (92), which leaves only on
  Android's own verdict.
- The unlock: `PinLockRuntime.userPresentReceiver` (1036-1052) hears `USER_PRESENT`, calls `AndroidLock.userPresent()`
  and steps `FaceTrigger.USER_PRESENT`; `ANDROID_UNLOCK_WAIT_MS` (3 s) covers a fingerprint from dark.
- The relock: `AndroidLock.relock()` (`AndroidLock.kt` 404-436) is `lockNow`.

Nothing there needs to change for route 6: a wake-and-unlock from the power key arrives as the unlock from dark does.

## Recommendation

1. Build nothing for "the finger while the glance is lit". No honest route exists on the Jelly Star's Android 13;
   the one real switch (route 1's package list) belongs to the phone's maker.
2. Check route 6 on the Jelly Star (below). If press-and-keep works, the answer for the kid is a habit, and at most
   one calm hint line on the glance when a fingerprint is enrolled (launcher: a string in nb and en and a line in the
   glance's layout, size S) - to be offered to the user first, not built unasked.
3. If it does not work on the phone, the swipe up and then the finger stays the way, and the glance stays the face.

## The checks

Emulator (Android 16, `adb emu finger touch 1`), to confirm the read, not the phone: glance lit + touch -> nothing;
screen off + touch -> Home; glance lit, power key, touch at once and again a second later -> Home both times; swipe
up + touch -> Home. The emulator's sensor is not a power key, so it cannot show press-and-keep.

Jelly Star, with an enrolled finger and the glance as the face:

- `dumpsys fingerprint`: the sensor type the HAL reports (power button, side or rear) and HIDL or AIDL.
- Glance lit, press the power key with the enrolled finger and keep it there: dark, then Home in one move? If not:
  lift and touch again while dark.
- From dark, press with the enrolled finger: Home, or a lit glance with a deaf sensor? Then the same with a touch
  that does not click the key.
- A notice wake, then the same press-and-keep.
- After a reboot and after a remote lock: the finger must be refused until the PIN, on every path above.
