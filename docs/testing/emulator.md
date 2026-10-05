# Testing on the Android emulator (Debian VM)

For a Pixel 7 / Android 16 AVD resized to the Jelly Star screen. Uses the **debug**
build (package `com.kidslauncher.mdm.debug`, tsnet stub) and a local server. Never put
a debug APK on the real phone.

## 1. Build (on the VM)

```sh
cd ~/repos/handy/kids-launcher-mdm && git checkout handy
./gradlew assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk
cd ../kid-phone-server && git checkout handy && cargo build
```

## 2. Server (on the VM)

```sh
cd ~/repos/handy/kid-phone-server
ADMIN_PASSWORD='change-me' BIND_ADDR=0.0.0.0:3100 INSECURE_COOKIES=true cargo run
```

Open `http://localhost:3100`, log in as `admin`, set a new password and 2FA.
The emulator reaches the VM as `http://10.0.2.2:3100`.

In the PWA, **before** enrolling the phone:
1. Devices → New device (note the enrollment code).
2. On the device page: turn **"Block USB debugging" OFF** (otherwise adb dies on the first sync).
3. Set an override PIN.

## 3. Emulator: fresh start and snapshot

- Wipe data in Device Manager (no Google account may be on it), boot, skip setup.
- Take an AVD snapshot now ("clean") so you can go back in seconds.

## 4. Provision (adb on the VM)

```sh
P=com.kidslauncher.mdm.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner $P/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION $P
adb shell cmd notification allow_listener $P/com.kidslauncher.mdm.badges.BadgeListenerService
# Step 9 (B2): the device owner can't switch off the camera gesture - do it before enrolling
# (Settings -> System -> Gestures -> "Quickly open camera" off), or with adb:
adb shell settings put secure camera_double_tap_power_gesture_disabled 1
```

Screen lock: since step 10 the phone has **no Android screen lock** - handy's own PIN lock
replaces it (set the kid's PIN on the device page, "Screen lock" card; the unlock code must be set
first). An Android PIN keeps handy's lock off (no double lock) and the device page says "Remove
the Android screen lock". Remove one with `adb shell locksettings clear --old <PIN>` (or Settings ->
Security -> Screen lock -> None). Only for the old keyguard tests (09 doc, B2:
`KEYGUARD_DISABLE_SECURE_CAMERA`) set an Android PIN on purpose, and remove it afterwards.

Press Home, choose the launcher. In its Settings (open until the first policy arrives):
server URL `http://10.0.2.2:3100`, enrollment code from step 2. Take a snapshot ("enrolled").

## 5. Simulated calls (emulator console via adb)

```sh
adb emu gsm call 4791234567      # incoming call from that number
adb emu gsm cancel 4791234567    # caller hangs up
adb shell am start -a android.intent.action.CALL -d tel:112   # (emergency; the emulator fakes it)
```

Add contacts with matching numbers in the PWA calls page (+47 9123 4567).

SMS (emulator console): `adb emu sms send <number> <text>`, e.g.
`adb emu sms send 4791234567 hello` or a named sender `adb emu sms send Vipps kode 1234`.
Step 9 keeps SMS **on/off only** (the SMS allowlist, part A, is postponed): with SMS off on the
calls page nothing arrives (`DISALLOW_SMS`); with SMS on Messages shows every message.

## 6. What to check first (smoke)

1. Phone boots to our Home; reboot → no lockout, Home comes back after unlock.
2. Allowed contact calls in → rings; unknown number → rejected; withheld → rejected.
3. Call out from the phone book to an allowed contact works; dialling another number
   (`am start -a android.intent.action.CALL -d tel:12345678`) is stopped.
4. Reboot, don't unlock, `adb emu gsm call <unknown>` → rejected; `<allowed>` → rings.
5. Add a school rule for "now" in the PWA → lock screen with the rule name; calls blocked.
6. Badges: post a notification from an allowed app → count on its icon.

7. Step 9: manage calls ON -> the calls page says "Waiting for the phone to confirm" and
   reloads itself until the next report, then shows the real state (never "waiting" for more
   than 5 minutes). `adb logcat -b events | grep -E "am_kill|wm_create_activity"` shows no
   `am_kill` of our package when calls go ON -> OFF -> ON.
8. Step 9 kiosk app block (device page "Push and Play", on by default), kiosk on: from an
   allowed app, `adb shell am start -n com.android.vending/.AssetBrowserActivity` and
   `adb shell am start -a android.settings.SETTINGS` end on the system's "app blocked" screen;
   share sheet, photo picker and a runtime permission dialog of an allowed app still work.
   `adb shell dumpsys activity lock-task` (or `dumpsys device_policy | grep -A3 lockTask`)
   lists the helpers. Switch the block off on the server -> after the next sync the bit is gone.
9. Step 9 emergency: school rule active, calls unmanaged, then
   `adb shell pm revoke $P android.permission.CALL_PHONE` - the lock screen's "Emergency call"
   must open the emergency dialer (the emulator fakes 112; never test 112 on a real phone
   outside its emergency test mode).
10. Camera in front of Home (B2): power double-press / lock-screen camera with kiosk on; if a
    camera appears, collect `adb shell dumpsys activity activities | grep -B2 -A8 camera`
    (launchedFromPackage) and `adb shell dumpsys device_policy | grep -A3 lockTask`.

Then the full checklists in `docs/design/01`–`07`, `09` and `10`.

## 6c. Step 10: handy's own PIN lock and the call screens

Setup: device page -> set an unlock code (offline override PIN, 6+ digits), then "Screen lock" ->
kid's PIN (4-6 digits; the first one also turns "Block safe mode" on). Sync (or wait for the
nudge). The emulator must have no Android screen lock:

```sh
adb shell locksettings clear --old 1234          # only if an Android PIN/pattern was set
adb shell locksettings get-disabled              # true once our lock switched the keyguard off
adb shell dumpsys device_policy | grep -iE "keyguard|lockTask"
```

Screen off/on: `adb shell input keyevent 26` (power). Each screen-off locks; the next
screen-on must show the PIN lock straight away, never a frame of the app underneath.

1. **Lock appears**: in an allowed app, `input keyevent 26` twice -> PIN lock; the right PIN ->
   back in the same app with its state. Repeat 20x (kiosk on, then kiosk off: device page
   allowlist cleared or kiosk unpinned on a test server).
2. **Nothing reachable while locked** (kiosk on and off): pull the shade, Home, Recents
   (`adb shell input keyevent KEYCODE_HOME`, `KEYCODE_APP_SWITCH`), `adb shell input keyevent
   KEYCODE_ASSIST`, the power-button camera gesture, `adb shell am start -a
   android.settings.SETTINGS` - everything must end on the lock (logcat tag `PinLock`:
   "Re-fronting the lock" and how often). `adb shell dumpsys activity lock-task` shows our
   package (plus the emergency helpers with the kiosk off) while locked, nothing extra after the
   unlock with the kiosk off.
3. **Wrong PINs**: 4 wrong are free, the 5th starts "Try again in 0:30", then 1, 2, 5, 15 min.
   During the wait: `adb shell su 0 date ...` / Settings clock forward or back doesn't shorten it
   (date/time are locked while managed - test on a debug build with the restriction lifted),
   `adb reboot` restarts the full wait. The Parent code link (the unlock code) opens the lock
   during the wait, also in airplane mode.
4. **Incoming call over the lock**: lock the screen, then `adb emu gsm call 4791234567`
   (allowed contact) -> the new incoming-call screen over the lock within a second; answer,
   speaker, mute, `adb emu gsm cancel 4791234567` -> back on the PIN lock, still locked. An
   unknown number is rejected without UI. Power button during the call (`input keyevent 26`
   with the "phone" away from the ear) -> lock when the call ends.
5. **Emergency call**: "Emergency call" -> "Call 112?" -> the emulator's emergency dialer/in-call
   UI stays in front (no re-front fight; logcat "Lock yields to an exempt screen: emergency").
   Never test 112 on a real phone outside its emergency test mode.
6. **Process death**: in an app with the screen on, `adb shell am crash $P` (or `am kill`) -> the
   lock comes back at once; with a bedtime rule active the lock and then the bedtime screen.
7. **Remote lock**: Locate page -> Lock -> lock shown, screen off, command result "locked (handy
   lock)".
8. **Migration**: set an Android PIN -> next sync: device page "Remove the Android screen lock",
   our lock off. `adb shell locksettings clear --old <PIN>` -> our lock active within seconds
   (onPasswordChanged), without a sync.
9. **Crash guard** (debug only): three crashes of the lock screen within 2 minutes (`adb shell am
   crash $P` right after each screen-on) -> lock off, device page "crashed several times".
10. Screenshots (320x568 dp and 411 dp, nb + en): `adb exec-out screencap -p > lock.png`;
    `adb shell wm size 640x1136; adb shell wm density 320` for 320x568 dp, `wm size reset` after.

## 6b. Step 7: FCM and Play (optional)

The default debug build has no Firebase config: the phone uses the SSE stream (device page
"Push and Play": `no_config`) - everything above works as before. For FCM/Play tests:

- Use an AVD with a **Google Play** system image (Play Store + Play services).
- Build with the debug Firebase app's values (07 doc, "FCM setup"):
  `./gradlew assembleDebug -Phandy.fcm.projectId=... -Phandy.fcm.debugApplicationId=... -Phandy.fcm.apiKey=... -Phandy.fcm.senderId=...`
  and start the server with `FCM_SERVICE_ACCOUNT_FILE=<key outside data/, chmod 600>`.
- Provisioning order changes when a Google account is wanted: wipe -> `dpm set-device-owner`
  (no account may exist at that moment) -> add the Google account and the Play settings in the
  normal Settings/Play UI -> only then enroll (the first policy blocks account changes and
  starts the kiosk).
- Quick checks: `adb shell dumpsys alarm | grep -i backstop`;
  `adb shell dumpsys package com.android.vending | grep -i suspended` (true while managed);
  `adb shell am start -a android.intent.action.VIEW -d market://details?id=org.example`
  shows "The Play Store is closed"; Settings -> "Install from Play" (PIN) opens Play for 15 min.

## 7. Reporting back

For anything odd, paste into the chat:

```sh
adb logcat -d -t 2000 | grep -iE "kidslauncher|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|Fcm|PlayRuntime|Backstop|AppEnforcer|EmergencyDialer|LockTask|PinLock" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.
