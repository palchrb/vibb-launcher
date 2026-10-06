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

## Fix round 2026-10-06 (design note)
Guarantees of docs/design/01–10 unchanged: emergency call stays reachable first, fail closed,
kiosk and PIN lock as before, calls only through our screening/redirection.
1. **PIN lock fits 320×569 dp**: less top padding/margins, clock 48 sp, 8 dp key gaps; keys are
   sized from the keypad's measured height (min 48 dp, max 72 dp) and applied with
   `setLayoutParams` (the old code mutated params without relayout, so 72 dp keys were clipped).
   Below 48 dp per key the date hides, then the clock shrinks. Emergency call and "Parent code" are
   outside the weighted keypad, so always visible. Unit test parses the layout XML and checks the
   budget for 320×569 dp with a 48 dp status bar and 24 dp gesture bar.
2. **Home**: smaller top padding above the contacts row (Main mockup).
3. **Call notification**: two channels. While `InCallActivity` is visible the CallStyle notification
   is posted on a new IMPORTANCE_LOW channel `launcher:calls_silent` (no heads-up, no sound; the
   full-screen intent stays because Android only accepts CallStyle with one, but it is inert below
   HIGH). When the screen leaves (Home, screen off) it goes back to the HIGH channel with the
   full-screen intent; that re-post alerts only if the screen is on. Timer stays at the top of our
   screen, now uncovered. Also fixes the notification id clash (calls and install mode both 1004).
4. **Auto-lock**: policy `screen_timeout_seconds` (15/30/60/120/300/600, default 60, per device on
   the PWA device page; migration). Launcher: `null` (older server) = leave the setting alone; other
   values clamp to 15–600 s. Applied with `DevicePolicyManager.setSystemSetting(SCREEN_OFF_TIMEOUT)`
   (DO/PO; SCREEN_OFF_TIMEOUT is on the allowlist — AOSP `DevicePolicyManager.java`
   `SystemSettingsWhitelist`, DPMS writes it with a cleared identity), re-applied on every sync and
   by a ContentObserver when anything changes it; `DISALLOW_CONFIG_SCREEN_TIMEOUT` hides the
   Settings control while managed. Status report: `screen_timeout_seconds` = value read back.
5. **PWA scroll**: every auto-saving form posts back and the redirect lands on the card anchor;
   a tiny inline script stores `scrollY` in sessionStorage on submit and restores it on load.
6. **Build hygiene**: current FCM/Wi-Fi/Bluetooth APIs (toggles [needs device test]), kapt,
   no compile-classpath processor discovery, R8 service warning silenced; CI builds with
   `-PwarningsAsErrors=true` (allWarningsAsErrors).
7. **No upstream splash**: our own screens get a navy, icon-less starting window
   (`windowSplashScreenBackground`, transparent `windowSplashScreenAnimatedIcon`, API 31+).
8. **Own app icon**: navy adaptive icon with a white house+phone glyph and a monochrome layer.
