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
```

Press Home, choose the launcher. In its Settings (open until the first policy arrives):
server URL `http://10.0.2.2:3100`, enrollment code from step 2. Take a snapshot ("enrolled").

## 5. Simulated calls (emulator console via adb)

```sh
adb emu gsm call 4791234567      # incoming call from that number
adb emu gsm cancel 4791234567    # caller hangs up
adb shell am start -a android.intent.action.CALL -d tel:112   # (emergency; the emulator fakes it)
```

Add contacts with matching numbers in the PWA calls page (+47 9123 4567).

## 6. What to check first (smoke)

1. Phone boots to our Home; reboot → no lockout, Home comes back after unlock.
2. Allowed contact calls in → rings; unknown number → rejected; withheld → rejected.
3. Call out from the phone book to an allowed contact works; dialling another number
   (`am start -a android.intent.action.CALL -d tel:12345678`) is stopped.
4. Reboot, don't unlock, `adb emu gsm call <unknown>` → rejected; `<allowed>` → rings.
5. Add a school rule for "now" in the PWA → lock screen with the rule name; calls blocked.
6. Badges: post a notification from an allowed app → count on its icon.

Then the full checklists in `docs/design/01`–`07`.

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
adb logcat -d -t 2000 | grep -iE "kidslauncher|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|Fcm|PlayRuntime|Backstop" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.
