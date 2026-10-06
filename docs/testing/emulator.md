# Testing on the Android emulator (Debian VM)

For a Pixel 7 / Android 16 AVD resized to the Jelly Star screen. Uses the **debug**
build (package `me.vibb.launcher.debug`, tsnet stub) and a local server. Never put
a debug APK on the real phone.

**Package rename (2026-10-06)**: the launcher's applicationId is `me.vibb.launcher` (debug
`me.vibb.launcher.debug`); it was `com.kidslauncher.mdm`(`.debug`). The Kotlin classes keep their
`com.kidslauncher.mdm.*` names, so component names are `me.vibb.launcher.debug/com.kidslauncher.mdm.…`.
A different applicationId is a different app: `adb install -r` of a new build next to an old
`com.kidslauncher.mdm.debug` device owner installs a second launcher instead of updating it
(`scripts/dev-rebuild.sh` warns). Wipe the emulator (or restore the "clean" snapshot) and
provision again as in §4; the server side needs nothing (its migration renames the launcher's
catalog row).

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
P=me.vibb.launcher.debug
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

## 5b. Smoke-test script

`scripts/smoke-test.sh` runs the call and lock basics below in about two minutes - after every change and
before allowing an Android update. **Emulator only**: it places calls, so it first proves the target is an
emulator (`ro.kernel.qemu`/`ro.boot.qemu` = 1) with a console that answers `avd name` with OK (and the same AVD
the device reports) and aborts otherwise, before any call. Every call it starts is hung up again on exit, also
after a failure or Ctrl+C (`gsm cancel`, then `KEYCODE_ENDCALL` if anything is left).

```sh
# On the VM itself (adb and the console are local; the token is read from ~/.emulator_console_auth_token):
KID_PIN=1234 ./scripts/smoke-test.sh
# From another machine: tunnel the VM's adb server and emulator console over ssh, then use loopback only.
ssh -N -L 5038:127.0.0.1:5037 -L 5554:127.0.0.1:5554 vm &
ADB="adb -H 127.0.0.1 -P 5038" CONSOLE_TOKEN="$(ssh vm cat .emulator_console_auth_token)" \
  KID_PIN=1234 ./scripts/smoke-test.sh
```

Never start the adb server with `-a` (anyone on the network gets an unauthenticated shell on the emulator), and
keep the console on loopback: the script refuses a `CONSOLE_HOST` that is neither loopback nor a Tailscale IP
(100.64.0.0/10, encrypted by WireGuard; the token goes in cleartext). Over the tailnet, forward the VM's adb server
and console on its tailnet IP only (e.g. `socat TCP-LISTEN:15554,bind=100.64.0.17,fork TCP:127.0.0.1:5554`), never
on 0.0.0.0,
and with a remote adb server (`-H`/`-P` in `ADB`) it talks to the console over TCP with `CONSOLE_TOKEN` - `adb emu`
would reach this machine's loopback, not the VM.

Setup in the PWA first: phone enrolled, calls managed and on, `ALLOWED_NUMBER` (default `+4791234567`) a contact
allowed in and out, `UNKNOWN_NUMBER` (default `+4799999999`) no contact, a kid PIN (`KID_PIN`). Both numbers must
be full E.164 numbers (8-15 digits; short or emergency numbers are refused). Other variables: `PKG` (default
`me.vibb.launcher.debug`), `ALLOWED_DIAL` (the contact in national form, default the number without `+47`),
`OUT_DIR` (screenshots, default `./smoke-<date>`, gitignored), `EXPECT_KIOSK=0` for a phone with the kiosk off,
`STRICT=1` to fail on skipped checks.

What it checks, each a PASS/FAIL line (SKIP without a PIN), summary at the end, exit 1 on a FAIL. Only evidence
logged after the step started counts (logcat is cleared per step); silence is a FAIL:
- adb reaches an emulator with a working console, the package is installed and device owner, lock task is
  engaged (kiosk);
- screen off/on (`KEYCODE_SLEEP`/`KEYCODE_WAKEUP`) shows `$PKG/com.kidslauncher.mdm.lock.PinLockActivity`;
- an incoming call from the allowed contact rings on our call screen
  (`$PKG/com.kidslauncher.mdm.calls.InCallActivity`, not the system dialer's) over the lock, Telecom's
  `FILTERING_COMPLETED` not a reject; it is answered (`KEYCODE_CALL`, else the Answer button), and after `gsm
  cancel` the call notification (id 1005) is gone and the PIN lock is back;
- an unknown number is screened out by **our screening service** (`FILTERING_COMPLETED ... [Reject` or
  `KidCallScreening` "Rejecting an incoming call"; a reject only by the in-call service means screening failed
  open and is a FAIL), no call screen;
- the PIN unlocks (keypad keys tapped through `uiautomator dump`);
- while the answered call is on, a second (allowed) outgoing call logs our "second call" line and Telecom keeps one
  live call (one call at a time - tested on the incoming call, since the emulator's modem simulator hangs up
  outgoing calls at once with DisconnectCause REMOTE);
- an outgoing call to the unknown number is stopped - Telecom's "Canceled from Call Redirection Service" or our
  "Cancelling a not-allowed outgoing call" / "Disconnecting a not-allowed outgoing call", and no live call left;
  one to the contact typed in national form logs our "Redirecting an allowed outgoing call" and Telecom's
  `SET_DIALING` (the number is never logged; `outgoingDialTarget` is unit-tested); after hang-up the notification
  and the call screen are gone.

Call state comes from `dumpsys telecom` (mCalls) and the ringing number from `dumpsys telephony.registry`: the
console's `gsm list` stays empty on the Android 16 emulator (modem simulator), and `gsm accept` finds no call.

Screenshots of every step go into `OUT_DIR` (the lock screen may be black if it is secure). **It never places an
emergency call** - it prints that manual step at the end.

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
app (package `me.vibb.launcher.debug`), mark it as the launcher on its page, upload the APK
with a new release label. The phone downloads it at the next sync and keeps it pending
(logcat `MdmSyncWorker`: "waiting for the update window"); **debug builds count a pending update
as overdue after 2 minutes**, release builds wait for 02:00-05:00 (or 24 h).

```sh
P=me.vibb.launcher.debug
H="-a android.intent.action.MAIN -c android.intent.category.HOME"
adb logcat -v time | grep -E "UpdateFence|SelfUpdate|MdmSyncWorker|AppInstall|PinLock|START u0|LockTask|Force stopping|kidslauncher|vibb"
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

Fix round (qa-11-code) and user requests, 2026-10-06:
- [ ] **Orphan sweep**: with the fence up (A4, during the window) `adb shell run-as $P rm shared_prefs/update_fence.xml`,
  `am crash $P` -> after the restart the stock launcher is unsuspended (logcat `UpdateFence` "Orphaned fence").
- [ ] **Wake right before the commit**: screen off until the commit starts, then `KEYGUARD_WAKEUP` within the hash/copy
  seconds -> logcat "deferred right before the commit", no fence, the APK stays pending.
- [ ] **Wrong signer**: an APK with the launcher's package name signed by another key -> "WRONG_SIGNER" before fencing,
  no new download of that release (device page: failed), a newer release goes through.
- [ ] **Call screen avatar**: an allowed contact with a photo, one without (initial on its colour, same as on Home),
  and an unknown number (calls unmanaged, so it rings) -> the silhouette only for the unknown one; 112 (emulator fake)
  -> silhouette.
- [ ] **Ongoing-call card**: answer an allowed call, press Home -> the green card replaces the contacts row with
  "Samtale med <navn> · 00:0x" counting; tap -> the call screen; `adb emu gsm cancel` -> the card goes, the contacts
  come back. Unknown caller (calls unmanaged) -> "Samtale pågår".
- [ ] **Camera while locked**: kiosk on and off, camera allowlisted: screen off, double-press power (or
  `adb shell am start -a android.media.action.STILL_IMAGE_CAMERA`) -> no camera frame, the lock stays
  (`dumpsys package com.android.camera2 | grep suspended` true while LOCKED, false after the right PIN; logcat
  `CameraLock`). The camera works normally after the unlock.

Camera lock follow-ups (qa-11b-code #5, after the fixes of #1-#4):
- [ ] **Restart while LOCKED**: screen off (LOCKED), `adb reboot` and, separately, `adb shell am crash $P` ->
  after the restart `dumpsys package com.android.camera2 | grep suspended` is true again while the lock shows,
  false after the right PIN.
- [ ] **Lock switched off while LOCKED**: with the screen off, remove the kid PIN on the device page (sync), or set
  an Android PIN (`adb shell locksettings set-pin 1234`) -> the camera is unsuspended without an unlock (logcat
  `CameraLock` "Unlocked: camera apps unsuspended"); `locksettings clear --old 1234` afterwards.
- [ ] **Sync during the unlock**: save something on the device page (it nudges the phone) and type the PIN right
  away, while that sync runs -> the camera stays usable after the unlock (no "app paused" dialog when opened;
  `suspended=false`).
- [ ] **Home keeps the camera tile**: camera allowlisted, note the grid; screen off/on and unlock 5x -> the camera
  tile never disappears or moves and the grid doesn't reload (logcat: no "loadApps" burst at screen-off/unlock).
- [ ] **What shows instead of the camera**: kiosk on and off, LOCKED, double-press power and `adb shell am start -a
  android.media.action.STILL_IMAGE_CAMERA` -> Android's "app paused"/admin-support dialog ("Learn more" opens
  Settings' admin info) must not stay usable over the lock - the lock re-fronts within 2 s; screenshot it.
- [ ] **Third-party cameras**: allowlist a camera-first app that isn't the gesture's default (e.g. a messenger
  declaring `STILL_IMAGE_CAMERA`) -> it stays unsuspended while LOCKED (its notifications still arrive); only the
  gesture's default and the system camera are suspended (logcat `CameraLock` "Third-party camera the gesture
  opens" names only the default).
- [ ] **Call card after Home stops**: during a call press Home, then open an allowed app -> no 1 s ticker keeps
  running (logcat quiet, `dumpsys activity` shows Home stopped); back on Home the card counts on.

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
  starts the kiosk). The full runbook (account settings, Play settings, FRP, an enrolled phone,
  the open Play-services check) is `docs/setup/google-account.md`.
- Quick checks: `adb shell bmgr enabled` -> "Backup Manager is not activated for user 0" (the
  device page's "Phone hardening" card says "Backup to Google: off");
  `adb shell dumpsys alarm | grep -i backstop`;
  `adb shell dumpsys package com.android.vending | grep -i suspended` (true while managed);
  `adb shell am start -a android.intent.action.VIEW -d market://details?id=org.example`
  shows "The Play Store is closed"; Settings -> "Install from Play" (PIN) opens Play for 15 min.

## 7. Reporting back

For anything odd, paste into the chat:

```sh
adb logcat -d -t 2000 | grep -iE "kidslauncher|vibb|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|Fcm|PlayRuntime|Backstop|AppEnforcer|EmergencyDialer|LockTask|PinLock|UpdateFence|SelfUpdate|NotificationRule|CameraLock" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.
