# Android's own settings on the kid's phone

A few of Android's settings stay as Android has them, because an app (Vibb One included) can read them but not
set them back. Set them before enrolling; the PWA can't fix them later.

## Touch feedback stays on (launcher round 9 §4)

Android Settings -> Sound & vibration -> Vibration & haptics -> **Touch feedback: on** (the default).

Since Android 13 that toggle writes two settings: `HAPTIC_FEEDBACK_ENABLED`, which Vibb One's "Vibrasjon ved trykk"
switch (kid Settings, shown when the parent ticks "Touch sounds and vibration" on Home Screen) writes, and the hidden
`HAPTIC_FEEDBACK_INTENSITY`, which no app may write. With Touch feedback turned off in Android's Settings, the kid's
switch shows on while the phone stays without tap vibration - the switch can't bring it back.

## "Modify system settings" for Vibb One

The touch row's two switches and the PWA's clock format (design 34 P12) need the "Modify system settings" app-op; a
device owner can't grant it to itself. With adb, before USB debugging is blocked:

```sh
adb shell appops set me.vibb.launcher WRITE_SETTINGS allow
```

Or on the phone, during a pause: Android Settings -> Apps -> Special app access -> Modify system settings -> Vibb One.
Without it the kid's touch switches are read-only ("Kan ikke endres på denne telefonen ennå") and the PWA warns
"Clock format not applied".
