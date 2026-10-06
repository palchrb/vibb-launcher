# Testing on the Android emulator (Debian VM)

For a Pixel 7 / Android 16 AVD resized to the Jelly Star screen. Uses the **debug**
build (package `com.kidslauncher.mdm.debug`, tsnet stub) and a local server. Never put
a debug APK on the real phone.

## 1. Build (on the VM)

```sh
cd ~/repos/handy && git checkout master   # one repo: launcher/ and server/
cd launcher && ./gradlew assembleDebug   # -> launcher/app/build/outputs/apk/debug/app-debug.apk
cd ../server && cargo build
```

## 2. Server (on the VM)

```sh
cd ~/repos/handy/server
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
adb install -r ~/repos/handy/launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner $P/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION $P
adb shell cmd notification allow_listener $P/com.kidslauncher.mdm.badges.BadgeListenerService
# Step 9 (B2): the device owner can't switch off the camera gesture - do it before enrolling
# (Settings -> System -> Gestures -> "Quickly open camera" off), or with adb:
adb shell settings put secure camera_double_tap_power_gesture_disabled 1
```

Language and time zone (fix round 2026-10-06): adb provisioning doesn't set them - choose
Android's language (e.g. Norsk bokmål) and time zone in Settings **before** `set-device-owner`/
enrolling; once managed, the system language is locked (`DISALLOW_CONFIG_LOCALE`, hardening switch
"Lock the phone's language", on by default - only the server lifts it). The QR setup sets both
from the provisioning page (`PROVISIONING_LOCALE` default `nb_NO`, `PROVISIONING_TIME_ZONE`
default `Europe/Oslo`). QR provisioning itself (the launcher now has the Android 12+
`GET_PROVISIONING_MODE`/`ADMIN_POLICY_COMPLIANCE` activities) is **[needs device test]** on the
Jelly Star and stock GMS phones; adb above stays the fallback.

Screen lock: since step 10 the phone has **no Android screen lock** - handy's own PIN lock
replaces it (set the kid's PIN on the device page, "Screen lock" card; the unlock code must be set
first). An Android PIN keeps handy's lock off (no double lock) and the device page says "Remove
the Android screen lock". Remove one with `adb shell locksettings clear --old <PIN>` (or Settings ->
Security -> Screen lock -> None). Only for the old keyguard tests (09 doc, B2:
`KEYGUARD_DISABLE_SECURE_CAMERA`) set an Android PIN on purpose, and remove it afterwards.

Press Home, choose the launcher. In its Settings (open until the first policy arrives):
server URL `http://10.0.2.2:3100`, enrollment code from step 2. Take a snapshot ("enrolled").

## 5. Simulated calls (emulator console via adb)

Always give numbers in international form with `+` (e.g. `+4781549300`): without it the
emulator presents `4781549300` as a national number, which the launcher normalizes to
`+474781549300` and doesn't match the contact.

```sh
adb emu gsm call +4791234567     # incoming call from that number
adb emu gsm cancel +4791234567   # caller hangs up
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

Then the full checklists in `docs/design/01`–`07`, `09`, `10` and `11` (§6d below).

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
4. **Incoming call over the lock**: lock the screen, then `adb emu gsm call +4791234567`
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

## 6d. Step 11: kiosk escapes - update fence, night update window, notifications

**`adb install -r` is unfenced** (dev only; adb is gone on managed phones by default): it still
kills the launcher, so the stock launcher can show for the install's duration. It does bring
Home back afterwards (`MY_PACKAGE_REPLACED`) and releases a stale fence (our `lastUpdateTime`
moves even when the debug versionCode doesn't). Only a self-update through the server's
launcher row (A4) exercises the fence and the window.

Setup: device page -> "Launcher updates and notifications": tick **Update fence** and
**Notification filter** (both default off until A3/B5 pass). A test self-update for the debug
build: `./gradlew assembleDebug -PversionCode=<higher than installed>`, PWA Apps -> add a manual
app (package `com.kidslauncher.mdm.debug`), mark it as the launcher on its page, upload the APK
with a new release label. The phone downloads it at the next sync and keeps it pending
(logcat `MdmSyncWorker`: "waiting for the update window"); **debug builds count a pending update
as overdue after 2 minutes**, release builds wait for 02:00-05:00 (or 24 h).

```sh
P=com.kidslauncher.mdm.debug
H="-a android.intent.action.MAIN -c android.intent.category.HOME"
adb logcat -v time | grep -E "UpdateFence|SelfUpdate|MdmSyncWorker|AppInstall|PinLock|START u0|LockTask|Force stopping|kidslauncher"
adb shell dumpsys package com.google.android.apps.nexuslauncher | grep -i suspended
adb shell dumpsys statusbar | grep -E "mDisabled1|mDisabled2"
adb shell run-as $P cat shared_prefs/update_fence.xml      # the fence record while it's up
```

Update fence (A, design 11 §4; record times and what is reachable):
- [ ] **A1 baseline**: `adb shell cmd package query-activities --brief $H`; `cmd package resolve-activity --brief $H` (= ours);
  `cmd role get-role-holders android.app.role.HOME`; `dumpsys activity activities | grep -E "mLockTaskModeState|mLockTaskPackages"`.
- [ ] **A2 repro and timing**: poll `adb shell "dumpsys activity activities | grep -m1 mLockTaskModeState; dumpsys package frozen"`
  each second during an install; record commit -> `Force stopping` -> `MY_PACKAGE_REPLACED` -> fence release (logcat
  `UpdateFence`). Meanwhile `input keyevent KEYCODE_HOME`, `KEYCODE_APP_SWITCH`, `cmd statusbar expand-notifications`,
  `adb exec-out screencap -p > w.png`. Repeat with the PIN lock LOCKED (`KEYCODE_SLEEP`, install, `KEYCODE_WAKEUP`).
  Variant: a call from a **blocked** number mid-window (`adb emu gsm call <number>`) - expected to ring unscreened in the
  system in-call UI for the ~1 min window (known gap, QA 11 #8); record it.
- [ ] **A3 fence pre-test (no code)**: `adb shell pm suspend com.google.android.apps.nexuslauncher`, then A2 - what does
  HOME show? In both nav modes (`cmd overlay enable com.android.internal.systemui.navbar.gestural` / `...threebutton`):
  `input swipe 540 2350 540 1200 250`, Recents, Back, kiosk on and off; `pm unsuspend` after. Same on the Jelly Star (its
  launcher package). **The `update_fence` switch may default on only after this passes on the Jelly Star.**
- [ ] **A4 after implementation**: screen on -> no commit (logcat "waits: screen_on"); `KEYCODE_SLEEP` -> commit 30 s
  later (logcat `UpdateFence` "Fenced for ..."); `dumpsys package com.google.android.apps.nexuslauncher | grep suspended`
  true during, false after; the shade disabled during; Home/lock in front (lock task when the kiosk is on) <= 5 s after
  `MY_PACKAGE_REPLACED`; ROLE_HOME and HOME resolution as in A1. Then:
  - wake the phone between commit and the kill -> the fence stays (no early release in the old process; logcat
    "Fence kept (front): installing");
  - `adb reboot` mid-install -> fence released at boot ("rebooted"), kiosk on and off;
  - a mismatched-signature APK (another debug key) and a lower versionCode -> the lower one is refused before fencing
    ("DOWNGRADE"), the mismatched one fails the install and the fence is released at once: packages unsuspended **and**
    the shade back with the PIN lock off;
  - a time-rule boundary inside the window -> the stock launcher stays suspended until apply decides;
  - the tail in both nav modes (gesture Home/Recents with quickstep suspended), power-menu Emergency and an incoming call
    (allowed and blocked) with the fence up.
- [ ] **A5 crash** (snapshot first): a build throwing in `HomeActivity.onCreate` over a good one - screen,
  `adb emu gsm call 4781549300`, power-menu Emergency, `dumpsys activity activities`; the fence must be gone 2 min after
  the replacement; recover with `adb install -r` of a good build.

Notifications (B; kiosk LOCKED, block on unless noted):
- [ ] **B1 identify nags**: `adb shell dumpsys notification --noredact | grep -E "NotificationRecord|android.title=|contentIntent|flags="`;
  `cmd notification list`, `cmd notification get <key>` -> package, channel, clearable, PendingIntent creator/type. List
  Play services' channels and classify each (nag vs alert) - the rule keeps channels named like earthquake/emergency/
  cmas/crisis; anything else from GMS is cancelled. Classify Play services' system-update "restart to install"
  notification too: the rule cancels it today, so a patch can wait for an unplanned reboot (qa-11-code #7). Record
  the OEM's emergency-alert app (`cmd package query-receivers --brief -a android.provider.action.SMS_EMERGENCY_CB_RECEIVED`)
  - it must stay. A grouped nag whose group also holds a kept notification keeps its summary (qa-11-code #6).
- [ ] **B2 non-pinned target** (filter off first): `cmd package resolve-activity --brief -a android.intent.action.SET_WALLPAPER` -> C;
  `cmd notification post -t Nag -c activity -n C nag1 test`; shade, tap -> `BlockedAppActivity`; block off -> toast; kiosk
  off -> opens. `dumpsys activity recents | grep -E "realActivity|BlockedApp"`.
- [ ] **B3 pinned helper**: as B2 with `-a android.safetycenter.action.SAFETY_CENTER` -> Safety Center in kiosk?; its
  "Screen lock" -> `BlockedAppActivity`. **B4** PIN lock LOCKED: `cmd statusbar expand-notifications` does nothing.
- [ ] **B5 after the rule** (filter on): B2's notification gone <= 1 s (`cmd notification post` posts as shell, which is
  neither allowed nor essential), B1's nags gone; a re-posting nag (post the same tag 5x within a minute) ends snoozed
  (logcat `NotificationRule`: "snooze"); a real missed call (`adb emu gsm call` + cancel) and a clock alarm stay; a
  cell-broadcast test alert (`adb emu cbs ...` / the emulator's extended controls) and the Play services earthquake-alert
  demo stay. The device page lists the removed package/channel counts. **The `notification_auto_cancel` switch may
  default on only after this passes.**

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
adb logcat -d -t 2000 | grep -iE "kidslauncher|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|Fcm|PlayRuntime|Backstop|AppEnforcer|EmergencyDialer|LockTask|PinLock|UpdateFence|SelfUpdate|NotificationRule" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.
