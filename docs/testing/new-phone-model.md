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
  and overnight `dumpsys location` shows the 4-hour tries, after a failed one the next two beats, 1 h, 2 h, then 4 h
  (`Location heartbeat: STILL` between them, `STILL fails=N retry=M min` after a failure; "Hasn't moved since" stays up
  to 24 h from the last good fix or renewal).
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
- [ ] After a small move (design 134 §9, the same setup, for 2-4 weeks):
  1. The Wi-Fi mainline module's version: `adb shell pm list packages --apex-only --show-versioncode | grep -i wifi`
     and `adb shell dumpsys package com.google.android.wifi | grep -i version` - noted with the run.
  2. Inconclusive tries per day by reason: `adb logcat -s ZoneWatch WifiPlaces StillPrint` (`result=inconclusive
     reason=...`, `second scan ...`, the `scan refusals` line) and the dump's `inconclusive=` line; whether a second scan
     brings the place back.
  2a. The results' time base: the `scan answer n/m ask=answered` lines show `n > 0` on a fresh scan and never
     `timebase`; a result's timestamp in `adb shell dumpsys wifi` ("Latest scan results") against `adb shell cat
     /proc/uptime` across a deep sleep (boottime counts it, uptime doesn't) - noted with item 1's module version.
  3. After 2-4 weeks: `adb shell dumpsys activity service
     me.vibb.launcher/com.kidslauncher.mdm.server.CommandListenerService still-print` - verdicts confirmed,
     contradicted, would-not by reason (and `within`), the contradicted ones' distances; revision 3's `mismatches=`
     line: how often a scan mismatch ended "Hasn't moved since" and whether the next fix said it was right
     (`confirmed`) or wrong (`contradicted`).
  4. Cells: whether the phone has a SIM (the mismatch's `cell` clause needs one).
- [ ] Apple's cell lookup (design 125b §9; needs a SIM, "Oppslag hos Apple" on):
  1. `adb shell dumpsys telephony.registry | grep -i -m5 cellinfo`: the registered cell's identity, which neighbours
     carry one, NR (NSA or SA), whether the timestamps move after a refresh, both slots' registered flags.
  2. A day: `WifiPlaces: cells read=` lines, `apple cells asked=... got=...`; the server's sweep `cells=`/`cell_calls=`.
  3. Outdoors, Wi-Fi off, GPS blocked (a bag, a car): a `vibb_apple_cell` fix and its circle against the GPS fix after;
     after a few days the `loo cell own=... apple=...` counts (what Apple's tower accuracy means here).
  4. A zone arrival by car: no crossing from a near cell fix, the arrival at the first fine fix; a trip over 20 km:
     "left" from two far cell fixes 10 minutes apart.
  5. A still evening at a fjord or valley edge: `cell_contradictions=0`.
- [ ] Google Location Accuracy off (the setup step `location_private`): Settings -> Location -> Location services ->
  **Emergency Location Service** is on (the model's name for it may differ; it sends the best position with an
  emergency call whatever GLA is), and the setup step reads done (`adb shell dumpsys location | grep -i network`: the
  provider disabled). Note whether GLA's switch was greyed by Advanced › "Hold posisjon på" (`DISALLOW_CONFIG_LOCATION`).
- [ ] Google's four switches (design 126 B1-B4, optional setup steps): each "Åpne" in the setup flow opens its page
  (Play services' own, else Android's), and the path in its line is the model's - Phone number verification, Delete
  advertising ID, Usage & diagnostics, Play Protect's "Improve harmful app detection"; note the model's nb labels.
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
