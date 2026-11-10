# What an Android 12 floor would cost (a check, 2026-11-10)

Asked by the user for a music-player-only device (PLAN, "Asked 2026-11-10: a music-player-only edition"): a small old
phone with the launcher as Device Owner and Vibb music as the kiosk's only screen. Both apps are `minSdk = 34` today.
This is the result of one agent's check on main `3f77a5de`: Android lint for both apps' debug variant with
`minSdk = 31` in a throwaway worktree (nothing merged), and a read of the device-owner and kiosk path. Design 56
(`docs/design/56-android-13-and-17.md`) priced the smaller step to Android 13 earlier.

"Measured" is lint output, a build result or code that was read. "Inferred" is what Android 12 is believed to do, from
memory of the platform: no Android 12 source was read and no Android 12 device or emulator was run.

## The answer

- **Which phone.** Unihertz lists the Jelly 2 with Android 11 and the Jelly 2E with Android 12. A floor of API 31
  covers the Jelly 2E only; the Jelly 2 would need API 30, which was not checked.
- **Measured: both apps build and lint at 31.** No library refuses the floor, the dependency verification blocks
  nothing. Launcher: 133 `NewApi` errors (110 need API 33, 23 need API 34) and 33 `InlinedApi` warnings on 135 source
  lines in 49 files. Music: 6 `NewApi` (5 need 33, 1 needs 34) and 3 `InlinedApi` on 7 lines in 4 files.
- **Judged size.** About 57 statements to guard or rewrite on the launcher's player path, 6 in the music app, about 28
  in phone features. 53 of the launcher's errors are one pattern (Android 13's typed `PackageManager` flag overloads)
  that one helper removes. The code change is of design 56's order (days of agent time); the Android 12 emulator
  rounds and the device questions below are the larger part.
- **An unguarded call is a crash (measured).** Start-up and the policy pass catch `Exception`; a missing method is a
  `NoSuchMethodError`, an `Error`. As the code stands the launcher would crash on Android 12 in QR provisioning
  (`ProvisioningActivities.kt:31`, `MdmDeviceAdminReceiver.kt:69`, the typed `getParcelableExtra`), at every start
  (`timerules/ScreenTime.kt:64`, `LocalDate.ofInstant`) and in every policy pass.
- **No Android 12 equivalent on the player's path (inferred):** the recents-screenshot switch (design 32's black
  snapshot), `setAccessibilityDataSensitive` on the parent PIN keypad, platform per-app language (`LocaleManager`, in
  both apps), and the broadcast's sender identity the launcher-music nudge checks (`setShareIdentityEnabled` /
  `getSentFromPackage`; the substitute is the signature permission). In phone features: `DISALLOW_CONFIG_DEFAULT_APPS`,
  the Device Owner's silent dialer role, the background-start-denied send behind the timer's Stop.
- **Cannot be had at all (inferred):** platform security patches (none for Android 12 since about early 2025) and a
  root store that updates without an OTA. The device would be a Device Owner with a VPN and a tailnet client on an
  unpatched OS.

## Open, and only a device answers it

1. QR Device Owner provisioning with our non-Play DPC on that phone (`docs/notes/launcher/gotchas-and-history.md`
   records a failure on a GMS Moto G Play).
2. A 64-bit userspace: `tsnet.aar` is built for arm64 alone, with `-androidapi 34`
   (`.github/actions/build-tsnet/action.yml`), so the release build needs it rebuilt at 31 - not exercised here, the
   debug build used the stub.
3. Lock task, the status-bar repair, design 32's lock flash, the keyguard cover and the boot cover on Android 12's
   SystemUI and legacy transitions - all written from the Android 14 to 16 sources.
4. The always-on VPN DNS filter on Android 12's resolver; Android 14's policy engine against 12's old one.
5. Bluetooth speaker connect by reflection (design 52 verified 14 to 16 only); the Wi-Fi picker's scan, which on 12
   needs fine location instead of `NEARBY_WIFI_DEVICES`.
6. The WebView and Widevine the Spotify path needs.
7. Exact alarms: the manifest declares only `USE_EXACT_ALARM` (33); on 12 every exact-alarm site degrades to inexact
   unless `SCHEDULE_EXACT_ALARM` is added for API 32 and below.

## Afterwards

Every emulator test runs on Android 16 (`jelly36`, `handy36`); there is no Android 12 image. Design 56 §4.1 put the
step to 13 at every kiosk, lock and call round twice. Nothing fails the build on a new Android 14 call today, so a
lowered floor rots unless `NewApi` becomes a build error. Phone features are not optional code: `Application`
initialises the call policy store, the SMS engine, time rules, screen time, calendar and weather on every device.

## Not checked

A release build with the real `tsnet.aar`; any device or emulator; unit tests; API 30; the tailnet client's Go code;
the music app's `PlaybackService`; the code reading the `POST_NOTIFICATIONS` grant's answer. The statement counts are
a hand count from the lint listing; the line and finding counts are lint's.
