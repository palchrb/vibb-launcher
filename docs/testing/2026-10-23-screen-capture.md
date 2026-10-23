# Screen capture switch on the emulator (2026-10-23)

Branch `screen-capture` (commit `feb35369`). AVD `handy36` (Android 16, API 36), `-wipe-data`, `wm density 240`. The
debug launcher from the branch was provisioned with adb (`docs/testing/emulator.md` §4) and enrolled from its Settings
with a typed code. The server was the branch's debug build on port 3197 with a fresh `data/`. In the PWA, Block USB
debugging was off, a Parent PIN was set and the kiosk was on (the default). The PWA forms were driven with Playwright.

## Results

| Check | Result |
|---|---|
| The policy carries the key, default off | The cached policy's `hardening` ends `"disallow_config_locale":true,"disallow_screen_capture":false` |
| Switch on: the launcher applies it | `AppEnforcer: Screen capture disabled by policy`, about 4 s after the save |
| `dumpsys device_policy` | `DefaultPolicyKey screenCaptureDisabled`, our admin `BooleanPolicyValue { mValue= true }`, `Resolved Policy (MostRestrictive): true`; `Screen capture disallowed users: [-1]` |
| `adb exec-out screencap -p` | Black: 855 non-zero bytes of 1.64 M, the same as a dark screen. With the switch off it was about 33 000 |
| The phone's own screenshot (`KEYCODE_SYSRQ`) | Refused: `Screenshot: Skipping screenshot because an IT admin has disabled screenshots on the device`, and SystemUI's toast (`NotificationService: Toast already killed. pkg=com.android.systemui`). No file was saved |
| Recents | A card taken before the switch kept its picture ([`recents-old-card.png`](screens/screen-capture/recents-old-card.png)). After Clock was opened again and left, its card was blank ([`recents-new-card-blank.png`](screens/screen-capture/recents-new-card-blank.png)). Overview's Screenshot/Select actions were greyed out |
| A pause does not lift it | The PWA's "Unlock for 2 h" (`start_pause`) ran (`mdm.restrictions_paused=true`). `screenCaptureDisabled` was still `true`, screencap was still black and `KEYCODE_SYSRQ` was still refused with the same line |
| Switch off | `AppEnforcer: Screen capture enabled by policy`, `Screen capture disallowed users: []`. Screencap worked again (29 787 non-zero bytes). `KEYCODE_SYSRQ` saved `Screenshot_20261023-183258.png` (`Screenshot: Saved screenshot: Result{uri=content://0@media/external/images/media/20 ...`) |
| The PWA row | [`pwa-restrictions-on.png`](screens/screen-capture/pwa-restrictions-on.png) (390 px): "Screenshots and screen recording off" is last in the first group, on. At this width the label wraps to two lines |

The emulator console's screenshot (`adb emu screenrecord screenshot`) still works with the switch on, because it
reads the host's frame. The Recents images above were taken that way.

## Seen on the way, not caused by the switch

After the PWA's pause, with the kiosk on, the phone showed "App is not available - Vibb One (debug) is not available
right now" (`BlockedAppActivity`) over the stock launcher. `LockTaskController` was `LOCKED` with
`mLockTaskPackages u0:[]`. The events log showed `wm_finish_activity ... HomeActivity, clear-task-all` 14 s after the
command, then `wm_create_activity ... BlockedAppActivity`.

The same happened again with the switch off. In that state `setScreenCaptureDisabled` is never called (the value
doesn't differ), so the switch is not the cause. One Back ended it (`mLockTaskModeState=NONE`), and Home brought
Vibb One back.

Before the pause, Overview had been opened several times while the kiosk was on. This matches the known F1 in
`2026-10-22-d59-rc-run.md` (a stale `BlockedAppActivity` task keeps a pause in `LOCKED`). Here, though, the task was
`type=home`, not `type=recents`.

## Not checked

Our own lock screen, the glance and the boot cover with the switch on (no Child PIN was set). They already guard
themselves (the lock's `FLAG_SECURE`, Home's and Settings' Recents screenshots off), and the switch doesn't change how
they draw. Casting and screen recording were not tried.
