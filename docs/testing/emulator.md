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

A `data/` (`data/kidphone.db`) that ran a music branch's server (`music-1b-sonos` or `music-2-storytel`) before
2026-10-12 won't open on a merged server: those branches' migrations 65 and 66 were renumbered (main's 0065-0069 took
the numbers), so sqlx stops at `VersionMismatch(65)` and the server exits. Start from a fresh `data/` or restore a
backup taken on `main` (design 26b, "Merge of music-1b-sonos and main", qa-26b-merge-code Low 4).

Since server 0.23.0 the admin listener answers only loopback and tailnet clients (design 22): the emulator's
`10.0.2.2` arrives as the VM's loopback and passes, so does `localhost`. A browser or phone on the LAN gets 403 -
add `ADMIN_PUBLIC=on` for such a dev setup. The startup warning about `0.0.0.0` with only loopback trusted is
expected here. With `INSECURE_COOKIES` the session cookie is a plain `handy` (`__Host-handy` needs https).

In the PWA, **before** enrolling the phone:
1. Devices → New device, then "New code" on its page (shown once, valid 15 minutes).
2. On the device page: turn **"Block USB debugging" OFF** (otherwise adb dies on the first sync).
3. Set a parent PIN (the "Parent PIN" card, 6-10 digits).

## 3. Emulator: fresh start and snapshot

- Wipe data in Device Manager (no Google account may be on it), boot, skip setup.
- Take an AVD snapshot now ("clean") so you can go back in seconds.

## 4. Provision (adb on the VM)

```sh
P=me.vibb.launcher.debug
adb install -r ~/repos/handy/launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner $P/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION $P
# Security review L7: the incoming-call filter when the launcher isn't the phone app.
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING $P
adb shell cmd notification allow_listener $P/com.kidslauncher.mdm.badges.BadgeListenerService
# Design 19b: the launcher on the power-save allowlist ("battery: Unrestricted"); expect "Added: ...".
# Check with `=$P` (true/false). Android's Settings shows it as "Unrestricted" either way.
adb shell dumpsys deviceidle whitelist +$P
# Step 9 (B2): the device owner can't switch off the camera gesture - do it before enrolling
# (Settings -> System -> Gestures -> "Quickly open camera" off), or with adb. Both keys (25b):
adb shell settings put secure camera_double_tap_power_gesture_disabled 1
adb shell settings put secure double_tap_power_button_gesture_enabled 0
```

Power-button double press (bug 25b, read in AOSP 16 `GestureLauncherService`): Android 15 and older
have only `camera_double_tap_power_gesture_disabled`. Android 16 adds `double_tap_power_button_gesture_enabled`
(and `double_tap_power_button_gesture`: 0 camera, 1 wallet) for its "Double press power button: open
camera or wallet" setting; where the build uses that multi-target mode (`config_doubleTapPowerGestureMode`
= 2, AOSP 16's default, with the `launchWalletOptionOnPowerDoubleTap` flag on) the old key is **ignored**
and the new one counts (unset = on) - so set both. Since 25b the lock is safe with the gesture on too (the cameras are off by policy
while LOCKED, so SystemUI starts nothing); the keys only keep the camera from opening over Home. Check:
`adb shell settings get secure double_tap_power_button_gesture_enabled` (`null` = the build's default).
Five presses (Emergency SOS, `emergency_gesture_enabled`) stay on: since 25b the SOS app is pinned on the
lock and the lock yields to it.

Language and time zone (fix round 2026-10-06): adb provisioning doesn't set them - choose
Android's language (e.g. Norsk bokmål) and time zone in Settings **before** `set-device-owner`/
enrolling; once managed, the system language is locked (`DISALLOW_CONFIG_LOCALE`, hardening switch
"Lock the phone's language", on by default - only the server lifts it). The QR setup sets both
from the provisioning page (`PROVISIONING_LOCALE` default `nb_NO`, `PROVISIONING_TIME_ZONE`
default `Europe/Oslo`). QR provisioning itself (the launcher now has the Android 12+
`GET_PROVISIONING_MODE`/`ADMIN_POLICY_COMPLIANCE` activities) is **[needs device test]** on the
Jelly Star and stock GMS phones; adb above stays the fallback.

Screen lock: since step 10 the phone has **no Android screen lock** - handy's own PIN lock
replaces it (set the kid's PIN on the device page, "Screen lock" card; the parent PIN must be set
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
`STRICT=1` to fail on skipped checks, `REBOOT=1` to start with a reboot (design 16c, below). Optional (design 15): `ELEMENT_SESSION` (the kid's Element X MXID) and
`ELEMENT_ROOM` (a DM's room ID) start Element X's `elementx://open/<session>/<room>` link (each segment fully
percent-encoded) and check Element X comes up; that it shows the DM is checked on the screenshot. The Message
button itself is a printed manual step (it needs a room learned from a real DM notification).

What it checks, each a PASS/FAIL line (SKIP without a PIN), summary at the end, exit 1 on a FAIL. Only evidence
logged after the step started counts (logcat is cleared per step); silence is a FAIL:
- adb reaches an emulator with a working console, the package is installed and device owner, lock task is
  engaged (kiosk);
- with `REBOOT=1` (and `KID_PIN`): `adb reboot`, then - once `/proc/sys/kernel/random/boot_id` changed - a
  `uiautomator dump` every ~0.3 s (plus the dump's own time) until the PIN lock is in front (3 min at most; FAIL
  without a new boot, without one successful dump, or without the lock) - no captured dump may hold a node of
  Home's content (`<package>:id/home_contacts`, `home_contacts_scroll`, `home_grid`, `home_call_card`; design 16c: a
  locked Home is the night ground `home_night`). A dump that shows it is saved as `boot-home-content-<n>.xml`
  next to its screenshot. Design 16e on the same dumps: once one showed the night ground, every later dump before
  the lock may hold only `$PKG`'s windows (another package is a FAIL, saved as `boot-not-mark-<n>.xml`), and the lock
  must be in front within about 5 s of the mark - measured from the end of that first dump to the last top-activity
  check without the lock, a lower bound, so slow dumps never fail it (FAIL above 5 s; SKIP when no dump caught the
  night ground - a dump plus the top check take 1-3 s, the mark is up for about 3 s). The mark's time on screen comes
  from `logcat -d -s PinLock`, captured once: `Boot mark over: <why> - the mark on screen N ms` (the 3 s count from
  Home's first focus with the night ground drawn). FAIL only below 2500 ms after an unexplained `Home paused`/`Home
  stopped`/`Home lost focus` (no UI dump caught another window) or a short `the mark's 3 s are up`; SKIP the safe ends -
  `Home's mark never shown` (no focus within 1 s: a slow or no-KVM emulator), no line at all, exemptions, `AT_ONCE`
  asks, the lock already up - and without the boot's `Process start:` line. `adb reboot` never arms the boot cover, so
  the mark is Home's;
- then (design 16d) for 45 s from the lock's first appearance - past the launcher's status-bar heals 1-40 s after
  its start - `cmd statusbar expand-notifications`, `expand-settings` and a swipe down from the top, each followed
  by `cmd statusbar collapse`: the shade or Quick Settings open (`mCurrentFocus` NotificationShade, or SystemUI's
  QS/notification panels in a UI dump) is a FAIL (screenshot `boot-shade-open-<n>`). Then `dumpsys statusbar`'s
  `mDisabled1` must equal SystemUI's `TaskbarDelegate mDisabledFlags` (`dumpsys activity service
  com.android.systemui`; SKIP when SystemUI uses NavigationBar, TaskbarDelegate was never initialised
  (`mNavigationMode=-1` - it prints `mDisabledFlags=0` then), or a value is missing);
- screen off/on (`KEYCODE_SLEEP`/`KEYCODE_WAKEUP`) shows `$PKG/com.kidslauncher.mdm.lock.PinLockActivity`;
- an incoming call from the allowed contact rings on our call screen
  (`$PKG/com.kidslauncher.mdm.calls.InCallActivity`, not the system dialer's) over the lock, Telecom's
  `FILTERING_COMPLETED` not a reject; it is answered (`KEYCODE_CALL`, else the Answer button), and after `gsm
  cancel` the call notification (id 1005) is gone and the PIN lock is back;
- an unknown number is screened out by **our screening service** (`FILTERING_COMPLETED ... [Reject` or
  `KidCallScreening` "Rejecting an incoming call"; a reject only by the in-call service means screening failed
  open and is a FAIL), no call screen;
- a missed call (design 12): the allowed contact rings and is cancelled unanswered (`gsm cancel`) - **our**
  notification comes up (`$PKG`, id 1006), and there is no Telecom `TelecomMissedCalls` notification for a call
  after the step started (one Telecom posted before the first unlock may be left over and doesn't fail). Both
  are read from `dumpsys notification --noredact`: the record's `Notification.when` (the call's time) against
  the device clock at the step's start;
- the PIN unlocks (keypad keys tapped through `uiautomator dump`), and for 3 s after it the system's "App is not
  available" screen (`BlockedAppActivity`) never comes to the front (design 16: the lock leaves through Home);
- Recents (`KEYCODE_APP_SWITCH`) and, with gesture navigation, a slow and a fast swipe-up from the bottom edge
  never show `BlockedAppActivity` either (SKIP while the lock is in front or with 3-button navigation);
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

The command stream by hand (design 19, QA #8 - the SSE stream is the only nudge): with the phone
enrolled and the device page saying "Instant changes: connected", freeze the server for more than
300 s, then let it go on:

```sh
kill -STOP $(pgrep -f kid_phone_server); sleep 330; kill -CONT $(pgrep -f kid_phone_server)
adb logcat -d | grep -E "CommandListenerService|SyncRunner" | tail -20
```

Expect the read timeout ("Command stream connection failed, reconnecting"), one reconnect ("Command
stream connected") and one `Sync for [sse_open]` - not a sync per retry. A ring or lock queued in the
PWA right after `-CONT` arrives within seconds. On a phone, the same after a server restart (`update.sh`)
while the screen is off: the reconnect comes within about 30 s (the drop's wake lock), not at the next
15-minute backstop; and a stream that went silent while the phone slept is reconnected at screen-on
("silent for ... s (screen) - reconnecting").

Missed calls by hand after the run (design 12), unlocked: tap the missed-call notification - the contact's sheet
opens (calls from several contacts: the phone book); close it - the notification is gone and the call log's
missed row is read:

```sh
adb shell content query --uri content://call_log/calls --projection _id:number:type:new   # type=3 -> new=0
adb shell cmd package resolve-activity -a android.intent.action.VIEW -t vnd.android.cursor.dir/calls
#   -> $PKG/com.kidslauncher.mdm.calls.PhoneBookActivity (also on the Jelly Star: a privileged dialer
#      whose call-log filter has a priority above 0 would win over our persistent preferred activity)
```

## 6. What to check first (smoke)

1. Phone boots to our Home; reboot → no lockout, Home comes back after unlock.
   With the boot cover switched on (design 16b) restart from the power menu or with `adb shell svc power reboot`:
   `adb reboot` goes through init (`sys.powerctl`), sends no `ACTION_SHUTDOWN`, so the cover isn't armed and the
   boot is A+B only; `ACTION_SHUTDOWN` is protected, so `am broadcast` can't fake it either.
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
8. Step 9 kiosk app block (device page "Play and kiosk", on by default), kiosk on: from an
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

Setup: device page -> set a parent PIN ("Parent PIN" card, 6-10 digits), then "Screen lock" ->
kid's PIN (4-6 digits; the first one also turns "Block safe mode" on). Sync (or wait for the
nudge). The emulator must have no Android screen lock:

```sh
adb shell locksettings clear --old 1234          # only if an Android PIN/pattern was set
adb shell locksettings get-disabled              # true once our lock switched the keyguard off
adb shell dumpsys device_policy | grep -iE "keyguard|lockTask"
```

Screen off/on: `adb shell input keyevent 26` (power). Each screen-off locks; the next
screen-on must show the PIN lock straight away, never a frame of the app underneath. Since design 25
the lock comes up on its glance page (clock, date, the music tile, missed calls and messages):
"Lås opp" (`<package>:id/lock_glance_unlock`) or a row leads to the keypad, which UI dumps don't show
while the glance page covers it.

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
   `adb reboot` restarts the full wait. The Parent code link (the parent PIN) opens the lock
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
10. Screenshots (320x568 dp and 411 dp, nb + en): the lock's window is `FLAG_SECURE` since design 25,
    so `adb exec-out screencap` (and a screen recording) shows it black. Take them with the emulator
    console instead - `adb emu screenrecord screenshot /path/on/the/emulator/host/lock.png` (the file is
    written where the emulator runs; whether it captures secure windows is a device check of design
    25) - and check the content with UI dumps (`adb exec-out uiautomator dump /dev/tty`: the rows are
    accessibility-data-sensitive, which UiAutomation still reads). `adb shell wm size 640x1136; adb
    shell wm density 320` for 320x568 dp, `wm size reset` after.

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

## 6e. Debug build only: lock-task override (design 16d experiments)

To test the gesture-navigation "App is not available" problem (`docs/design/16d-recents.md`), for example HOME and
OVERVIEW off in kiosk, or the recents package pinned, the **debug** build has a receiver that changes the lock-task
chrome without a server change. It lives only in `src/debug`. `assembleRelease` fails if it reaches a release APK
(`checkReleaseHasNoDebugHook`, `DebugHookAbsentTest`). `LockTaskChrome` stays the only writer: the receiver stores
an override in the debug prefs file `lock_task_debug_override`, and every chrome pass applies it on top of the
computed plan, unlocked and LOCKED, until it is reset. The override survives restarts.

```
P=me.vibb.launcher.debug
R=$P/com.kidslauncher.mdm.lock.LockTaskOverrideReceiver
# Gesture nav with HOME + OVERVIEW off (16d table row 2). NOTIFICATIONS needs HOME, so it goes too:
adb shell am broadcast -n $R --ei clear_features 14
# Pin the recents package (16d option H) and put OVERVIEW back:
adb shell am broadcast -n $R --ei add_features 12 --es extra_lock_task_packages com.google.android.apps.nexuslauncher
# Back to the computed plan:
adb shell am broadcast -n $R --ez reset true
adb logcat -s LockTaskDebug LockTaskChrome
```
- Bits: SYSTEM_INFO 1, NOTIFICATIONS 2, HOME 4, OVERVIEW 8, GLOBAL_ACTIONS 16, KEYGUARD 32, BLOCK_ACTIVITY_START_IN_TASK
  64. Each broadcast sets the **whole** override (a missing extra means nothing). Features = computed - clear + add.
  KEYGUARD is never cleared. OVERVIEW and NOTIFICATIONS are dropped without HOME, because AOSP refuses them.
- The extra packages only join an existing list (kiosk on, or LOCKED). With the kiosk off and unlocked, nothing is
  pinned.
- The broadcast re-applies the chrome at once. Its result data, and logcat `LockTaskDebug`, show the features and
  packages read back from DPM (`dumpsys activity activities | grep -A10 LockTaskController` shows the mode). Every
  later pass logs `Override ...: features A -> B` while the override is set.
- After `reset`, the next pass writes the computed plan. Without an `apply()` in this process (only the fallback),
  the extra packages stay until the next sync.
- Only shell can send it: the receiver needs `android.permission.DUMP`. Never on a kid's phone: release builds don't
  have it.

## 6f. Vibb music (design 21 steps 2-3): the bridge, or the debug feed

With the debug launcher as device owner and the server's music library ticked for the phone, the debug music app
gets everything through the launcher's bridge (step 3): install both debug APKs (the same debug key on one machine -
CI's debug APKs differ in key, QA #7), sync the launcher, and the music app follows on the `BRIDGE_CHANGED` nudge
(`adb logcat -s MusicSync MusicFiles MusicBridge BridgeSync`). Without a launcher, the music app takes a library from a
debug-only receiver (`src/debug`, `checkReleaseHasNoDebugFeed`, `SourceScanTest`). Build with
`cd music && ./gradlew assembleDebug` (-> `music/app/build/outputs/apk/debug/app-debug.apk`) and install it
next to the launcher; no device owner or server is needed for these checks.

```
# A local folder debug-feed/ with the library, listings, own files' audio and covers - the server's own
# answers, or server/testdata's snapshots edited by hand. With a server that has entries ticked for a
# phone (TOKEN = that phone's bearer):
#   curl -sH "Authorization: Bearer $TOKEN" $SERVER/api/devices/music/library > debug-feed/library.json
#   curl -sH "Authorization: Bearer $TOKEN" $SERVER/api/devices/music/entries/12/items > debug-feed/items/12.json
#   curl -sH "Authorization: Bearer $TOKEN" $SERVER/api/devices/music/files/5 > debug-feed/files/5
#   curl -sH "Authorization: Bearer $TOKEN" $SERVER/api/devices/music/covers/$HASH > debug-feed/covers/$HASH
# Optional debug-feed/config.json: {"mobile_data": true}
R=me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
adb shell am broadcast -n $R        # once: the app creates its (empty) debug-feed folder
adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
adb shell am broadcast -n $R
adb logcat -s VibbMusicDebugFeed WM-WorkerWrapper ExoPlayerImpl
# The Nytt notification needs the permission the device owner grants on a phone:
adb shell pm grant me.vibb.music.debug android.permission.POST_NOTIFICATIONS
# Language: the app follows the system, or per app:
adb shell cmd locale set-app-locales me.vibb.music.debug --locales nb
```
- Own files are checked against the library's size and SHA-256 and covers against their hash; a mismatch is
  logged and skipped. A listing that doesn't parse becomes the report's `bad_listing`. The sync runs at once
  (the bridge's nudge waits 15 s). Push a changed feed and broadcast again to test NY/Nytt (the first listing of
  an entry is never new; a later one with a new key is).
- What to check (21 §6 step 2): every screen at 320x569 dp in nb and en; the carousel (drag, the spring snap,
  a tap on a neighbour, a tap during a slide, "Remove animations" on: it jumps); resume (a cover tap continues,
  the list shows ✓ for heard items), shuffle on an own-files entry with resume off, the sleep timer (fade and
  pause; a manual pause cancels it); airplane mode (downloaded items play, others say "Ikke lastet ned - trenger
  Wi-Fi" and the queue moves on); restart (`adb shell am force-stop`, or a reboot): the now-playing bar is back,
  paused, and one tap continues. The report the step-3 bridge will send is
  `adb shell run-as me.vibb.music.debug cat files/music/report.json`.

## 6g. The launcher's security wave (`docs/review/security-review-2026-10-10.md`)

Not run yet: no emulator in the build sandbox. Use a snapshot you can restore - the wipe is real.

1. **Remote wipe (M8)**, API 34 and API 36 images, launcher as device owner and enrolled, with `adb logcat -s
   LocateCommands DevicePolicyManagerService` running: Locate page -> Wipe. Expect the emulator to reboot into the
   factory reset (the AVD comes back at the setup wizard) and no "Failed to wipe device" in the log. If a wipe is
   ever refused, the phone keeps running and the Locate page must say "Failed ...: wipe failed, the phone keeps its
   data: <the exception>" - never "Done". Restore the snapshot after each run.
   On API 34 also the update path (QA of the launcher wave, Low 4): install the build before this wave, make it device
   owner and enroll, update it in place to this build *without a reboot*: the Locate page says "Remote wipe works after
   the phone restarts once" (the report's `remote_wipe_ready` is false) and a Wipe comes back "Failed ...
   SecurityException"; after one restart the warning is gone and the wipe works.
   **Admin policies after an update** (the keyguard camera switch on an Android 16 emulator): with the build before
   this fix as device owner, update in place to this one without a reboot. `adb logcat -s AppEnforcer` may still show
   "Failed to set the keyguard camera feature" (on versions that check the admin's declared policy), and the device
   page's "Play and kiosk" card says "Restart the phone once: ..." (the report's `admin_policies_pending`). Restart:
   the log line is gone, the warning goes with the next report, and `adb shell dumpsys device_policy | grep -i
   keyguard` shows the camera feature disabled. A fresh install with this build has nothing pending.
2. **Command receipts (L12)**: Locate page -> Lock. The Recent commands row goes "Delivered ..., not confirmed yet" ->
   "Done ... (locked ...)" within seconds; `adb logcat -s CommandAnswers MdmSyncWorker` shows no "Couldn't send". A
   wipe (snapshot!) reads "Received ... - the phone is wiping now" before the emulator reboots. A lost answer: on the
   server, `UPDATE device_commands SET received_at = NULL, acknowledged_at = NULL, result = NULL, delivered_at =
   datetime('now', '-3 minutes') WHERE id = <the lock's id>`, then "Sync now" on the phone: the log says "delivered
   again - confirmed, not run twice", the screen doesn't lock again, and the row reads "Received ...".
3. **Catalog APK check (M10)**: a catalog app from a manual upload (Apps page), checked for the phone: it installs.
   The server refuses a release with another key itself, so to see the phone's check, change the pin under the cached
   release (no "Check now" - that would re-check the file): `UPDATE tracked_apps SET signer_sha256 = '<64 other hex>'`
   for a new upload of the app that isn't installed yet. The phone downloads it, logs "refused before installing:
   WRONG_SIGNER", doesn't install it, and the device page's row says "Refused - signed with another key than this
   app's"; the same with `package_name = 'org.example.other'` says "another Android package". With
   `signer_sha256 = NULL` the phone doesn't download it ("no package and signing key"). A key-rotated APK of a pinned
   app (`apksigner rotate`, `--rotation-min-sdk-version 33`) installs on API 33+: the pin is the old key, in the lineage.
   **No role prompt while the launcher is the phone app**: `adb shell dumpsys user | grep -i no_config_default_apps`
   shows the restriction on, and Home shows no caller-ID or redirection prompt (Android refuses them under it).
   Grant both roles with adb at provisioning (§4).
4. **Call screening without the phone-app role (L7)**, calls managed, `CALL_SCREENING` granted (§4): `adb shell cmd
   role get-role-holders android.app.role.CALL_SCREENING` lists the launcher. Device page -> Calls: no role warning.
   Then `adb shell cmd role remove-role-holder android.app.role.CALL_SCREENING $P`: after the next sync the page says
   "Call-screening role not active". Add it back, take the dialer role away (Calls unmanaged and managed again won't
   do it - `adb shell cmd role add-role-holder android.app.role.DIALER com.android.dialer` while `Block USB debugging`
   is off; the launcher takes the role back at its next apply, so call right away): `adb emu gsm call <unknown>` is
   rejected without ringing, an allowed contact rings in the system dialer, and a report sent meanwhile makes the page
   say "Incoming calls are still filtered". Without either role the page says "not filtered". Also call from a withheld
   number in that state (`adb emu gsm call` with a restricted presentation, or a real SIM): if it is rejected, the
   page's "withheld numbers may ring through" can become a firm statement.

## 6h. Vibb music on Sonos (design 26a): the fake speaker

The emulator's user-mode network carries no multicast (no SSDP) and every service on the host is `10.0.2.2` (the
host's loopback), so the music app's speakers come from `scripts/fake-sonos.py` on the VM: N fake speakers on N
ports, one household topology, a scripted transport. Its answers are built from the canned SOAP replies in
`music/app/src/test/resources/sonos/` - the files the JVM tests parse (`SonosFixturesTest`) - so the fake and the
tests can't drift apart. Python 3, stdlib only; it logs every SOAP call. The app's speaker cache is seeded through
the debug feed (`debug-feed/sonos.json`, `src/debug` only).

```
python3 -I scripts/fake-sonos.py --selftest            # fixtures parse, the fake answers itself (no emulator needed)
python3 scripts/fake-sonos.py --speakers Stua:1401,Kjøkken:1402 --print-seed > debug-feed/sonos.json
python3 scripts/fake-sonos.py --speakers Stua:1401,Kjøkken:1402            # leave it running; Ctrl+C ends it
#   --group Stua+Kjøkken     the two as one group (the picker's "Stua + Kjøkken" row, Stua the coordinator)
#   --script FILE            scripted events, timed from the first Play (see below)
#   --duration 1453          a track length when the DIDL has none (default: the speaker reads 0:00:00)
R=me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
adb shell am broadcast -n $R
adb logcat -s VibbMusicDebugFeed VibbSonos       # "sonos: 2 speaker(s) seeded into the cache"
adb shell run-as me.vibb.music.debug cat no_backup/sonos.json
```

Script lines are `t+<seconds> [@<speaker>] <event>` (the first speaker when none is named), one per line:

```
# grouped away: Stua becomes a member of Kjøkken's group (its transport reads x-rincon:RINCON_FAKE0140201400)
t+20 x-rincon:RINCON_FAKE0140201400
# taken over: someone else plays on the speaker
t+20 PLAYING "x-sonos-spotify:spotify%3atrack%3aGUEST?sid=9"
# migration (start with --group Stua+Kjøkken): Stua leaves its group, the stream moves on to Kjøkken, Stua
# reads STOPPED ""
t+30 move Kjøkken
# unreachable for a minute, then back
t+10 drop
t+70 back
# the stall retry: RelTime frozen while PLAYING
t+15 stall
# the volume changed from the Sonos app
t+15 volume 60
```

- The fake's transport: SetAVTransportURI sets the uri, Play plays (TRANSITIONING for 0.5 s first), RelTime runs in
  real time and the track ends by itself at its DIDL duration (STOPPED, uri kept: an episode end), Seek on a
  stopped transport is UPnP 701 (the hush and the settled seek), past the end 711; volume and mute.
- Step 1 (`sonos/` core) has no screen yet: check the selftest, the seeding and the cache file. With step 3a: the
  "Spill av på" sheet lists Stua and Kjøkken (stale + "Søker etter høyttalere…" when the fake is down), switch phone ->
  Stua -> Kjøkken -> phone (the fake's log shows one Stop per room left, a bookmark before it), the same tile, the
  grouped-away, taken-over and migration scripts, the sleep timer's 3-step ramp (three `SetVolume`, `Pause`, the volume
  back), the volume keys (`SetVolume` 0..100), an own-files entry ("Kan ikke spilles på Sonos", the phone stays) and a
  failed start (the fake stopped, a stale row picked: "Kan ikke spilles på Sonos", back to the phone, paused). The app
  talks to speakers only while a Wi-Fi network is up (a gate; no socket is bound to it, 26a §6.5): use an image with
  the emulated Wi-Fi (`AndroidWifi`); without it every room reads stale and nothing is tried. With step 3b: kill and
  restart the app while the fake plays (adopt: only reads in the fake's log - no SetAVTransportURI, Play or Seek - the
  right item, Home's RESUME starts nothing twice; a stopped or taken-over fake: the phone, paused), a Bluetooth play
  (`adb shell input keyevent MEDIA_PLAY`) with a room and no queue (the speaker starts, Exo doesn't), a time rule's
  suspension with and without the service (Stop, then `SetAVTransportURI x-rincon-queue:...`; `adb shell pm suspend`
  reaches `MY_PACKAGE_SUSPENDED` too), the service going (the task swiped while paused: one Stop), the volume keys
  against the cap (set on the device page: no `SetVolume` above it, the override lifts it at once; a `volume 90`
  script line is left alone; the override ending while the keys had the room above the cap: one `SetVolume` of the
  cap), the start cap (the fake with `--volume 90`, a cap of 40: the fake's log shows `GetVolume`, `SetVolume 40`
  before `SetAVTransportURI`; with `--group Stua+Kjøkken` both rooms get it), a call (`gsm call <number>` in the
  emulator console: Pause, and Play after `gsm cancel` only when it played) and the locks (`adb shell dumpsys power |
  grep vibb:sonos` while playing, gone 30 s after a pause). With step 3c (the parent's room scope):
  the device page's Music card shows "The phone hasn't seen any speakers yet" before the first report with rooms, then
  Stua and Kjøkken under "All rooms"; "All rooms" off and Kjøkken unticked -> after the next sync the sheet lists only
  Stua, a session playing on Kjøkken got a bookmark and one Stop and came home paused ("Rommet er ikke lenger
  tillatt"); with `--group Kjøkken+Stua` (or `Stua+Kjøkken`) and only Stua allowed the sheet shows no group row (a
  group needs every member allowed); a `group` script line putting Kjøkken under a playing, allowed Stua stops the
  session within a minute (the scope's topology read); with the
  sheet open while the sync lands, Kjøkken's row goes (the switch's own refusal - the message, nothing in the fake's
  log - is pinned in the JVM tests: the sheet no longer offers the room).
- Real speakers stay a device check (design 26a, "Device checks"): SSDP, discovery and control with the launcher's VPN
  filter on (no `EPERM`), the NRK service, Doze.

## 6i. Vibb music with Storytel (design 26b): the fake Storytel

No real account is used on the emulator: `scripts/fake-storytel.py` on the VM is the login, the asset call (the
302 the app captures) and a CDN serving silent WAV books with Range. Its answers come from the fixtures the JVM tests
read (`music/app/src/test/resources/storytel/`; fake values only: `k@example.invalid` / `hemmelig`, `FAKE-JWT-1`,
`FAKE-SIG-<n>`), and it logs every request with its Range and token. The app reaches it through the one debug switch
(`storytel_base` in the debug feed's `config.json`, `src/debug` only; read again at every process start, so a
download job in a fresh process still goes to the fake; only `10.0.2.2` or loopback is taken, anything else means the
real hosts). Python 3, stdlib only.

```
python3 -I scripts/fake-storytel.py --selftest                 # fixtures, the fake against itself (no emulator)
python3 -I scripts/fake-storytel.py                            # port 8730, books 111:20,222:5,333:5 (MB); Ctrl+C ends it
#   --books 111:300,222:5,333:5   cid:MB in reading order    --locked 222        403 without Location for that book
#   --drop-at 40                  each book's first transfer closed at 40 %
#   --token-ttl 30                a token is refused (403) 30 s after its mint
#   --refuse | --no-jwt           the login refused (403) | 200 without a jwt
#   --slow-mint 5                 every 302 after 5 s (step 4b)
#   --no-download 222,444         those books mint but the CDN answers 503: never on the phone, a room plays them (step 4b)
mkdir -p debug-feed/items
cp server/testdata/music_library.json debug-feed/library.json     # entry 5 "Kokosbananas" is storytel:series:26175
python3 -I scripts/fake-storytel.py --print-listing 5 > debug-feed/items/5.json
echo '{"storytel_base": "http://10.0.2.2:8730"}' > debug-feed/config.json
echo '{"email": "k@example.invalid", "password": "hemmelig"}' > debug-feed/storytel.json   # fake values only
R=me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
adb shell am broadcast -n $R        # "storytel: http://10.0.2.2:8730", "storytel: a login seeded (generation N)"
adb logcat -s VibbMusicDebugFeed WM-WorkerWrapper ExoPlayerImpl
adb shell run-as me.vibb.music.debug cat files/music/report.json     # "storytel", "downloads", "item_errors"
```

`storytel.json` is deleted once read (it sits on shared storage) and stored as the next login generation, so pushing
it again ends a refused login's hour like a new save on the server would. The library snapshot's other entries have no
listings here and stay hidden. What to check (design 26b §8 step 3):

- **Reading order**: the fake's log mints and fetches 111, 222, 333 in that order, one book at a time, one `asset`
  line per book; the tile appears with the first book ("Lydbok · fortsetter der du slapp"), the report reaches
  `have 3, want 3`.
- **The bookmark's book first**: play book 3 a little (the bookmark is on 333), push a library without entry 5 and
  broadcast (the books are deleted: `cache_bytes` drops, the tile goes - the switch-off case), then the library with
  entry 5 again and its listing: the fake's log fetches 333 first, then 111 and 222; the tile reads "del 3 av 3".
- **Resume after the URL expired**: `--books 111:50,222:5,333:5 --drop-at 40 --token-ttl 30`: book 111's first
  transfer is cut at 40 % (`dropped at 40 %`), the report's `item_errors` says `network`; about 2 minutes later the
  next job mints again (`asset 111: 302 ... token=FAKE-SIG-<n+1>`) and the CDN line shows `206 Range bytes=<N>-` with
  the new token - never a 200 from 0 - and the book completes (`have` counts it).
- **A book Storytel refuses**: `--locked 222`: `item_errors` holds `no_audio` for 222, 111 and 333 download; the
  next try of 222 is a day later.
- **A refused login**: `--refuse` (and a fresh login: push `storytel.json` again): the report says
  `"storytel": "refused"` and the entry's `waiting` is `login`; the fake's log shows one login, and no further login
  within the hour (a sync or a download job in that hour sends the fake nothing); after the hour one login again.
- **Offline**: airplane mode: the downloaded books play, the others are hidden (with books 1 and 3 only: the list
  shows those two), the tile and the player still say "del N av 3".
- **The 10-minute job limit**: `--books 111:300,222:5,333:5`; while book 111's CDN line streams, stop the job as
  WorkManager's limit would: `adb shell cmd jobscheduler timeout me.vibb.music.debug`. The next job mints a new token
  (`asset 111: 302 ... token=FAKE-SIG-<n+1>`) and the CDN shows `206 Range bytes=<N>-` - the partial kept, never a
  200 from 0 - and the book completes.
- **Copy only**: with the fake running, play a downloaded book, seek back and forth and skip to the other downloaded
  book: no new `asset` or `cdn` line appears in the fake's log while it plays. Then stop the fake (Ctrl+C): the books
  still play and seek.
- **No secrets** (none of these may match; the second is one recursive grep over the app's private data - Room's
  `music.db` and its `-wal`, Media3's `databases/exoplayer_internal.db` and its WAL, `shared_prefs`, `files/music/`
  with the report, `no_backup/` with the cache and `renderer.json`; it must name no file - `storytel.bin` is sealed and
  the WAV spans are zeros):

```
adb logcat -d | grep -E 'FAKE-JWT|FAKE-SIG|pwd=|token=|k@example|hemmelig'
adb shell run-as me.vibb.music.debug sh -c 'grep -raE -l "token=|FAKE-|pwd=|k@example|hemmelig" .'
```

  Media3 keeps the cache's metadata (`exo_len`, and the `exo_redir` a request whose uri differs from the answer's
  would write) in `databases/exoplayer_internal.db`; Storytel's CDN requests never follow a redirect and the minted
  URL is OkHttp's canonical form, so no token can land there.
- **On a Sonos room** (design 26b step 4b; §6h's fake speakers and their debug-feed seed together with this fake):

```
python3 -I scripts/fake-storytel.py --books 111:5,222:5,333:5,444:5,555:5 --no-download 222,444,555 --slow-mint 5
python3 -I scripts/fake-sonos.py                         # Stua, Kjøkken (§6h)
python3 -I scripts/fake-storytel.py --print-listing 5 --books 111:5,222:5,333:5,444:5,555:5 > debug-feed/items/5.json
adb shell run-as me.vibb.music.debug cat no_backup/renderer.json
```

  Books 1 and 3 download; 2, 4 and 5 stay off the phone (the CDN's `503`). **Reading the fake's `asset` lines**: the
  download worker mints a kept-off book on every retry (2 minutes doubling), each followed by a `cdn <cid>: 503` line;
  a Sonos play's mint has **no** following `cdn` line (the fake speaker never fetches). Then:
  - Book 2 on Stua (pick it from the list with Stua chosen): one `asset 222` without a `cdn` line, `fake-sonos`'s
    `SetAVTransportURI` with the DIDL's `res duration` and no `albumArtURI`; the player shows "Del 2 av 5".
  - Next and previous mint again (one new `asset` line each); pause and resume don't.
  - Next pressed three times during the 5 s mint: one `SetAVTransportURI`, for the last book pressed.
  - **A suspension during the mint** (design 26b M6): start book 2 on Stua and within the 5 s `adb shell pm suspend
    me.vibb.music.debug`: no `SetAVTransportURI` in `fake-sonos`'s log after it (the room comes home paused); `adb
    shell pm unsuspend me.vibb.music.debug` afterwards.
  - A stopped Stua (a `STOPPED ""` line in a `fake-sonos` script) then play: a new mint, from the bookmark.
  - Phone -> Stua from book 3 (on the phone): the room's queue holds all five ("Del 3 av 5", the list's five rows).
  - Kill the app while Stua plays book 2 (`adb shell am force-stop me.vibb.music.debug`), open it again: adopted
    (`fake-sonos` logs only `GetPositionInfo`/`GetTransportInfo`, no Stop, no `SetAVTransportURI`), the right book
    and place, no new `asset` line.
  - Stua -> phone on book 2: the phone paused, "Ikke lastet ned"; then the cover tap, Home's resume and a headset's
    play on the phone also say "Ikke lastet ned" and start nothing (the bookmark stays on book 2); a pick of book 1
    from the list plays it.
  - **A failed mint on a room**: `--refuse` (push `storytel.json` again for a fresh login) or `--locked 222`, then
    book 2 on Stua: no SOAP to the speaker, "Kan ikke spilles på Sonos", the phone paused; with `--locked 222` the
    report's `item_errors` holds `no_audio` for 222.
  - `renderer.json` holds `storytel:book:222` after the play and after the adopt - never a `token=`; logcat's
    `not-ours?` lines show scheme, host and path only; the no-secrets greps above still find nothing.
- Real Storytel stays a device check (design 26b, "Device checks"): the real login, a 300 MB book over Wi-Fi that
  drops for more than an hour, downloads under Doze, the Sonos items.

## 6b. Step 7: Play (optional)

FCM is gone since design 19: every build uses the SSE stream, and the device page's "Play and
kiosk" card says "Instant changes: connected since ..." while the phone holds it. For Play tests:

- Use an AVD with a **Google Play** system image (Play Store + Play services).
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
adb logcat -d -t 2000 | grep -iE "kidslauncher|vibb|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|CommandListener|PlayRuntime|Backstop|AppEnforcer|EmergencyDialer|LockTask|PinLock|UpdateFence|SelfUpdate|NotificationRule|CameraLock" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.
