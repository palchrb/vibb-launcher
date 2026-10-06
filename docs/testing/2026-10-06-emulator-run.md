# Emulator test run 2026-10-06 (Android 16 AVD, 480x854 @ 240 dpi, debug build of master)

Driven remotely over the tailnet (adb server + emulator console via socat).

| Test | Result |
|---|---|
| Home: new layout (no clock, colored circles, Nunito, Settings tile) | OK — too much space between status bar and contacts |
| Kid settings: wallpapers, Wi-Fi/Bluetooth switches | OK (quick-controls "empty" bug gone) |
| Phone book grid, contact sheet | OK, matches mockup |
| Incoming unknown (+4799999999) | Rejected by KidCallScreeningService |
| Incoming allowed (Pappa +4793613444) | Allowed, our incoming screen shown |
| Answer, Back during call, hang up | OK; Back keeps the call screen; returns to Home/lock |
| Outgoing to non-allowed (+4712345678) | "Canceled from Call Redirection Service" |
| Outgoing to Pappa | Allowed by redirection; emulator modem then disconnected it (emulator, not us) |
| PIN lock on screen off/on | Shown, kiosk LOCKED |
| 5 wrong PINs | "Try again in 0:29"; correct PIN refused during backoff, accepted after |
| Incoming allowed call over the lock → answer → Back → hang up | Call screen over lock; back on the PIN lock after hang-up |
| Emergency button on the lock | "Call 112?" confirm → system emergency in-call UI shown over the lock (kiosk block on), hang-up returns to the lock |

## Bugs / changes found
1. PIN keypad doesn't fit on 854 px: rows 7-8-9 and 0 are cut off (PIN with those digits can't be entered). Too much space above the clock/keypad.
2. Home: too much space between status bar and the contacts row.
3. Our incoming/ongoing call screens are covered at the top by the call notification as a heads-up ("Incoming call"/"Ongoing call"), hiding the call duration; it should be silent while our screen is in front.
4. No auto-lock: `screen_off_timeout` is "never" on the emulator; the parent should set the screen timeout per device in the PWA (device owner `setSystemSetting(SCREEN_OFF_TIMEOUT)`), which also drives our PIN lock.
5. Simulated calls must use `+47…` numbers (documented in emulator.md).
