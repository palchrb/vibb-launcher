# Android's own settings on the kid's phone

A few of Android's settings stay as Android has them, because an app (Vibb One included) can read them but not
set them back. Set them before enrolling, or on the phone during "Pause all restrictions"; the PWA can't fix them.

## Touch feedback stays on (launcher round 9 §4)

Android Settings -> Sound & vibration -> Vibration & haptics -> **Touch feedback: on** (the default).

Since Android 13 that toggle writes two settings: `HAPTIC_FEEDBACK_ENABLED`, which Vibb One's "Vibrasjon ved trykk"
switch (kid Settings, shown when the parent ticks "Touch sounds and vibration" on Home Screen) writes, and the hidden
`HAPTIC_FEEDBACK_INTENSITY`, which no app may write. With Touch feedback turned off in Android's Settings, the kid's
switch shows on while the phone stays without tap vibration - the switch can't bring it back.

## "Modify system settings" for Vibb One

The touch row's two switches and the PWA's clock format (design 34 P12) need the "Modify system settings" app-op; a
device owner can't grant it to itself. Turn it on from the phone:

1. Vibb One's Settings (the unlock code) -> **"Pause all restrictions"** ("Sett alle begrensninger på pause") -> the
   unlock code.
2. The row **"Modify system settings"** ("Endre systeminnstillinger") under "Background connection": tap it. Android's own page for
   Vibb One opens (where the phone has no such page for one app, Android's list of apps; then pick Vibb One).
3. Turn on **"Allow modifying system settings"**, go back - the row now says "Allowed" ("Tillatt") - and switch
   the pause off again.

Outside a pause the row says to pause first (Android's Settings is hidden while the phone is managed); if the phone
has no such page at all, the row says so. The phone's About This Phone page ("Modify system settings") shows the
grant, with these steps while it is missing.

The shortcut with adb, before USB debugging is blocked:

```sh
adb shell appops set me.vibb.launcher WRITE_SETTINGS allow
```

Without it the kid's touch switches are read-only ("Kan ikke endres på denne telefonen ennå") and the PWA warns
"Clock format not applied" (when the parent chose 24 h or 12 h) and shows "Modify system settings · Not allowed".

## Fingerprint (android mode)

With Android's lock screen as the gate (design 59) the kid's fingerprint is Android's own, enrolled against the Child
PIN. Vibb One's Settings (the unlock code) -> **"Pause all restrictions"** -> the row **"Fingerprint"**
("Fingeravtrykk", under "Modify system settings"): tap it, type the Child PIN when Android asks, and follow Android's
enrolment; then end the pause. The row shows only while android mode is on, Android's PIN is the Child PIN Vibb One set (not one changed on the phone), and the phone has a
fingerprint sensor; it says "Registered" / "Not registered" ("Registrert" / "Ikke registrert") when Android can tell.
No adb is needed. Removing Android's PIN by hand also deletes the fingerprint (design 59d §8.4) - enrol it again here.

## The lock screen's clock: single line (android mode)

Android's own lock face (seen for a frame at a wake, and before the first unlock after a reboot) shows a large
two-line clock by default, on AOSP and Pixel alike. Vibb One's glance copies Android's **one-line** clock (design 59d
step L), so the frame before it matches only with the one-line clock on. Set it once per phone, by hand, during a
pause: Settings › Display › Lock screen › "Double-line clock" off (the Secure setting
`lockscreen_use_double_line_clock` = 0; a device owner can't write it). Measured 2026-10-23 on a pure AOSP Android 16
emulator at the Jelly Star's profile (`docs/testing/2026-10-23-aosp-lock-look.md`): with it off the wake frame
matches the glance's clock within 1 px; with it on, one frame of the stacked clock shows. Confirm the setting's
place and default on the Jelly Star.
