# A new phone model: the checklist

What to check when Vibb One goes onto a phone model we haven't run before (the user asked 2026-11-21: "vil hver nye tlf
modell kreve like mye jobb og feilsøking?"). Most of what the Jelly Star found in November 2026 was a wrong emulator
assumption, fixed for every phone: an exact backstop alarm, Element X's new room link, MediaStore's delete dialog in
lock task, the Credential Manager selector. What differs per model is mostly the OEM's background killing, the screen
and font, and which system apps the model ships. This list checks those. It takes about an hour with the phone on adb
over Wi-Fi (`adb pair`/`adb connect` from Developer options; turn it off afterwards).

Run it with a signed release build (never a debug APK on a kid's phone), on the Android version the phone will run.

## 1. Before setup

- [ ] Note the model, Android version, security patch, density and font scale:
  `adb shell getprop ro.product.model; adb shell getprop ro.build.version.release; adb shell wm density; adb shell settings get system font_scale`.
- [ ] Factory reset, then the QR setup. The provisioning log shows "Admin policy compliance", and the setup page's steps
  all reach done (Barne-PIN included).

## 2. Staying in touch (the OEM's background killing)

- [ ] Leave the phone on a charger and then off it for a day. The PWA never shows "No contact" while the phone is on.
- [ ] `adb shell dumpsys alarm | grep -A3 BACKSTOP_SYNC`: `window=0` and an `exactAllowReason`. A non-zero window means
  the exact alarm grant is missing.
- [ ] `adb shell dumpsys deviceidle whitelist | grep vibb` and `adb shell am get-standby-bucket me.vibb.launcher`
  (5 = exempt). Look for the OEM's own killer (`dumpsys activity | grep -i -E "duraspeed|powergenie|autostart"` and the
  model's battery settings).
- [ ] Location every 30 min indoors with no SIM: the PWA's age stays under ~31 min. If not:
  `adb shell dumpsys location | grep -iE "network|fused|enabled"` (needs Google Location Accuracy / a network provider;
  whether it is on is the parent's choice, a privacy trade-off - `docs/setup/google-account.md` §2).
- [ ] The significant-motion trigger (design 124, location while the phone lies still), location Regularly: after the
  first sync and a fix, `adb logcat -s ZoneWatch` shows `Motion trigger armed (start)` (or `(fresh)`), and
  `adb shell dumpsys sensorservice | grep -i -B1 -A3 significant` lists a registration by the uid of
  `me.vibb.launcher` (`adb shell dumpsys package me.vibb.launcher | grep userId`). No significant-motion sensor in the
  list: the phone fixes at every due beat (today's behaviour) and `zone_watch.motion_sensor` is false.
- [ ] After at least an hour screen-off (deep Doze): a walk of a minute logs `Motion trigger fired`, and the next beat
  logs `Location heartbeat: FIX` with `Fresh fix path=...`. A still evening: the PWA's Locate says "Hasn't moved since",
  and overnight `dumpsys location` shows at most the 4-hour tries (`Location heartbeat: STILL|QUIET` between them).
- [ ] Wi-Fi places (design 125 §8, a release build, Google Location Accuracy **off**, location Regularly):
  1. `adb shell dumpsys location | grep -iE "network|satellites"`: the `network` provider disabled; a GPS fix's extras
     carry `satellites` (no teaching without it, design 125 D1).
  2. Airplane mode with Wi-Fi on, indoors: `locate` gets nothing from `fused` (then fused may teach, D1; until then it
     never does).
  3. A day on Regularly: `adb logcat -s WifiPlaces` shows `taught aps=...` lines; `adb shell dumpsys wifi | grep -A3
     -i "scan request"` shows the launcher's uid's scans accepted, not throttled.
  4. Days until the first `vibb_wifi` fix at home (the PWA's newest-fix title); its circle against a GPS fix at the
     window; the day the zone link forms (`attempt path=renew` on a still night).
  5. A still night: "Hasn't moved since" through it; the 4-h tries log `attempt path=wifi` or `path=renew`;
     `loo 3+=... contradictions=...` counts (the leave-one-out check of the 68 % accuracy, D16).
  6. Wi-Fi off with Location -> Wi-Fi scanning on, then off (no learned fix); location Off from the PWA:
     `WifiPlaces: cleared (off)`.
  7. `adb shell dumpsys batterystats me.vibb.launcher`: Wi-Fi scans and GPS time against design 124's day.
- [ ] Google Location Accuracy off (the setup step `location_private`): Settings -> Location -> Location services ->
  **Emergency Location Service** is on (the model's name for it may differ; it sends the best position with an
  emergency call whatever GLA is), and the setup step reads done (`adb shell dumpsys location | grep -i network`: the
  provider disabled). Note whether GLA's switch was greyed by Advanced › "Hold posisjon på" (`DISALLOW_CONFIG_LOCATION`).
- [ ] A reboot: the phone is back online and locked down without anyone opening it.

## 3. Screen and font

- [ ] Home in 3 and 4 columns at the phone's own font scale: every label on one line, the last row clear of the bottom fade.
- [ ] Lock glance: "Nødsamtale" clear of the edge (with and without a nav bar).
- [ ] Telefonbok, Meldinger, the shade's Snarveier, the music app's library and now-playing bar.

## 4. System apps behind lock task

The model's own packages can differ from AOSP/Pixel; a blocked one shows as BlockedAppActivity.
`adb logcat -s ActivityTaskManager | grep -i -E "lock task|blocked"` while doing each:
- [ ] Delete a photo from a media app with media rights (MediaStore's PermissionActivity).
- [ ] The photo picker.
- [ ] A passkey sign-in (the Credential Manager selector; with Bitwarden as provider).
- [ ] Bluetooth pairing with headphones (the pairing dialog).
- [ ] A phone call in and out, an emergency call from the lock glance, SMS and MMS.
- [ ] The Wi-Fi sign-in window on a captive portal.
- [ ] Camera, and the share sheet from it.

## 5. Calls and links out

- [ ] The chat button opens the contact's Element X DM (the installed Element X's schemes:
  `adb shell dumpsys package io.element.android.x | grep -A20 "Schemes"`).
- [ ] Map links open in the map app.
- [ ] The music app plays to a Sonos room and on Bluetooth.

## 6. Before handing it over

- [ ] Re-enable "Blokker USB-feilsøking" and "Blokker tilbakestilling" in the PWA.
- [ ] Turn off wireless debugging, and forget the paired computer.
- [ ] Add what you found for this model to the end of this file.

## Models checked

- Unihertz Jelly Star, Android 16, 320×569 dp (density 1.5), font 1.3, no nav bar (November 2026): DuraSpeed not
  present; standby bucket 5; the exact alarm needed the grant check (fixed for all phones).
