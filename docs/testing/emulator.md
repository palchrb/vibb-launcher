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
1. Devices → Add Phone, name it, then under "Code" on Connect: **New Setup Code** (shown once, valid 15 minutes).
2. On the phone's Advanced page (Restrictions): turn **"Block USB debugging" OFF** (otherwise adb dies on the first sync).
3. Set a Parent PIN (the phone's Lock Screen & PINs page, 6-10 digits).

## 3. Emulator: fresh start and snapshot

- Wipe data in Device Manager (no Google account may be on it), boot, skip setup.
- Take an AVD snapshot now ("clean") so you can go back in seconds.
- Never set a screen lock (PIN) on the AVD before provisioning: Android then disables escrow tokens for good on
  that user, and design 59's Child PIN (the reset token) can't be set afterwards. Android's PIN is set by the
  launcher through the PWA's Child PIN once the phone is enrolled (the android-mode switch).
- Leave Advanced › Restrictions' "Block screenshots" off on a test AVD (it is off by default):
  with it on `adb exec-out screencap` is black, the phone's own screenshot is refused and new Recents cards are blank
  (2026-10-23). The emulator console's `adb emu screenrecord screenshot` still works (it reads the host's frame).

**`jelly36`: the AVD that matches the user's own** (`jellystar2`, since 2026-10-23). The Google Play Android 16 image
(Pixel SystemUI) at 480x854 and 240 dpi, made **without** a device profile, so it has no display cutout and the plain
24 dp (36 px) status bar the user sees. `handy36`'s pixel_7 profile instead gives a 136 px status bar from its cutout,
and that moves SystemUI's keyguard clock 76 px down (design 59d §8.15). Use `jelly36` for anything the user compares by
eye: the glance's clock twin, the face's frames, the bottom-edge swipe.

```sh
avdmanager create avd -n jelly36 -k "system-images;android-36;google_apis_playstore;x86_64" --force   # answer "no"
# then in the AVD's config.ini (replace these keys, keep the rest): ~/.android/avd/jelly36.avd/config.ini
# (avdmanager's default), or ~/.config/.android/avd/jelly36.avd/config.ini where Android Studio keeps its AVDs:
hw.lcd.width = 480
hw.lcd.height = 854
hw.lcd.density = 240
skin.name = 480x854
skin.path = _no_skin
hw.ramSize = 2048
hw.keyboard = yes
# REQUIRED - "yes" (the default without a device profile) means no navigation bar (see below); keep the value bare
hw.mainKeys = no
hw.gpu.enabled = yes
hw.gpu.mode = swiftshader_indirect
disk.dataPartition.size = 6442450944
showDeviceFrame = no
# start (headless):
emulator -avd jelly36 -port 5556 -wipe-data -no-window -no-audio -gpu swiftshader_indirect -no-snapshot-save -no-boot-anim
```

**`hw.mainKeys = no` is required** (design 59d §8.16). `avdmanager` without a device profile writes `hw.mainKeys = yes`
(`qemu.hw.mainkeys=1`): the AVD then has hardware keys and SystemUI hosts **no navigation bar and no taskbar delegate**.
Quickstep still runs gesture navigation, but nothing turns lock task's `DISABLE_HOME`/`DISABLE_RECENT` into its
`home_disabled`/`overview_disabled` flags, so every bottom-edge swipe over the lock goes through to the system (the
user's `jellystar2`, 2026-10-23). A real phone without hardware keys - the Jelly Star - has the navigation bar. **On your
own AVD made without a device profile**: set `hw.mainKeys = no` in its `config.ini` and cold boot it. The file is
`~/.config/.android/avd/<name>.avd/config.ini` with newer Android Studio on Linux (the user's machine), else
`~/.android/avd/<name>.avd/config.ini`; `emulator -list-avds` names the AVDs and `find ~ -name config.ini -path
'*avd*'` finds the file. Check with
`adb shell getprop qemu.hw.mainkeys` (must be `0`) and `adb shell dumpsys statusbar | grep taskbar` (a `taskbar
mDisabledFlags` line must be there).

The density is 240 already (no `wm density`). Gesture navigation is the default. Check the bars with
`adb shell dumpsys window | grep -E 'type=(statusBars|displayCutout) frame'`: `statusBars frame=[0,0][480,36]`
and no cutout. For other status-bar heights on the same AVD: `cmd overlay enable-exclusive --category
com.android.internal.display.cutout.emulation.tall` gives 72 px and `.hole` gives 136 px. Disable it again before a
run that should look like the user's. SystemUI's status bar can lag the cutout for a while after a switch, so re-read
the frames, or reboot.

## 3b. The Jelly Star's size, font scale, time zone, adb-installed apps (design 34 step 0)

**Density.** The AVD `handy36` runs 480x854 at 320 dpi = 240x427 dp; the Jelly Star is 480x854 at 240 dpi =
320x569 dp (what `PinLockLayoutTest`, `FitTest` and `KidHeaderLayoutTest` assume). At 320 dpi the call screen's
Hang up is off the bottom, the incoming call's buttons are cut in half and Home's labels ellipsize. Check every
screen at the Jelly Star's size:

```sh
adb shell wm density 240                         # 320x569 dp, the Jelly Star
adb shell settings put system font_scale 1.3     # large text; 2.0 for the floor (design 34 P4, P7, P10)
# Afterwards - back to the AVD's own values:
adb shell wm density reset
adb shell settings put system font_scale 1.0
```

The AVD's own 240x427 dp is still a useful stand-in for a large font scale. Screenshots of the lock (secure
window) go through the emulator console (§6c item 10).

**An `adb install`ed app is held** (expected): the launcher's install check outside a Play window blocks any app
another installer put on the phone (`relock_blocks`, reason `outside_play`, design 24b) - it stays hidden whatever
its tick. Let it out from the PWA: the phone's summary › Blocked Apps › **Allow these apps…** (the phone lets out
exactly the listed apps with the keys shown).

**Time zone** (design 34 P13): the launcher forces automatic time zone, so the emulator's network zone wins over
Settings. Read it and give Phone Setup the same one, so the PWA's "Locked until ..." can show:

```sh
adb shell getprop persist.sys.timezone           # e.g. Europe/Oslo, or GMT on a fresh AVD
```

Then Settings › Phone Setup › Time zone = that value.

## 4. Provision (adb on the VM)

A test server for the debug launcher sets the debug component (add it to §2's `cargo run` line):
`LAUNCHER_ADMIN_COMPONENT=me.vibb.launcher.debug/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver`. The server
creates the launcher's catalog row at its first start, pinned to that component's package, so the row refuses the
release builds (`me.vibb.launcher`) instead of installing the release Vibb One next to the debug one (or untick
Enabled on that row under Apps). For §6d's manual launcher row, take the flag off the seeded row first (one launcher
row; while any row is flagged nothing is seeded again).

```sh
P=me.vibb.launcher.debug
adb install -r ~/repos/handy/launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner $P/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION $P
# Security review L7: the incoming-call filter when the launcher isn't the phone app.
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING $P
adb shell cmd notification allow_listener $P/com.kidslauncher.mdm.badges.BadgeListenerService
# Design 34 P12 (step 6): the PWA's clock format (Follow / 24 h / 12 h) goes to TIME_12_24 through the
# WRITE_SETTINGS app-op, which a device owner can't grant itself (TIME_12_24 isn't on setSystemSetting's list;
# kid Settings' brightness is, so it needs no app-op). Without it the PWA's Home Screen warns. Launcher round 9 §4:
# kid Settings' touch sounds and vibration need it too (read-only without it; docs/setup/android-settings.md).
# On a phone the parent grants it from Vibb One's Settings ("Modify system settings" row, under Background
# connection, during a pause - write-settings-row, 2026-10-24); adb is the emulator's shortcut.
adb shell appops set $P WRITE_SETTINGS allow
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
from Settings › Phone Setup ("Phone language", `PROVISIONING_LOCALE` default `nb_NO`; "Time zone", `PROVISIONING_TIME_ZONE`
default `Europe/Oslo`). QR provisioning itself (the launcher now has the Android 12+
`GET_PROVISIONING_MODE`/`ADMIN_POLICY_COMPLIANCE` activities) is **[needs device test]** on the
Jelly Star and stock GMS phones; adb above stays the fallback.

Screen lock: since step 10 the phone has **no Android screen lock** - handy's own PIN lock
replaces it (set the Child PIN on the phone's Lock Screen & PINs page; the Parent PIN must be set
first). An Android PIN keeps handy's lock off (no double lock) and Lock Screen & PINs says
"Android's screen lock is still on". Remove one with `adb shell locksettings clear --old <PIN>` (or Settings ->
Security -> Screen lock -> None). Only for the old keyguard tests (09 doc, B2:
`KEYGUARD_DISABLE_SECURE_CAMERA`) set an Android PIN on purpose, and remove it afterwards.

Press Home, choose the launcher - or start it with `adb shell am start -a android.intent.action.MAIN -c
android.intent.category.HOME -p $P`. (Until 2026-10-24 Home had to start on screen once **before** the enrolment:
a phone enrolled from Settings before that start lost its whole preferences file at the next process start, the
token included - glance-topinset run, 2026-10-23. Since write-settings-row the first-launch reset skips an enrolled
phone, `firstLaunchResetDue`, design 59d §8.17; the order no longer matters.) In its Settings (open until the first policy arrives): server URL `http://10.0.2.2:3100`, enrollment code
from step 2. Take a snapshot ("enrolled").

## 5. Simulated calls (emulator console via adb)

Always give numbers in international form with `+` (e.g. `+4781549300`): without it the
emulator presents `4781549300` as a national number, which the launcher normalizes to
`+474781549300` and doesn't match the contact.

```sh
adb emu gsm call +4791234567     # incoming call from that number
adb emu gsm cancel +4791234567   # caller hangs up
adb shell am start -a android.intent.action.CALL -d tel:112   # (emergency; the emulator fakes it)
```

Add contacts with matching numbers on the phone's Calls & Contacts page in the PWA (Add Contact, +47 9123 4567;
the default country code is on Settings › Phone Setup).

SMS (emulator console): `adb emu sms send <number> <text>`, e.g.
`adb emu sms send 4791234567 hello` or a named sender `adb emu sms send Vipps kode 1234`.
Step 9 keeps SMS **on/off only** (the SMS allowlist, part A, is postponed): with SMS off on the
Calls & Contacts page nothing arrives (`DISALLOW_SMS`); with SMS on Messages shows every message.

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
enrolled and About This Phone saying "Online since ...", freeze the server for more than
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

7. Step 9: manage calls ON -> Calls & Contacts shows the row "Waiting for the phone to confirm" and
   reloads itself until the next report, then shows the real state (never "waiting" for more
   than 5 minutes). `adb logcat -b events | grep -E "am_kill|wm_create_activity"` shows no
   `am_kill` of our package when calls go ON -> OFF -> ON.
8. Step 9 kiosk app block (Advanced › App screens, "Block other apps' screens", on by default), kiosk on: from an
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

Setup: device page -> Lock Screen & PINs -> set the Parent PIN (6-10 digits), then the Child PIN
(4-6 digits; the first one also turns "Block safe mode" on). Sync (or wait for the
nudge). The emulator must have no Android screen lock:

```sh
adb shell locksettings clear --old 1234          # only if an Android PIN/pattern was set
adb shell locksettings get-disabled              # true once our lock switched the keyguard off
adb shell dumpsys device_policy | grep -iE "keyguard|lockTask"
```

Screen off/on: `adb shell input keyevent 26` (power). Each screen-off locks; the next
screen-on must show the PIN lock straight away, never a frame of the app underneath. Since design 25
the lock comes up on its glance page (clock, date, the music tile, missed calls and messages):
a swipe up (design 59d step J, e.g. `input swipe 240 600 240 300 150` at `wm density 240`; the "Lås opp" button
before it; since §8.13 also from the gesture handle `<package>:id/lock_glance_handle` at the bottom, e.g.
`input swipe 240 838 240 300 150`) or a row leads to the keypad, which UI
dumps don't show while the glance page covers it.

1. **Lock appears**: in an allowed app, `input keyevent 26` twice -> PIN lock; the right PIN ->
   back in the same app with its state. Repeat 20x (kiosk on, then kiosk off: the phone's
   Apps page with every app off or kiosk unpinned on a test server).
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
7. **Remote lock**: Locate tab -> Lock -> lock shown, screen off, the Recent row "Lock · Done" with "Locked with
   the Child PIN" under it (the phone's answer "locked (handy lock)" in its `title`).
8. **Migration**: set an Android PIN -> next sync: Lock Screen & PINs "Android's screen lock is still on",
   our lock off. `adb shell locksettings clear --old <PIN>` -> our lock active within seconds
   (onPasswordChanged), without a sync.
9. **Crash guard** (debug only): three crashes of the lock screen within 2 minutes (`adb shell am
   crash $P` right after each screen-on) -> lock off, Lock Screen & PINs "Lock turned off after crashes".
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

Setup: the phone's Advanced -> "Updates & notifications": tick **Update fence** and
**Notification filter** (both default off until A3/B5 pass). A test self-update for the debug
build: `./gradlew assembleDebug -PversionCode=<higher than installed>`, PWA Apps -> add a manual
app (package `me.vibb.launcher.debug`), turn off "This is the launcher app" on the seeded Vibb One row
(§4) and turn it on for the new app on its page, upload the APK
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
- [ ] **Lock switched off while LOCKED**: with the screen off, remove the Child PIN on Lock Screen & PINs (sync), or set
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
# Optional debug-feed/config.json (design 30): {"stream_mobile_data": true, "download_mobile_data": false,
#   "storage_limit_gb": 2} - the two switches and the limit in GB (0 = none); a bare "mobile_data" sets both switches
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
  an entry known at the baseline (closed by the first play) is never new; a later one with a new key is). The
  design 29 checks (a new entry is news) are below.
- What to check (21 §6 step 2): every screen at 320x569 dp in nb and en; the carousel (drag, the spring snap,
  a tap on a neighbour, a tap during a slide, "Remove animations" on: it jumps); resume (a cover tap continues,
  the list shows ✓ for heard items), shuffle on an own-files entry with resume off, the sleep timer (fade and
  pause; a manual pause cancels it); airplane mode (downloaded items play, others say "Ikke lastet ned - trenger
  Wi-Fi" and the queue moves on); restart (`adb shell am force-stop`, or a reboot): the now-playing bar is back,
  paused, and one tap continues. The report the step-3 bridge will send is
  `adb shell run-as me.vibb.music.debug cat files/music/report.json`.

**Mobile data, the stream saved as it plays, the storage limit (design 30 §8)**, with the debug feed's
`config.json` (above) or the launcher's bridge and the phone's Music page (`/devices/{id}/music`). Tools:
- network: Wi-Fi is unmetered (through the launcher's VPN on a managed phone); `adb shell svc wifi disable` leaves the
  emulated LTE (metered); `adb emu gsm data roaming` / `adb emu gsm data home` for roaming;
- the audio cache: `adb shell run-as me.vibb.music.debug du -s no_backup/media`; the report's `audio_bytes` and
  `extra_bytes`: `run-as ... cat files/music/report.json`;
- a fake podcast host: `python3 -I scripts/fake-audio.py [--vary]` (port 8731, episodes `ep1..ep3` of 20 MB) and its
  listing `python3 -I scripts/fake-audio.py --print-listing 6 > debug-feed/items/6.json`, for a `lan` RSS entry 6 in
  `debug-feed/library.json` (a public entry may not reach 10.0.2.2, 21b §2.7). Plain http works since the app's
  network security config allows cleartext (qa-30-s3b-code H2); before it, every http fake failed with
  `CLEARTEXT communication ... not permitted`. For the extras, give the fake more episodes than the depth:
  `--episodes ep1,ep2,ep3,ep4,ep5,ep6,ep7` at depth 5, or depth 1.

Checks (S = streaming switch, D = downloads switch; depth 5, limit 2 GB unless noted):
- **S on, D off, Wi-Fi off:** a not-downloaded episode plays and `du` grows while it plays; the report's
  `downloads[].waiting` is `wifi` and no download runs (`adb logcat -s WM-WorkerWrapper`).
- **S off, D on, Wi-Fi off:** the episode says "Ikke lastet ned - trenger Wi-Fi" and the queue moves on; downloads
  (and §6i's fake Storytel books) run. **Roaming, both on:** nothing streams or downloads; copies play.
- **Offline at once:** play an episode outside the keep set (with the fake above: `ep1` at depth 5, the oldest of
  seven; on a real feed episode 6), not downloaded, to its end on Wi-Fi, then the next item: in airplane mode it
  plays, its list shows it on the phone, and after the next sync `extra_bytes` > 0.
- **Not twice:** S on, D off, on LTE: stream half of a not-downloaded keep-set episode, then play another entry.
  Wi-Fi on: the job fetches only the second half (`adb shell dumpsys netstats detail`, the app's uid), then
  `downloads[].have` counts it.
- **Depth 0:** an entry set to "Ingen", an episode streamed: `du` doesn't grow. Old copies go at the next sync; the
  one playing and its next once the player lets go.
- **HLS:** an NRK serie episode streamed to its end plays in airplane mode with the copy's rendition.
- **The limit and the floor:** with a few extras, set the limit to 1 GB and sync: the least recently played extras
  go, the keep set and the playing item stay; a depth whose keep set passes 1 GB: the card warns; fill the storage
  to under 1 GiB free (`adb push` a large file to `/sdcard/Download`) and sync: extras go, a stream stops writing and
  still plays.
- **A changed source:** `fake-audio.py --vary`: stream half of `ep1`, pause long enough to drop the connection (or
  `adb shell svc wifi disable` and back), play on: it plays on uncached (`adb logcat -s ExoPlayerImpl` shows no
  error dialog; the fake's log a resumed Range against another total), its row is gone, and the next sync's orphan
  sweep empties its bytes (`du`).

**A new entry is news (design 29 §7)**, with the launcher's bridge (§6i for Storytel). Grant `POST_NOTIFICATIONS`
and watch `adb shell dumpsys notification --noredact | grep -A3 me.vibb.music` and the launcher's Home badge. Except
in the first check, play any item for 20 s first: the stored position closes the baseline. **Setting up a real phone:
tick everything first, then test-play** - the first stored play closes the baseline for good, so every entry ticked
after a parent's test play counts as "1 ny" (design 29 §8.4, qa-29-code L5).
- **Fresh install, no flood:** uninstall the debug music app, install it with nothing ticked, then tick at least 6
  entries **one at a time** (podcasts, an NRK serie, an own entry, the Storytel entry), letting a sync run between
  ticks. Expect no notification, an empty Nytt and no NY. Wait for a podcast's next episode (or push a longer listing
  through the debug feed) before playing: still nothing (an open baseline only acknowledges). Repeat after
  `adb shell pm clear me.vibb.music.debug`. Then play something for 20 s and tick one more new show: "1 ny".
- **A new NRK serie** (a short one, natural order): tick it, wait until the server has listed it, sync the
  launcher. After the music app's sync: exactly one notification, "1 ny i Musikk", Home's badge at 1, Nytt holds one
  row (episode 1) and its tile shows NY. Tap the row: episode 1 plays, the notification and NY go.
- **A new podcast:** the same with a 30+ episode NRK podkast: "1 ny", the newest episode.
- **A new Storytel series** (§6i, `--books 111:300,222:300,333:300`): take the baseline with a library without entry
  5, then play something. Push the library with entry 5 and its listing: while 111 downloads, no notification and no
  tile; "1 ny i Musikk" with book 111 in Nytt **while 222 is still downloading**. Push a library without entry 5,
  then with it again: no notification (the same key). With `--locked 111`: once 111 is `no_audio`, the download job
  asks for a sync (15 s settle, `repickWanted`), that sync makes 222 the item, and "1 ny" comes once 222 is
  downloaded - not at the 6 h backstop.
- **A new own-files entry:** create it and upload 3 files: "1 ny" (the first file) once copied. Upload 2 more within
  the hour: still "1 ny".
- **Nothing ticked:** untick everything (the app empties), then tick two of the old entries and one brand-new show:
  "1 ny" only.
- **Restore that reuses an id** (`music_entries.id` is `AUTOINCREMENT`, so only a restore hands an id out twice):
  1. Back up the server.
  2. Create show C (it takes id N) and let the phone sync: "1 ny"; play it or clear it.
  3. Stop the server, restore the backup, start it, and create show E at once, before the phone syncs: E takes id N
     again.
  4. Sync: "1 ny" for E's newest only (none of C's or E's back catalogue), and `adb logcat -s Sync` shows "Entry ids
     that now carry another key, reset: [N]".
- **A fresh server with the same shows** (design 22, "Re-enrolling"): no notification for the shows the phone knew
  (the same keys under new ids); re-uploaded own-files entries get new keys and give "1 ny" each (§1.4).
- **An own file's copy under a reused id** (design 29 step 4): replace an own file's copy with another file's bytes,
  keeping its name, and delete its record (`adb shell run-as me.vibb.music.debug sh -c 'cp no_backup/own/<other id>
  no_backup/own/<id> && rm no_backup/own/<id>.sha256'`): a copy without a record is hashed once, as one from before
  this version is. The record alone is trusted, so swapping only the bytes isn't caught; after a real restore it is
  the library's SHA-256 that changes. The next sync logs "copy is another file" (`adb logcat -s BridgeSync`), the
  report no longer names it held, the launcher fetches the right file and a later sync copies it; it plays the right
  audio.
- **Unchanged:** a new episode in a known podcast (outside a 24 h window) still adds one; a swipe hides the
  notification until the count changes; "Fjern alle" cancels it; tapping a Nytt row while a sync runs doesn't bring
  the row back.

## 6g. The launcher's security wave (`docs/review/security-review-2026-10-10.md`)

Not run yet: no emulator in the build sandbox. Use a snapshot you can restore - the wipe is real.

1. **Remote wipe (M8)**, API 34 and API 36 images, launcher as device owner and enrolled, with `adb logcat -s
   LocateCommands DevicePolicyManagerService` running: Locate tab -> Erase Phone… (type the name). Expect the emulator to reboot into the
   factory reset (the AVD comes back at the setup wizard) and no "Failed to wipe device" in the log. If a wipe is
   ever refused, the phone keeps running and the Recent row must say "Erase Phone · Failed" with "Not erased: the
   phone keeps its data" (the exception in its `title`) - never "Done". Restore the snapshot after each run.
   On API 34 also the update path (QA of the launcher wave, Low 4): install the build before this wave, make it device
   owner and enroll, update it in place to this build *without a reboot*: the Locate tab says "Erasing works after
   the phone restarts once" (the report's `remote_wipe_ready` is false) and an erase comes back "Failed" (the
   SecurityException in the row's `title`); after one restart the warning is gone and the wipe works.
   **Admin policies after an update** (the keyguard camera switch on an Android 16 emulator): with the build before
   this fix as device owner, update in place to this one without a reboot. `adb logcat -s AppEnforcer` may still show
   "Failed to set the keyguard camera feature" (on versions that check the admin's declared policy), and the device
   page's Attention and Advanced › App screens say "Restart the phone once" (the report's `admin_policies_pending`). Restart:
   the log line is gone, the warning goes with the next report, and `adb shell dumpsys device_policy | grep -i
   keyguard` shows the camera feature disabled. A fresh install with this build has nothing pending.
2. **Command receipts (L12)**: Locate tab -> Lock. The Recent row goes "Sent" ("Not confirmed yet.") ->
   "Done" (its answer under it) within seconds; `adb logcat -s CommandAnswers MdmSyncWorker` shows no "Couldn't send". A
   wipe (snapshot!) reads "Erasing" ("The phone got it (10:58). An erased phone stops reporting.") before the emulator
   reboots. A lost answer: on the
   server, `UPDATE device_commands SET received_at = NULL, acknowledged_at = NULL, result = NULL, delivered_at =
   datetime('now', '-3 minutes') WHERE id = <the lock's id>`, then "Sync now" on the phone: the log says "delivered
   again - confirmed, not run twice", the screen doesn't lock again, and the row reads "Received".
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
#   --duration 90            a track length when the DIDL has none - since design 53-0 every podcast and NRK item
#                            (vibb's DIDL: a duration only for a Storytel book); without it a podcast reads 0:00:00
#                            and never ends: start it with `--duration 90` for the advance steps (the next episode,
#                            "Til slutten", repeat)
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
  real time and the track ends by itself at its DIDL duration - a Storytel book's - else at `--duration` (STOPPED,
  uri kept: an episode end; a podcast's DIDL carries no duration since design 53-0, vibb's), Seek on a
  stopped transport is UPnP 701 (the hush and the settled seek), past the end 711; volume and mute.
- **No event socket in the fake** (design 53 §3, round 53-a): the app dials `10.0.2.2:1443` and is refused - logcat
  `VibbSonos` shows `sonos socket: ended (REFUSED ...)` with the backoff (1, 2, 5, 15 s) and, after the fifth, `parked
  for 6 h` - so the emulator checks the polling fallback: with the player screen up and a room playing the fake's log
  shows a GetPositionInfo every second (none with the screen on another tab or off), and with `--duration 90` the next
  episode starts at the poll after the end (the STOPPED advance). The socket path itself is checked on the user's
  speakers (design 53 §5).
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
  the phone's Music page (Sonos speakers) shows "No speakers seen yet." before the first report with rooms, then
  Stua and Kjøkken under "All rooms"; "All rooms" off and Kjøkken switched off -> after the next sync the sheet lists only
  Stua, a session playing on Kjøkken got a bookmark and one Stop and came home paused ("Rommet er ikke lenger
  tillatt"); with `--group Kjøkken+Stua` (or `Stua+Kjøkken`) and only Stua allowed the sheet shows no group row (a
  group needs every member allowed); since design 53-b (the user's Q4) a `group` script line putting Kjøkken under a
  playing, allowed Stua is a room grouped in by hand: within a minute (the group watch) the session plays on, the
  sheet shows "Stua + Kjøkken" with Kjøkken's "−", no other out-of-scope row gets a "+"; with the
  sheet open while the sync lands, Kjøkken's row goes (the switch's own refusal - the message, nothing in the fake's
  log - is pinned in the JVM tests: the sheet no longer offers the room).
- **Grouping in "Spill av på"** (design 53-b; the fake answers `x-rincon:` joins and `BecomeCoordinatorOfStandaloneGroup`,
  `--selftest` 4b). Start the fake with three rooms (`--speakers Stua:1401,Kjøkken:1402,Barnerom:1403 --duration 90`),
  play on Stua, open the sheet: Stua selected "Spiller nå", a "+" on Kjøkken and Barnerom ("Trykk for å bytte hit"; a
  room playing something else - `@Barnerom PLAYING "https://x.example/a.mp3"` in a script - "Spiller noe annet", read
  once per opening: the fake's log shows one `GetTransportInfo` per other row). "+" on Kjøkken: its Spinner, the
  fake's log `GetVolume` (with a cap, `SetVolume <cap>` when louder) then `SetAVTransportURI x-rincon:<Stua>` on
  Kjøkken, then the block "Stua + Kjøkken" with "Spiller nå i begge", the "Begge" slider and a line per room with its
  "−" and slider; the bar's label "Stua + Kjøkken". "+" on Barnerom playing: its stream stops, it joins. The "Begge"
  slider: one `SetVolume` per room at a time on Stua (our actor) and the others; a member's slider: that room only; the
  volume keys: ±3 on every room. "−" on Kjøkken: `BecomeCoordinatorOfStandaloneGroup` on Kjøkken, Stua plays on.
  "−" on Stua (the coordinator): the fake moves the stream to Kjøkken, the app follows within a few seconds (logcat
  `stream moved`), the label "Kjøkken", `renderer.json` updated. A switched-off room (`@Kjøkken drop`): its "+" shows
  "Fikk ikke kontakt med Kjøkken - er den på?" after a few seconds, while Pause and the volume keys on Stua answer at
  once. With the fake there is no event socket: the outside cases (a `group`/`move` script line) show at the group
  watch (60 s), not at once.
- Real speakers stay a device check (design 26a, "Device checks"): SSDP, discovery and control with the launcher's VPN
  filter on (no `EPERM`), the NRK service, Doze.

## 6i. Vibb music with Storytel (design 26b): the fake Storytel

No real account is used on the emulator: `scripts/fake-storytel.py` on the VM is the login and the bookshelf (the
server's, §6i B), the server's session route for the phone alone (`/fake/jwt`), the asset call (the 302 the app
captures) and a CDN serving silent WAV books with Range. Its answers come from the fixtures the tests read (the login
and the bookshelf from `server/testdata/storytel/`, `/fake/jwt` from `server/testdata/storytel_token_answers.json`, the
asset call from the music app's; fake values only: `k@example.invalid` / `hemmelig`, `FAKE-JWT-<n>`, sessions
`1:<16 hex>`, `FAKE-SIG-<n>`), and it logs every request with its Range and token. Since design 58 step 2 **the phone
never logs in**: it uses the server's JWT, in memory only - through the launcher's bridge when managed (§6i B), and
from the fake's `/fake/jwt` (generation 1) when the app runs alone on the debug feed (part A, here). The app reaches
the fake through the one debug switch (`storytel_base` in the debug feed's `config.json`, `src/debug` only; read
again at every process start, so a download job in a fresh process still goes to the fake; only `10.0.2.2` or
loopback is taken, anything else means the real hosts; with it set only a `FAKE-JWT-` is ever sent). Python 3,
stdlib only.

```
python3 -I scripts/fake-storytel.py --selftest                 # fixtures, the fake against itself (no emulator)
python3 -I scripts/fake-storytel.py                            # port 8730, books 111:20,222:5,333:5 (MB); Ctrl+C ends it
#   --books 111:300,222:5,333:5   cid:MB in reading order    --locked 222        403 without Location for that book
#   --drop-at 40                  each book's first transfer closed at 40 %
#   --token-ttl 30                a token is refused (403) 30 s after its mint
#   --refuse                      the login refused (403) and /fake/jwt `refused` (409)
#   --no-jwt | --login-5xx        the server's login: 200 without a jwt | 503 (design 58: an outage, not a refusal)
#   --jwt-ttl 300                 /fake/jwt's JWT life (default 3540); it ages, and below half of it (at most 15 min)
#                                 a plain ask gets the next JWT, as the server does
#   --off | --busy 30 | --jwt-down   /fake/jwt answers `off` (404) | `busy` 30 s (429) | `unreachable` (502)
#   --revoke-after 1              after the first accepted asset call the JWT answers 401 until a stale /fake/jwt
#                                 (or the server's login) issues FAKE-JWT-2
#   --slow-mint 5                 every 302 after 5 s (step 4b)
#   --no-download 222,444         those books mint but the CDN answers 503: never on the phone, a room plays them (step 4b)
mkdir -p debug-feed/items
cp server/testdata/music_library.json debug-feed/library.json     # entry 5 "Kokosbananas" is storytel:series:26175
python3 -I scripts/fake-storytel.py --print-listing 5 > debug-feed/items/5.json
echo '{"storytel_base": "http://10.0.2.2:8730"}' > debug-feed/config.json
R=me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
adb shell am broadcast -n $R        # "storytel: http://10.0.2.2:8730"
adb logcat -s VibbMusicDebugFeed StorytelLegacy WM-WorkerWrapper ExoPlayerImpl
adb shell run-as me.vibb.music.debug cat files/music/report.json     # "storytel", "downloads", "item_errors"
```

A `storytel.json` left in the feed from before design 58 is deleted unread ("storytel.json: the phone takes no login
since design 58 - deleted unread"). The session lives in the process's memory: `adb shell am force-stop
me.vibb.music.debug` starts the next run with no JWT (one `jwt` line again), which ends a `refused` or `busy` wait
too. The library snapshot's other entries have no listings here and stay hidden. What to check (design 26b §8 step 3,
design 58 §9):

- **Reading order**: the fake's log mints and fetches 111, 222, 333 in that order, one book at a time, one `asset`
  line per book and **one `jwt` line per process**, not per book (no `login` line at all: the phone never logs in);
  the tile appears with the first book ("Lydbok · fortsetter der du slapp"), the report reaches `have 3, want 3`.
- **The bookmark's book first**: play book 3 a little (the bookmark is on 333), push a library without entry 5 and
  broadcast (the books are deleted: `cache_bytes` drops, the tile goes - the switch-off case), then the library with
  entry 5 again and its listing: the fake's log fetches 333 first, then 111 and 222; the tile reads "del 3 av 3".
- **Resume after the URL expired**: `--books 111:50,222:5,333:5 --drop-at 40 --token-ttl 30`: book 111's first
  transfer is cut at 40 % (`dropped at 40 %`), the report's `item_errors` says `network`; about 2 minutes later the
  next job mints again (`asset 111: 302 ... token=FAKE-SIG-<n+1>`) and the CDN line shows `206 Range bytes=<N>-` with
  the new token - never a 200 from 0 - and the book completes (`have` counts it).
- **A book Storytel refuses**: `--locked 222`: `item_errors` holds `no_audio` for 222, 111 and 333 download; the
  next try of 222 is a day later.
- **A refused session** (design 58 §9): `--refuse` (force-stop the app first, so it holds no JWT): the report says
  `"storytel": "refused"` and the entry's `waiting` is `login`; the fake's log shows one `jwt: refused` line and none
  more in that process within the wait (`retry_after_s` 2400: a sync or a download job in it sends the fake nothing);
  the download job comes back at the wait's end on its own (`adb shell dumpsys jobscheduler | grep -A3
  me.vibb.music.debug` shows it scheduled).
- **An outage** (design 58 §9, QA 58-2): `--jwt-down` (after a force-stop): one `jwt: unreachable` line and a second
  5 s later (the prompt retry), the run stops with no item error and the report still says `ok`; restart the fake
  without the flag: about 2 minutes later the job comes back by itself and downloads (one `jwt` line; with the fake
  still down it comes back after 4, 8, 16, then every 30 minutes - qa-58-step2-code S2-2), with no sync
  nudged. `--busy 30` is the same with the job back after 30 s; `--off` makes the report say `none`.
- **A revoked JWT** (design 58 §9, QA N-1): `--revoke-after 1 --books 111:5,222:5`: book 111 downloads; book 222's
  `asset` line is a 401, then one `jwt #2 (stale 1:0000000000000001)` line and the same asset retried once with
  `FAKE-JWT-2` (`asset 222: 302`); the book completes.
- **Offline**: airplane mode: the downloaded books play, the others are hidden (with books 1 and 3 only: the list
  shows those two), the tile and the player still say "del N av 3".
- **The 10-minute job limit**: `--books 111:300,222:5,333:5`; while book 111's CDN line streams, stop the job as
  WorkManager's limit would: `adb shell cmd jobscheduler timeout me.vibb.music.debug`. The next job mints a new token
  (`asset 111: 302 ... token=FAKE-SIG-<n+1>`) and the CDN shows `206 Range bytes=<N>-` - the partial kept, never a
  200 from 0 - and the book completes.
- **Copy only**: with the fake running, play a downloaded book, seek back and forth and skip to the other downloaded
  book: no new `asset` or `cdn` line appears in the fake's log while it plays. Then stop the fake (Ctrl+C): the books
  still play and seek.
- **The old login goes** (design 58 §5.5, §9): install the previous debug music app (before design 58), seed its login
  the old way (`echo '{"email": "k@example.invalid", "password": "hemmelig"}' > debug-feed/storytel.json`, pushed and
  broadcast: "a login seeded"), let it download a book, then install this one and sync: `adb shell run-as
  me.vibb.music.debug ls no_backup` names no `storytel.bin` or `storytel_refused.json`, and logcat has `StorytelLegacy`'s
  "storytel: the old login deleted" once (not again at the next sync).
- **No secrets** (none of these may match; the second is one recursive grep over the app's private data - Room's
  `music.db` and its `-wal`, Media3's `databases/exoplayer_internal.db` and its WAL, `shared_prefs`, `files/music/`
  with the report, `no_backup/` with the cache and `renderer.json`; it must name no file - no JWT is ever written, and
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
  - **A failed mint on a room**: `--refuse` (force-stop the app first, so it holds no JWT) or `--locked 222`, then
    book 2 on Stua: no SOAP to the speaker, "Kan ikke spilles på Sonos", the phone paused; with `--locked 222` the
    report's `item_errors` holds `no_audio` for 222.
  - **The advance on an expired JWT** (design 58 §9, QA 58-1): `--jwt-ttl 300 --books 111:16,222:16,333:16
    --no-download 111,222,333` (16 MB is about 6 minutes of the fake's WAV; design 58 §9's `--jwt-ttl 120` predates
    QA N-6's guard: a life of 240 s or less is never prefetched, the advance then asks as any mint does). Book 1 on
    Stua from its start: a `jwt #n (renewed)` line about every 200 s while it plays (the prefetch at a third of the
    life left; the fake renews below half of it, as the server does); at book 1's end the advance's `asset 222` line has **no** `jwt` line just before it, and book 2 plays.
  - `renderer.json` holds `storytel:book:222` after the play and after the adopt - never a `token=`; logcat's
    `not-ours?` lines show scheme, host and path only; the no-secrets greps above still find nothing.
- Real Storytel stays a device check (design 26b, "Device checks"): the real login, a 300 MB book over Wi-Fi that
  drops for more than an hour, downloads under Doze, the Sonos items.

### §6i B: the chain - the server's session from the fake (design 58 §2.6, steps 1 and 4)

The server talks to the same fake. `STORYTEL_BASE` in the server's `.env` sends its login and bookshelf there, with
`Reach::Any`; it is taken only as `http://127.0.0.1:<port>`, `http://[::1]:<port>` or `http://localhost:<port>` (the
host compared exactly - `http://127.0.0.1.evil.example:8730` is ignored with a warning). While it is set the server
sends a login only for an e-mail at `example.invalid` (the save's probe too), so a real password never reaches a
loopback port, even from a line left in a Pi's `.env`; and a phone's forced login needs a JWT only 10 s old.

```
python3 -I scripts/fake-storytel.py                            # port 8730 on the VM (the server's loopback)
echo 'STORYTEL_BASE=http://127.0.0.1:8730' >> .env             # the server's .env; restart it: the start logs the warning
```

Set-up: Music › Settings saves `k@example.invalid` / `hemmelig` (the probe hits the fake's `login` and `bookshelf`),
the series is added from the shelf, and the phone's Storytel switch is on. Then (step 1; the phone's own use of the
session comes with step 2):

- **The line**: the server logs `storytel: logged in (life_s=1800, left_at_login_s=1800, claims=no,
  payload_has_email=unknown)` - `FAKE-JWT-1` has no dots, so the fallback life and nothing known of an e-mail (design
  58 §9 says `no`; QA N-5's revision made an unreadable payload `unknown`). Music › Settings' Storytel ⓘ reads
  "Session: lives 30 min; holds an e-mail address: unknown."; after a server restart "Session: not known yet".
- **The route**: with the phone's device token (the debug launcher's preferences), `curl -s -X POST -H "Authorization:
  Bearer $TOKEN" http://<server>/api/devices/music/storytel/token` answers `{"status":"ok","jwt":"FAKE-JWT-1",
  "expires_in_s":1739,"session":"<generation>:<16 hex>"}` (about 29 minutes) with no new `login` line in the fake's log (the probe's JWT is
  kept); a second phone's token gets the same JWT, still no login. The phone's Storytel switch off: `404 off`.
- **A force**: `-d '{"stale":"<that session>"}'` within 10 s of the login answers `429 busy` with the seconds left,
  never the JWT; after 10 s one `login` line, a new session (the same `FAKE-JWT-1` from this fake). A fourth force
  from the same phone within the hour answers `busy` with the wait to its first force's hour.
- **A revoked JWT**: `--revoke-after 1` and a book downloading with the server's JWT (the phone on the chain, below):
  after its first asset call the fake revokes the JWT; the server's next Check now on the series gets the bookshelf's
  401, logs in once (`jwt #2`) and lists the series.
- **An outage** (`--login-5xx`, the fake restarted with it): a force after 10 s answers `502 unreachable`, the PWA's
  status line says "Storytel answered 503", a plain ask still gets the kept JWT while it has over 2 minutes, and the
  next force within a minute answers `busy` with no `login` line (the backoff: 1 minute, doubling to 30). Saving the
  login again answers "Storytel answered 503 - nothing was saved".
- **The guard**: save `parent@example.org` with the override set: the fake logs nothing, the server logs "STORYTEL_BASE
  is set: the stored login isn't a fake one, not sent", and the card says "Saved; STORYTEL_BASE is set, so Storytel
  wasn't asked." and "Storytel wasn't asked: STORYTEL_BASE is set and this login isn't a fake one."; every phone's ask
  answers `404 off` (never counted in the phone-login cap). Take the line out of `.env` (and restart) when done.
- **The phone on the chain** (design 58 step 2; the debug launcher and the managed music app as §6l A1, the feed's
  `config.json` with `storytel_base` for the API and the CDN): the fake logs logins only from the server - one at the
  save, none per book; a second phone (or `pm clear` and a re-enrol) gets the same JWT with no new login.
  `--revoke-after 1`: the phone's 401 on the second book is a force through the launcher; after `FORCE_MIN_AGE`'s 10 s
  the server logs in once and the run goes on. **A declined force** (QA N-1): `--revoke-after 1` within 10 s of the
  save's login - the phone's force gets `busy` with the few seconds left, never the old JWT; the download job comes
  back after the wait by itself (no sync nudged), the force logs in, and the book completes.

## 6j. The music card's listening window; Play in the drawer during a window (design 28)

Design 28 (`docs/design/28-music-card-session.md` §8): Home's "Spilles nå" card and the lock's music tile show while
the music plays, and otherwise only for 10 minutes after the end of play or a fresh headset connection; during the
override or the pause the Play Store is in the drawer. Set up: a managed phone with the debug music app and its feed
(§6f), listener access granted (§4), and the screen kept on for the waits. The emulator has no headset, so the debug
launcher has a receiver that connects a fake Bluetooth A2DP headset to the headset watch - through the same rules as a
real one (each broadcast its own device; the 2-minute grace after the boot and after the launcher's process start; a
device back within 60 s is not fresh). It lives only in `src/debug` (`checkReleaseHasNoDebugHook`,
`DebugHookAbsentTest`) and needs `android.permission.DUMP` (shell has it).

```
P=me.vibb.launcher.debug
H=$P/com.kidslauncher.mdm.music.HeadsetDebugReceiver
adb shell svc power stayon true
adb shell cat /proc/uptime                  # the boot's age in seconds (the first number)
adb shell am broadcast -n $H                # "the broadcast" below; its result data and logcat say what it did
adb logcat -s HeadsetDebug MusicListening   # "added AudioOut(id=-1000, ...) at uptime N s -> headsetAt=BootStamp(...)"
adb shell run-as $P cat shared_prefs/music_bridge.xml   # listen_played_ms/_boot, listen_headset_ms/_boot
```

The broadcast's `headsetAt=null` (or an older stamp) means it wasn't fresh: in a grace, or the stamps not readable yet.

**The card and the tile**
- [ ] **Playing**: play an episode - the Home card ticks; screen off and on - the glance tile shows, playing.
- [ ] **Paused, then 10 minutes**: pause in the player and go Home; note the time. The card shows paused. Don't touch
  the phone: at +10 min it goes by itself. Lock the screen and wake it: no tile.
  Then on the lock: play again, pause, and at about +8 min lock and wake the screen (power key twice): the tile shows
  paused. Leave the lock on screen (stay-on is set): at +10 min the tile goes by itself and the page refits (the rows
  or the big clock move into its place, nothing scrolls, no gap).
- [ ] **A launcher restart inside the window**: play, pause, and at about +5 min `adb shell am crash $P`. The PIN lock
  comes up (a new process locks). With the music app's session still live, its tile shows paused. Unlock: Home has
  the card. Both go at the original +10 min.
- [ ] **Reboots**: reboot after a pause, then again with `adb reboot` **while it plays**. No card and no tile after
  either boot, and none after opening and closing the player (a dead battery mid-play opens no window).
- [ ] **A fresh headset**: with uptime > 2 min and the launcher's process older than 2 min, send the broadcast on a
  visible Home. The snapshot card appears at once; its play button opens the player playing (`EXTRA_RESUME`). Pause:
  the card goes 10 min after the pause.
- [ ] **The lock**: in the same launcher process (each broadcast is its own device), outside a window, with the music
  app's session still live and paused: the broadcast brings the glance tile back on the lock (play works from it).
  Force-stop the music app (`adb shell am force-stop me.vibb.music.debug`) and send it again: the lock shows no tile
  (never the snapshot - nothing on the lock opens the player), and Home shows the snapshot.
- [ ] **The graces**: reboot and send the broadcast while uptime < 2 min: no card. Then `adb shell am crash $P` and
  send the broadcast within 2 min **of the crash** (the grace counts from the new process's start, not from Home -
  don't unlock first and run past it), before or after unlocking: the result's `headsetAt` stays as it was, and there
  is no card and no tile.
- [ ] **Sonos** (§6h, the fake speaker): play on the fake room, so the card is playing. Pause from the player: the card
  stays paused and goes about 10 min after the player shows paused.
- Real phone only (Jelly Star; not on the emulator): BT buds connected with nothing played for an hour show the card
  for 10 min; buds in the ears at boot give no card; reboot with the buds in, wait 3 minutes before the PIN, unlock: no
  card; walk out of range for about 10 s and come back: no new card; switch the BT codec in Developer options: no
  card; a call over the buds and its end: no card; a USB-C headset behaves like the buds; an SCO-only car kit gives no
  card.

**Play in the drawer** (design 28 step 1). Do P1-P3 with **every installed app allowlisted**, so no other package's
suspension edge refreshes the lists by accident; note the device page's `play_store_suspendable` (false on the Google
Play emulator: the store is the package verifier and is never suspended).
- [ ] **P1**: managed, kiosk on: no Play Store in the drawer or on Home.
- [ ] **P2**: open the drawer and leave it open. Settings -> "Pause all restrictions" (parent PIN), then back to the
  drawer: the Play Store is listed (no reopen needed). Home's grid shows no Play Store. Play opens from the drawer with
  no "App is not available" and no lock-task toast;
  `adb shell am start -a android.intent.action.VIEW -d market://details?id=org.example` opens Play.
- [ ] **P3**: "Lock again" (Home's banner, Home staying in front): Play leaves the drawer at once. The market link: with
  `play_store_suspendable` false it reaches Play and the kiosk stops the start (with the kiosk off it opens - design
  09 B4's status quo); with true, "The Play Store is closed".
- [ ] **P4**: let a pause run out by itself (or end it with the PWA's `end_windows`): Play leaves the drawer at the
  relock.
- [ ] **P5**: the same with a bedtime rule and the time lock's "Enter unlock code" (the override), and with the PWA's
  pause.
- [ ] **P6**: in no case do Play services or GSF show. Install mode is unchanged (§6b): the store is in the drawer and
  on Home.

## 6k. Debug build only: the keyguard cover (design 32 step 2)

Design 32 (`docs/design/32-lock-flash.md` §8 step 2, §11): Android's own keyguard, as a bare Swipe screen with no
credential and every feature off, is a cover across the sleep. Our lock is `showWhenLocked` and occludes it. While
the keyguard is locked, AOSP shows no activity that isn't `showWhenLocked`, so no other app's content or snapshot can
be on screen before the lock's first frame. The lock dismisses the keyguard after that frame. This is a device
experiment behind a debug-only switch. The receiver and its prefs file `keyguard_cover_debug` live only in
`src/debug` (`checkReleaseHasNoDebugHook`, `DebugHookAbsentTest`), and the release `KeyguardCoverDebug` is always
off. It needs `android.permission.DUMP`, which shell has.

```
P=me.vibb.launcher.debug
K=$P/com.kidslauncher.mdm.lock.KeyguardCoverDebugReceiver
adb shell am broadcast -n $K --ez on true      # switch on (stored, survives restarts) + a full apply
adb shell am broadcast -n $K --ez on false     # switch off: the keyguard as after step 1
adb shell am broadcast -n $K                   # no change, just apply and report
adb shell am broadcast -n $K --ez failDismiss true   # the lock "asks" without the call: each one fails
adb shell am broadcast -n $K --ez skipDismiss true   # the lock never asks: the keyguard stays locked
adb logcat -s KeyguardCover PinLock PinLockActivity HomeActivity ScreenTimeout
adb shell dumpsys device_policy | grep -i -E "maximumTimeToLock|disabledKeyguardFeatures|keyguardDisabled"
adb shell settings get system screen_off_timeout
adb shell dumpsys window | grep -i -E "keyguard|occlud"
adb shell dumpsys lock_settings | head -20     # no credential; the lockscreen not disabled under the cover
```

- **Every broadcast** runs a full apply. Its result data, and logcat `KeyguardCover`, say what is in force:
  `on -> mode=COVER features=0x7fffffff maxTimeToLock=<T> disabled=false ... armed=true dismissTest=NONE`.
  `failDismiss`/`skipDismiss` are memory only; any broadcast without them resets them, and so does a new process.
- **Held off until a lock resume with the screen on.** A new process (a boot, an update, `am crash`) and a dismissal
  fallback write DISABLED until the lock has resumed with the screen on (M4, M7). The log line then says
  `held off until a lock resume with the screen on`. So switch it on with the PIN lock on screen and the screen on,
  or lock and wake once after the broadcast. The fallback's re-arm also waits for the next dark screen-off, so the
  dismissal falls back at most once per wake.
- **A new process takes a leftover cover out** (qa-32-s2-code M2): prefs `keyguard_cover` `cover_written` says the
  device policy may still hold the cover (an update, a crash, a reboot without `ACTION_SHUTDOWN`). The process start
  then writes DISABLED at once (log `mode=DISABLED ... (a cover left by an earlier process)`), before any apply; the
  cover comes back after the first apply at a lock resume with the screen on.
- **"Stay awake" goes off.** DPMS writes `STAY_ON_WHILE_PLUGGED_IN = 0` whenever a max time to lock is set, and
  nothing puts it back when T returns to 0. After switching the cover off, `adb shell svc power stayon true` again
  where a check needs it (§6f, §6j).
- **What it writes** (`lock/KeyguardCover`, the one writer of `setKeyguardDisabled`, the keyguard features and
  `setMaximumTimeToLock`):
  - the features first, read back (ALL), then T = the screen timeout in force, read back, then
    `setKeyguardDisabled(false)`;
  - any failure means today's disabled keyguard with the PIN lock active, and the log says
    `The cover couldn't be set (<step>)` (H2);
  - switched off, exactly the feature bits the cover added come out again (prefs `keyguard_cover`), and T goes
    back to 0.
- **T follows the screen timeout** (H1). In Enforce it is written with the parent's value; in Release (the pause, the
  override) the observer follows the phone's own setting. A growing timeout writes the setting first, a shrinking one
  T first. Log `maxTimeToLock=<T> (<why>)`. Expected side effect: with T set, Settings greys out timeouts longer than
  T. In Release the kid can shorten the timeout but not lengthen it, while the cover is on.
- **Under the cover, a lock-up starts the lock alone, without Home under it** (`Lock-up at screen-off: LOCK (...,
  cover=true, kg=true)`), but only while the keyguard is locked at that screen-off (`kg=`, qa-32-s2-code L4); with
  `kg=false` it is step 1's `HOME_THEN_LOCK`. Only `showWhenLocked` windows can show while the keyguard is locked, so
  Home's black stand-in adds nothing. Its start ahead of the lock's would only widen the window in which SystemUI's
  bare lockscreen shows at a quick wake.
- **Don't combine with §6e.** A 16d override that clears `LOCK_TASK_FEATURE_KEYGUARD` makes `LockTaskController`
  disable the keyguard in lock task (`setKeyguardState`), which silently turns the cover off (L5). (`LockTaskDebug`
  never clears that bit, but `LOCK_TASK_FEATURE_KEYGUARD` must stay on for this experiment.)
- Never on a kid's phone: release builds don't have it.

**The frame-stepping rule** (every check below that records, §11):
```
adb shell screenrecord --bugreport --bit-rate 8000000 /sdcard/flash.mp4   # Ctrl+C to stop
adb pull /sdcard/flash.mp4 && mkdir -p frames && ffmpeg -i flash.mp4 -vsync 0 frames/%05d.png
```
Step through every frame from the first lit one after each power-on to the lock's first frame. **A run fails on any
frame with** app content; a status-bar time older than the live one (a snapshot, or a stale display buffer); Home's
grid; or SystemUI's lockscreen. Black frames, with or without the live status bar, pass. When in doubt, use the
§5 Perfetto layer trace in `docs/design/32-lock-flash.md`. Record with the device's own `screenrecord`, not the
emulator window: one stale full-display frame at power-on, with an old status-bar time, was the emulator's host
composer and not WM (2026-10-13). Check suspect frames with a different `-gpu` mode and on a real phone.

**The checks** (switch on, kid PIN set, managed; §11 step 2):
- [ ] The broadcast logs `mode=COVER features=0x7fffffff maxTimeToLock=<T> disabled=false`. `dumpsys device_policy`
  has `maximumTimeToLock` equal to `settings get system screen_off_timeout`. Settings > Security shows Swipe.
- [ ] Change the parent's "turn off after" (30 s -> 2 min -> 15 s), then start the pause and change the phone's own
  timeout in Settings. After each change, T equals the setting (log `maxTimeToLock=`).
- [ ] Right after a timeout screen-off: `dumpsys window | grep -i -E "keyguard|occlud"` shows the keyguard showing and
  occluded within 1 s.
- [ ] **(a) The power key**, 10 cycles (off, 3 s, on) from an app in front: the rule, no failing frame.
- [ ] **(b) The screen timeout, then power within 1-4 s, 20 times** (H1, the user's case): no failing frame.
- [ ] **(d) Quick presses: off and on within ~100 ms, 20 times**, from an app in front, the cover on:
  `adb shell input keyevent KEYCODE_SLEEP KEYCODE_WAKEUP` (one `input` call; no camera gesture), or a physical double
  press. Expected per AOSP reading, still to verify: SystemUI locks an insecure keyguard at once at the sleep, so even
  a wake that cancels the screen-off fade (`SCREEN_OFF` with `interactive=true`, the lock started as a visible launch)
  finds the keyguard locked. The app is then hidden, and at most SystemUI's bare lockscreen shows until the lock
  occludes it. Count the frames with SystemUI's lockscreen (an accepted residual, §12) and fail on any frame of the
  app. Note `Lock-up at screen-off: LOCK (call=false, interactive=true, cover=true, kg=...)`.
- [ ] (L4) A test app holding a `KeyguardManager.KeyguardLock` (`disableKeyguard()`), a timeout sleep, a quick wake:
  the log says `kg=false` and `HOME_THEN_LOCK`, and the wake shows no frame of the app.
- [ ] (e) Mashing, 5 presses: SOS is still reachable.
- [ ] After the lock's first frame, logcat shows `dismissed` and `dumpsys window` shows the keyguard not showing.
  Then, as today: Recents, a VoIP call screen over the lock (sent only after the dismissal: `the VoIP call screen goes
  now (dismissed)`), the time-rule screen, the emergency dialer, the alarm, SOS, and the camera double press (nothing
  opens).
- [ ] A VoIP call in progress, then power off and on: the call screen, no SystemUI lockscreen (log `mode=DISABLED
  (VoIP IN_CALL)`). The call ends while dark: the cover comes back at the next lock resume with the screen on.
- [ ] A system-dialer call that ends while dark, then wait 3 s and power on: black, then the lock (log `Lock-up while
  dark: LOCK (the re-front check, the keyguard cover)`).
- [ ] Our call: power off and on during it gives black, the lock, then the call screen - at once, not waiting for the
  dismissal (it is `showWhenLocked`, qa-32-s2-code L5).
- [ ] 20 wakes with the kiosk on: no "probably Emergency SOS" line, and no lock pause or stop after a dismissal (L9).
- [ ] After the dismissal: no `LockActivity` start and no lock re-front from the late `USER_PRESENT` (L4). Note
  whether the lock click and the unlock sound play.
- [ ] `--ez failDismiss true`: `dismissal failed (state), n=1`, then `n=2` and `fallback` (`mode=DISABLED`). The next
  lock resume with the screen on after a dark screen-off logs `re-armed` and `mode=COVER`, and the sleep after it is
  covered.
- [ ] A cover that can't be set (for example the `disable-keyguard-features` policy not granted yet): the lock stays
  active, `mode=DISABLED`, and `The cover couldn't be set (features)` appears.
- [ ] Switch off: Settings shows None, the keyguard features are back to what they were (a parent's bits kept), and
  `maximumTimeToLock` is 0.
- [ ] Boot via the power menu or `adb shell svc power reboot` (`ACTION_SHUTDOWN`, log `disabled now (shutdown)`): the
  boot animation, then the 16b cover or Home's mark, then the lock, with no SystemUI lockscreen frame. `re-armed`
  follows at the first lock resume with the screen on. `smoke-test.sh REBOOT=1` is green.
- [ ] Boot via `adb reboot` (no shutdown broadcast): SystemUI's Swipe screen shows until the new process's start
  writes DISABLED (`mode=DISABLED ... (a cover left by an earlier process)`), which dismisses it. This is the accepted
  residual (Home can't dismiss it: it sleeps behind the keyguard, qa-32-s2-code L1).
- [ ] An update (`adb install -r`) or `adb shell am crash $P` with the cover on: the new process logs the same
  DISABLED write before its first apply; the cover is `re-armed` at the first lock resume with the screen on after
  that apply, never `UNTOUCHED` (M2).
- [ ] A crash-guard trip (a debug crash loop of the lock): `disabled now (crash guard)` at once, in the new process
  where the guard trips (L8, M2).
- [ ] The Jelly Star: note the Android version and its SystemUI's lock-after-timeout and notification behaviour. On
  Android 13 (legacy transitions), repeat (b) and (d) carefully.

**The lock started with the screen on** (the double press, all builds; `smoke-test.sh`'s "Double press" step
automates the first point):
- [ ] `adb shell input keyevent KEYCODE_SLEEP KEYCODE_WAKEUP` from an app, until the log says `interactive=true` (the
  fade really cancelled): the lock comes up. **Known residual without the cover** (design 32 §12, qa-32-s2-code M1):
  the lock's black splash (SystemUI logs `VRI[<last part of the package>]`, `debug` for the debug build) and Shell's
  default close of the app the kid was already in (the card). Frame-step a screenrecord: the app, then that close and
  black, then the lock - and no frame of any *other* app. With the cover on, see (d) above.
- [ ] The next screen-off after a wake whose ScreenOn backstop re-started the lock on top (`START ... result code=3`,
  `wm_new_intent PinLock`) logs `NONE`, not `HOME_THEN_LOCK`, and there is no `Set visible without transition
  PinLock`.

## 6l. Vibb music with Spotify (design 26c): the fake Spotify (part A), then the real SDK (part B)

Design 26c §9.4 called this "§6j"; §6j is design 28's and §6k design 32's (both merged from `main` first), so
Spotify's is §6l. **Part A uses no Spotify account**: `scripts/fake-spotify.py` on the VM is the accounts service
(the PWA's login and the server's refreshes), the Web API (the server's listings, the phone's play, transfer, queue, shuffle and repeat calls) and a
**stub Web Playback SDK** that the debug music app's headless web player loads and that plays a real tone per track
through the WebView. Its answers come from the fixtures the tests read (`server/testdata/spotify/`,
`music/app/src/test/resources/spotify/`); every value is fake (`FAKE-ACCESS-<n>`, `FAKE-REFRESH-<n>`, `FAKE-CODE-<n>`,
users `fakeparent`/`fakekid`, client id `0123456789abcdef0123456789abcdef`), and it logs every request (it prints its
own fake tokens - that is the fake's log, not the app's). The server honours `SPOTIFY_ACCOUNTS_BASE` and
`SPOTIFY_API_BASE` only as `http://127.0.0.1:<port>`; the music app reaches the fake through its one debug switch
(`spotify_base` in the debug feed's `config.json`, `src/debug` only, only `10.0.2.2` or loopback): the Web API calls go
to the fake over the LAN client, and the web player loads `https://fake-spotify.invalid/sdk/spotify-player.js`, which
its request interceptor fetches from the fake (the page sees https only). With the switch and **no launcher** (the
app unmanaged) the token comes from the fake's `/fake/token?user=kid`; on a **managed** phone it comes through the
launcher's bridge from the server, as on a real phone. Python 3, stdlib only.

```
python3 -I scripts/fake-spotify.py --selftest                 # fixtures, the fake against itself (no emulator)
python3 -I scripts/fake-spotify.py | tee /tmp/fake-spotify.log    # port 8731, the fixtures' track lengths; Ctrl+C ends it
#   --as kid|parent      who the PWA's login consents as (GET /fake/as?user=kid switches it while running)
#   --rotate --access-ttl 600 --expire-refresh --bad-client      the accounts service's refresh answers
#   --track-seconds 20   every track's length       --slow-ready 15     the SDK's ready after 15 s
#   --elsewhere 60 | --transfer-in 30 | --autoplay | --unplayable ID,... | --relink ID:OTHER
#   --not-premium | --no-eme | --quota | --rate-limit N | --shuffle | --repeat track | --token-ttl S
#   a context play (an artist; a playlist no account may list) plays the context's tracks from its offset:
#   the fixture artist's album, the fixture playlist's tracks, else FAKEtrack 1..5 (the library snapshot's 6 and 7)
adb logcat -s VibbSpotify VibbMusicDebugFeed MusicBridge BridgeSync    # the web player's debug log (fixed words)
```

**Restarting the fake** forgets its devices, codes and tokens: after a restart force-stop the music app (`adb shell am
force-stop me.vibb.music.debug`; the next play makes a new page and device), and in A1 log the two accounts in again
(the server's refresh tokens are the old fake's; restart the server too - its access tokens live in memory only, and
one the new fake doesn't know is refused, not refreshed). So A1 runs with one fake process, and the option checks are
A2's.

### Part A1. The whole chain: the PWA, the server, the launcher, the music app

§2's server with the two base variables, §4's enrolled debug launcher (device owner) and §6f's debug music app (both
debug APKs from one machine's key):

```sh
cd ~/repos/handy/server
SPOTIFY_ACCOUNTS_BASE=http://127.0.0.1:8731 SPOTIFY_API_BASE=http://127.0.0.1:8731 RUST_LOG=debug \
  ADMIN_PASSWORD='change-me' BIND_ADDR=0.0.0.0:3100 INSECURE_COOKIES=true cargo run 2>&1 | tee /tmp/server.log
```

**The music app's language on a managed phone** is Vibb's language from the launcher (the PWA: the phone's page,
Launcher; the managed config's `language`, applied at every start of the app): set it to Norsk there. `adb shell cmd
locale set-app-locales me.vibb.music.debug --locales nb` holds only until the app's next start (a force-stop, a
suspension), as the 2026-10-14 run found.

- [ ] **Not configured**: Musikk → Spotify (`/music/spotify`) shows the registration text (one account per phone,
  the child's own, never the parent's; how a playlist gets its track list) and no add form; the
  library snapshot and the device's policy are unchanged (`music.spotify` false).
- [ ] **The app**: client id `0123456789abcdef0123456789abcdef`, Redirect URI `http://127.0.0.1:43117/callback` (the
  page came over http, so it offers the loopback form and the paste flow); saved, the page shows `…cdef`.
- [ ] **The first login (`fakeparent`)**: the fake's owner of the fixture playlist. In a family's setup the parent's own
  account is never logged in (design 26c step 8: one account per phone, the child's own) - read `fakeparent` as a second
  child's account that owns the playlist; the server can't tell the two apart. "Log in with Spotify" → the fake logs
  `authorize: the consent page (FAKE-CONSENT-1, as parent)` and, as the page submits itself (Spotify's own consent
  page has a button; the fake's presses it), `authorize: consented as parent -> FAKE-CODE-1`; the browser lands on
  `http://127.0.0.1:43117/callback?code=FAKE-CODE-1&state=…` ("can't connect"). (Before the fix of 2026-10-14's F4 the
  fake answered a 302 straight on, which Chromium refused under the PWA's `form-action` CSP.) Copy that whole address into "Paste the
  address your browser shows after logging in" → the fake logs `token: code exchanged for fakeparent`; the accounts card
  lists `fakeparent` with "log in again before <date>".
- [ ] **The kid's login**: `curl -s 'http://127.0.0.1:8731/fake/as?user=kid'` (the fake logs `the next login consents
  as kid`), then the same login and paste (`authorize: consented as kid -> FAKE-CODE-2`, `token: code exchanged for
  fakekid: …`): `fakekid` is listed too.
- [ ] **Plays as**: the phone's Music page (Devices › the phone › Music), the Spotify group's "Plays as": `fakekid`. The
  phone's Web Filter page shows the DNS note; the next sync's policy has `music.spotify` true.
- [ ] **Links**, each with the phone ticked on the add form, then "Check Now" on its entry page (Music › the entry):
  `https://open.spotify.com/playlist/0FakePlaylistKveld0001?si=x` (list mode: the fake logs `GET
  /v1/playlists/0FakePlaylistKveld0001/items: 200 (fakeparent)` - the path without its query; its library row says "3 tracks" and the entry page lists why the fixture's episode,
  local file, unplayable track and duplicate left out), `spotify:album:2FakeAlbumX00000000001`,
  `spotify:show:4FakeShow0000000000001` (newest first) and `spotify:artist:3FakeArtist00000000001` (context mode:
  open the entry - Spotify picks the order).
- [ ] **The phone**: the debug switch, then a launcher sync (§6f; the music app follows on `BRIDGE_CHANGED`):

```
mkdir -p debug-feed && echo '{"spotify_base": "http://10.0.2.2:8731"}' > debug-feed/config.json
R=me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
adb shell am broadcast -n $R        # once: the app creates its debug-feed folder
adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
adb shell am broadcast -n $R        # "spotify: http://10.0.2.2:8731"
adb shell run-as me.vibb.music.debug cat files/music/report.json    # "spotify": "none" until the first play
```

- [ ] **The tiles**: the playlist and the album "Musikk · fortsetter der du slapp" with "Musikk" on the cover, the
  show "Podkast · fortsetter der du slapp" with "Podkast" (its hints and rows say "episode"), and the artist, "Musikk ·
  rekkefølgen velges for deg" (context mode, design 26c step 6). **No "Spotify" anywhere on the kid's screens** (design
  26c step 8): not on a cover, a hint, the player, the list or a line. The list ("Liste") shows the tracks with their
  durations, no line in place of "N av M lastet ned", no download marks.
- [ ] **A cover tap** (the playlist): the play button shows pause at once; the fake's log in this order - at most one
  `token: refreshed for fakekid: FAKE-ACCESS-<n>` (the server's refresh through the launcher's courier; none while the
  server still holds the login's token), `sdk: the stub's script`, `connect: fakedevice… "Vibb" for fakekid`, `shuffle:
  false on …`, `repeat: off on …`, then **one** `play: 3 uris from spotify:track:1FakeTrackA00000000001 at 0 ms on …`
  and an `audio 1FakeTrackA00000000001: …` line (200, or 206 with a `Range`). The tone sounds; the player shows "Stille
  natt — Ola Fjell, Kari Li", "Spor 1 av 3" and nothing under it (no source line); logcat `spotify: the web player is
  ready` and `spotify: wake and Wi-Fi locks held` (a `spotify: permission asked by …` line only if the page asks for
  protected media - the stub plays no DRM; part B's line is the one that counts). `report.json` after the next sync:
  `"spotify": "ok"`. **The tone keeps sounding** (2026-10-14's F2): the audio focus is the WebView's own - `adb shell
  dumpsys audio | grep -A4 'Audio Focus stack'` shows `org.chromium.content.browser.AudioFocusDelegate` of the music
  app's uid on top and no request of `AndroidSpotifyPorts`; logcat has no `spotify: audio focus` line (design 26c
  §5.6 answered).
- [ ] **Pause, resume, seek**: no new `play:` line in the fake's log (the SDK's own calls). A seek past what the
  player has buffered shows `audio …: 206 Range bytes=<n>-` (the debug proxy forwards `Range`) and the tone jumps.
- [ ] **Next, a mashed next**: next once → one `play:` from track 2; next three times quickly → **one** `play:` line,
  from the last target. Previous within the first 3 s goes back (one `play:`); later it restarts the track through
  the SDK (no `play:` line).
- [ ] **A pick from the list**: one `play:`, from the picked track, at its saved place. (The carousel's swipe needs
  per-track pictures: part B.)
- [ ] **The queue's end**: seek near the end of the last track and let it play out: no `play:` line, the player
  closes to the library, and the tile is "fortsetter der du slapp" again (`clear_state`: the partial positions and
  the bookmark gone).
- [ ] **Resume after a restart**: play to the middle of track 2, pause, `adb shell am force-stop me.vibb.music.debug`,
  open the app: the bar is back paused at track 2 and **the fake's log shows nothing** (no page, no request); play:
  one `play:` with `at <the place> ms`, no second from 0:00.
- [ ] **A headset's play after a restart**: force-stop again, then `adb shell input keyevent MEDIA_PLAY`: the stored
  Spotify queue starts on the web player - the fake logs one `play:` line, the tone sounds, and `adb shell dumpsys
  media_session` shows the Spotify track's title in the music app's active session (playing). ExoPlayer gets nothing
  (its `ExoPlayerImpl: Init …` line at the service's start is expected; no `audio` from it). After `am force-stop` the
  package is in the stopped state; if the key press reaches nothing, open and close the app once (or reboot) and press
  it again.
- [ ] **Screen off and Doze**: playing, `adb shell dumpsys battery unplug`, screen off, `adb shell dumpsys deviceidle
  force-idle`; for 30 minutes the tone goes on and track changes keep coming (the fake's `audio` lines); `adb shell
  dumpsys power | grep vibb:spotify` shows the wake lock while playing and none 1 s after a pause. Then `adb shell
  dumpsys deviceidle unforce` and `adb shell dumpsys battery reset`.
- [ ] **The sleep timer**: Sov → 15 min: in its last 10 s the tone fades out, then paused; the volume is back at full
  on the next play.
- [ ] **A bedtime edge**: playing, `adb shell pm suspend me.vibb.music.debug` (or a time rule from the PWA): paused
  within a second, logcat `spotify: web player destroyed`; the headset's play (`adb shell input keyevent MEDIA_PLAY`)
  and the app's play start nothing (no `play:` line); `adb shell pm unsuspend me.vibb.music.debug` - still paused
  until a tap.
- [ ] **A call**: in the emulator console `gsm call 4799999999` while it plays → paused (the launcher's `CALL_HOLD`, and
  the WebView's media session loses the focus to the ringtone); `gsm cancel` → it plays on (only what we paused; or
  Chromium's own resume after the transient loss - either way one tone, no `play:` line). Then (QA M1, L2) **one call
  longer than 5 minutes**: it plays on after `gsm cancel` (a `play:` at the place if the page went meanwhile); note in
  the report which came first in logcat - `CALL_HOLD` (MusicBridge) or the page's paused state (`spotify: …`). **During
  a call, tap play**: no tone, no `play:` line, logcat `spotify: a start refused (a call)`, the player shows paused with
  no line; after `gsm cancel` it plays only if the call paused it.
- [ ] **Another app's audio** (design 26c §5.6 answered, F2): while it plays, an alarm a minute ahead in the emulator's
  Clock app - while it rings the tone pauses and the player shows paused (the WebView's media session's transient
  loss), after Dismiss it plays on by itself and the player follows; then a video in the emulator's Chrome - the tone
  pauses and stays paused (a permanent loss), the player shows paused, no `play:` line either time. Then (QA H1):
  **pause while the alarm rings, then Dismiss** - it stays paused (logcat: a `vibb.pause()` right after the page's own
  resume, the player never shows playing); and **Sov with the timer ending while the alarm rings** - after Dismiss it
  stays paused. A VoIP call's `CALL_HOLD` (Element X) is a device
  check (QA checklist item 10).
- [ ] **Design 28**: while it plays, Home's "Spilles nå" card and the lock's tile show it (title, playing); pause: both
  show paused and go 10 minutes later (§6j's checks with Spotify playing).
- [ ] **The output picker**: "Spill av på" with §6h's fake rooms: pick Stua while Spotify plays → "Kan ikke spilles på
  Sonos", the phone plays on; no `SetAVTransportURI` in `fake-sonos`'s log.
- [ ] **The artist (context mode, design 26c §5.9)**: a cover tap on the artist: the fake logs **one** `play: context
  spotify:artist:3FakeArtist00000000001 from Spotify's start at 0 ms on …` (no offset, no position: an artist has no
  resume) and the album's first track sounds; the player shows its title ("Stille natt — Ola Fjell"), nothing
  under it and **no** "Spor N av M"; "Liste" shows "Musikk · rekkefølgen velges for deg" and one row, the track
  playing. Next: no `play:` line - the stub's next track plays and the title follows (`vibb.next()`, the SDK's own
  step); previous within 3 s goes back a track, later it restarts the track (Spotify's rule, no `play:` line). Pause
  and resume: no `play:` line. Next while paused: the title moves and it stays paused (a step only moves; if the stub
  plays the track the phone pauses it at once). Home's card and the lock's tile show the playing track's title.
- [ ] **A playlist no account may list (the 403 playlist, context mode)**: on the Spotify page Remove `fakeparent`, then
  "Check Now" on the playlist's entry page: the fake logs `GET /v1/playlists/0FakePlaylistKveld0001/items: 403 (fakekid
  doesn't own the playlist)`; the entry page's notes say no logged-in account owns it, the phones play it in Spotify's order, and how
  to get the track list (the playlist in the child's account, or a collaborative one). After the next sync the phone's
  playlist tile is "Musikk · rekkefølgen velges for deg". A cover tap: `play: context
  spotify:playlist:0FakePlaylistKveld0001 from Spotify's start …` (no live track stored yet). Let it play into its
  second track, pause in the middle, `adb shell am force-stop me.vibb.music.debug`, open the app: the bar is back paused
  with **the second track's title** and the fake's log shows nothing; play: one `play: context
  spotify:playlist:0FakePlaylistKveld0001 from spotify:track:<the second track> (track 2 of 3) at <the place> ms` - it
  goes on there. `adb shell run-as me.vibb.music.debug cat no_backup/spotify_context.json` holds `{"<entry key>":
  {"uri": "spotify:track:…", "title": "…"}}` only. Log `fakeparent` in again (the paste) and "Check Now" - **not before 15 minutes after the 403 check**:
  until then the entry page has no "Check Now", only "Check again after <time>" (the check's cooldown): list mode
  again, the tile "Musikk · fortsetter der du slapp".
- [ ] **A refused refresh** (the last A1 check): stop the fake, start it with `--expire-refresh`, restart the server
  (it then has no access token and must refresh), force-stop the music app; a cover tap: the fake logs `token: 400
  invalid_grant …`; "Spør en voksen" on the player's line and on **every** Spotify tile; the report's
  `"spotify": "reauthorize"` - the changed word makes the music app run its own sync (15 s later), which hands the
  report to the launcher, so a launcher "Sync now" about 20 s after the tap delivers it (2026-10-14's F7); the phone's
  Music page (its Spotify group: "Spotify login needs renewing") and the
  Spotify page's accounts say the account must log in again, and the phone's summary shows the Attention row "Spotify
  login needs renewing". Restart the fake without the flag, log both accounts in again, force-stop the music app (its
  token cache holds the `reauthorize` answer for 10 minutes), then play: `"spotify": "ok"` after the next sync, the
  tiles' line and the Attention row gone.
- [ ] **No secrets** (none of these may match): `adb logcat -d | grep -E 'FAKE-ACCESS|FAKE-REFRESH|FAKE-CODE|Bearer '`,
  `adb shell run-as me.vibb.music.debug sh -c 'grep -raE -l "FAKE-ACCESS|FAKE-REFRESH|FAKE-CODE" .'` (Room, the
  report, `no_backup/` with `spotify_state.json` - `{"outcome":…,"at":…}` only - and `spotify_page_account` -
  `<id>:<generation>` only - and the WebView's own folders), and `grep -E 'FAKE-ACCESS|FAKE-REFRESH|FAKE-CODE|code_verifier'
  /tmp/server.log` (the server's lines carry ids and statuses only).

### Part A2. The phone alone: the debug feed, the fake's options

The music app unmanaged (no launcher as device owner: a second AVD, or the snapshot from before §4's provisioning),
the library and the listing from the fake, the token from the fake's own route:

```
mkdir -p debug-feed/items
python3 -I scripts/fake-spotify.py --print-library > debug-feed/library.json        # entry 6 "Kveldsliste", 7 the artist
python3 -I scripts/fake-spotify.py --print-listing 6 > debug-feed/items/6.json      # the fixture playlist, 3 tracks
echo '{"spotify_base": "http://10.0.2.2:8731"}' > debug-feed/config.json
R=me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
adb shell am broadcast -n $R
```

A cover tap on "Kveldsliste" logs `phone token for kid: FAKE-ACCESS-<n>` in place of A1's refresh, the rest as A1.
Each check below restarts the fake with its option (`python3 -I scripts/fake-spotify.py --track-seconds 20 <option>`;
20 s tracks make the ends quick) and force-stops the music app first:

- [ ] **The window's end and the queue add** (60 tracks): `--print-listing 6 --tracks 60 > debug-feed/items/6.json`,
  push and broadcast. A cover tap: `play: 50 uris from spotify:track:FAKEtrack0000000000001`; when track 50 starts the
  fake logs `queue: spotify:track:FAKEtrack0000000000051 on …`, track 51 follows with no gap and no second `play:`, and
  each later track is added one ahead. Pick track 3 then: one `play:` from track 3; when it ends track 4 plays (the
  leftover add of 51 is paused if Spotify plays it - logcat `spotify: a track that isn't ours was paused` - and our
  list goes on), never "Kan ikke spilles" (QA checklist item 20). Put the 3-track listing back afterwards.
- [ ] **A cold start**: `--slow-ready 15`: the play button shows pause throughout the 15 s, a second tap during it
  sends nothing (one `connect:`, one `play:`).
- [ ] **Elsewhere**: `--elsewhere 30`: 30 s after the first play the fake logs `device …: -> elsewhere`; within about
  2 s (the look) the player shows paused, "Spilles et annet sted"; the bookmark is the last measured place
  (force-stop and reopen: there). Play takes it back (a new `play:` at that place).
- [ ] **Unplayable**: `--unplayable 1FakeTrackC00000000003`: track 2 is skipped ("Kan ikke spilles"), track 3 plays;
  unmanaged, only the debug feed's broadcast runs a sync: `adb shell am broadcast -n $R`, then `adb shell run-as
  me.vibb.music.debug cat files/music/report.json` holds
  `{"entry": 6, …, "item": "1FakeTrackC00000000003", "error": "unplayable"}`. With all three unplayable
  (`--unplayable 1FakeTrackA00000000001,1FakeTrackC00000000003,1FakeTrackD00000000004`): at most three `play:` lines,
  then paused.
- [ ] **Autoplay after the list**: `--autoplay`: after the last track the fake's stub plays "Something Spotify picked":
  paused at once (logcat `spotify: a track that isn't ours was paused`), nothing of it saved, the player shows the
  end of our list.
- [ ] **A foreign transfer**: `--transfer-in 20`: 20 s after the first play another app's song starts on "Vibb":
  paused at once, our item and place unchanged.
- [ ] **A detached web player** (qa-26c-step5b-code M1): `--transfer-in 60`, the debug feed with a podcast besides
  "Kveldsliste" (§6f). Play "Kveldsliste", then within the minute tap the podcast: ExoPlayer plays it. At 60 s the fake
  starts another app's song on "Vibb": logcat `spotify: a track that isn't ours was paused`, the podcast plays on (no
  focus loss), the player and the notification show the podcast, and `adb shell dumpsys power | grep vibb:spotify`
  shows no wake lock.
- [ ] **Relinked**: `--relink 1FakeTrackC00000000003:FAKErelink00000000001`: track 2 plays as the relinked copy and
  counts as ours (no pause, "Spor 2 av 3").
- [ ] **Shuffle and repeat on the account**: `--shuffle --repeat track`: the first play turns both off first
  (`shuffle: false`, `repeat: off` before `play:`); the list plays in order, once.
- [ ] **No Premium**: `--not-premium`: "Spør en voksen" on the player and every Spotify tile;
  `"spotify": "not_premium"` in the report; no item error.
- [ ] **No Widevine**: `--no-eme`: "Virker ikke på telefonen", logcat `spotify: web player destroyed`;
  `"spotify": "no_widevine"`.
- [ ] **The quota**: `--quota`: "Ikke tilgjengelig nå" (player and tiles); `"spotify": "quota"`; the fake
  logs one `… 429 QUOTA_EXCEEDED (--quota)` (the start's first call) and no `play:`.
- [ ] **The token refused**: `--expire-refresh` (the fake's token route answers `reauthorize`): "Spør en voksen" on
  the player and the tiles, `"spotify": "reauthorize"`, no `connect:` line.
- [ ] **No Wi-Fi, offline**: `adb shell cmd connectivity airplane-mode enable`, a cover tap: "Trenger internett" under
  the title and on the tile, nothing in the fake's log, the item unchanged (never "Ikke lastet ned",
  never the next track); next and previous say the same and don't move. `adb shell cmd connectivity airplane-mode
  disable`. Wi-Fi off with only mobile data (`adb shell svc wifi disable`; `{"stream_mobile_data": false,
  "download_mobile_data": true, "spotify_base": …}` in `config.json`, pushed and broadcast - design 30's streaming
  switch is the one Spotify obeys, the downloads switch never): "Trenger Wi-Fi"; with `"stream_mobile_data":
  true` (downloads off) it plays over mobile data. `adb shell svc wifi enable` afterwards.
- [ ] **Context mode, the artist (entry 7)**: `--track-seconds 20` (five 20 s tracks: the library snapshot's artist id
  is no fixture's, so its context is FAKEtrack 1..5). The artist's tile is "Musikk · rekkefølgen velges for deg"; a
  cover tap logs `play: context spotify:artist:FAKEartist000000000001 from Spotify's start at 0 ms`; next and previous
  log no `play:` (the SDK's steps; the title follows each track); the fifth track plays out: the player closes to the
  library, the tile has no place left (`clear_state`; `no_backup/spotify_context.json` no longer names the entry).
- [ ] **Context mode, a playlist no account may list (entry 6 as a context)**: flip entry 6's mode in the pushed
  library - `python3 -I -c "import json; p='debug-feed/library.json'; d=json.load(open(p)); [e.update(spotify='context')
  for e in d['entries'] if e['id'] == 6]; json.dump(d, open(p, 'w'))"` - push and broadcast: "Kveldsliste" is one tile,
  "Musikk · rekkefølgen velges for deg". Play into track 3, pause in the middle, force-stop and reopen: paused at
  "Fake track 3 — Fake Artist", no request; play: `play: context spotify:playlist:FAKEplaylist0000000001 from
  spotify:track:FAKEtrack0000000000003 (track 3 of 5) at <the place> ms`. Then `--autoplay`: after track 5 the stub plays
  "Something Spotify picked" in its own context - paused at once (logcat `spotify: a track that isn't ours was paused`),
  the context ended (`clear_state`). `--transfer-in 20`: another app's context on "Vibb" 20 s after the first play is
  paused at once, the live track and its title unchanged. Push the list-mode library back afterwards.
- [ ] **A stored track that left the playlist** (qa-26c-step6-code M3): with entry 6 flipped to a context (above), the
  fake restarted with `--offset-404 --track-seconds 20`, and a stored track the fake's context (`FAKEtrack` 1..5)
  lacks - `adb shell run-as me.vibb.music.debug sh -c 'printf "%s" "{\"<entry 6 key>\":{\"uri\":\"spotify:track:FAKEgone000000000001\",\"title\":\"Gone\"}}" > no_backup/spotify_context.json'`
  (the key from `--print-library`), with the app force-stopped first: open it, play. The fake logs `play: 404
  (--offset-404: …)` twice (around the transfer), then `play: context spotify:playlist:FAKEplaylist0000000001 from
  Spotify's start`; logcat `spotify: the context's stored track was refused - from the top`; the tone, never "Trenger
  internett"; `spotify_context.json` names the first track once it plays, not the gone one.
- [ ] **Our list's leftover queue add, then a context** (qa-26c-step6-code M1): the 60-track listing (above) played
  until the fake logs `queue: spotify:track:FAKEtrack0000000000051 …`, then tap entry 6 flipped to a context
  (`--track-seconds 20`): when its track ends the stub plays Spotify's queue first (track 51): logcat `spotify: our
  leftover queue add skipped`, no `play:` line, the context goes on with its next track - no end, the place kept.
- [ ] **The state recorder** (design 26c §9.4, debug builds only): add `"record_spotify_states": true` to
  `config.json`, push and broadcast - logcat `spotify: state recording to sdk_states/<UTC start>.jsonl`; play a little
  (a context and a list), then `adb pull /sdcard/Android/data/me.vibb.music.debug/files/debug-feed/sdk_states/`: one
  line per posted state, `{"t":<elapsed ms>,"state":{…the page's compact state…}}`, and per page event or call of ours,
  `{"t":…,"event":"ready"}`, `"play from 0 to 2 at 0 ms"`, `"play context"`, `"pause"`, `"next"`, `"queue 51"`,
  `"error playback"` (never the SDK's text). `grep -E 'FAKE-ACCESS|Bearer|token' sdk_states/*.jsonl` finds nothing, and
  logcat holds no state JSON (`adb logcat -d | grep '"position"'` finds nothing). Remove the key (or `false`), push and
  broadcast: `spotify: state recording off`.
- [ ] **No Spotify entry left**: with the app open (the clearing is the player service's; with the service down it
  happens at its next start), push a library without entries 6 and 7 (`--print-library` edited) and broadcast: the
  tiles go, the report's `"spotify"` is `"none"` after the next broadcast, logcat `spotify: web player's cookies, storage and cache cleared`, and
  `adb shell run-as me.vibb.music.debug ls no_backup/` has no `spotify_page_account`.

### Part B. The real SDK (the kid's Premium account, the spike's emulator image)

The spike's image (WebView with Widevine L3), the debug launcher and music app, the server **without** the two base
variables, and no `spotify_base` in the debug feed: push a `config.json` without the key and broadcast (logcat `spotify:
the real hosts` only when the running process had the switch set; a fresh process logs nothing - check `adb shell cat
/sdcard/Android/data/me.vibb.music.debug/files/debug-feed/config.json` has no `spotify_base`). A real playlist the kid's
account owns (or the parent's, made collaborative and joined by the kid), the real login of the kid's account (the PWA
on https, or the paste; the parent's account is never logged in, design 26c step 8), the phone bound to it.

- [ ] Play, pause, next, previous, a pick, the carousel's swipe, resume after `adb shell am force-stop
  me.vibb.music.debug`; the title and the picture (and no "Spotify") in the player, the notification, Home's card and
  the lock's tile. A show's episode in the `uris` list plays (design 26c §3.1, `SHOWS_PLAYABLE`).
- [ ] logcat: `spotify: permission asked by <host>: protected media granted` - **note the host** (`sdk.scdn.co` or
  `appassets.androidplatform.net`; design 26c §5.2's device check); no `spotify: blocked a request to …` line (the
  counter stays 0; a redirect's target never shows there - read the parent's DNS log for hosts outside the list,
  qa-26c-step5a-code L2); no `the web player said csp:` line (the page's CSP admits the real SDK's loader).
- [ ] **No token in logcat** (qa-26c-step5b-code M2: the page's console is handled and never logged): while it plays,
  `adb shell svc wifi disable` for a minute (the SDK's connections fail), `adb shell svc wifi enable`, then
  `adb logcat -d | grep -E 'access_token|BQ[A-Za-z0-9_-]{20}|Bearer '` finds nothing; a debug build's `spotify: console
  <level> from <host>` lines name a level and a host only.
- [ ] **Real state sequences** (design 26c §9.4, step 6's recorder): `{"record_spotify_states": true}` in
  `config.json` (no `spotify_base`), push and broadcast; play the real playlist, a pause, next, a pick; then **an artist**
  and **a playlist the kid's account doesn't own or collaborate on** (the parent's own, say; context mode, design 26c §5.9: Spotify's order; the SDK reference says a
  state's `context` uri "can be null" - note every recorded state with `"context":null` and which track it names: our
  live track and the track the last state named next are read as ours, any other as a uri list, paused), next and
  previous in them (also while paused: whether the SDK's `nextTrack` plays a paused player - the phone pauses it again),
  one context played to its end (with the kid's Autoplay on and off: what the real SDK posts at the end - the recording
  is the page's compact state: the track, `paused`, `position`, `next`/`prev` up to three each, `context`; not
  `disallows`); then `adb pull …/debug-feed/sdk_states/`. The debug log names state kinds
  only (§7.1: never a state's JSON). The recorded lines become `sdk_states/*.json` sequences for `SpotifyTrackerTest`
  (the main session's follow-up): each `{"t", "state"}` a step, each event a `played`/`played_context`/`pause_sent`.
- [ ] **A real artist and a playlist the kid's account doesn't own or collaborate on** (the parent's own, say) (design
  26c §11 step 6's device check): each plays as Spotify picks, the title follows the track, next and previous are
  Spotify's, a restart of a playlist context goes on at the live track (the kid's account must be able to open the
  playlist - public, shared by link or followed; a 403 is "Spør en voksen" with the report's `"refused"`, a 404 after
  the transfer "Spør en voksen" on that tile only, no stored word), an artist starts where Spotify starts it (its bar
  and its tap show 0:00). Remove the playlist's stored track from the playlist (the parent edits it in Spotify), then
  play: if Spotify refuses the offset, logcat `spotify: the context's stored track was refused - from the top` and one
  play from the top - never "Trenger internett".
- [ ] The DNS capture of design 26c §6.2 (the host list), as `docs/testing/spotify-spike.md` describes.

## 6m. A custom DNS server with a backup, networks that block 853 (design 36 steps 2-3)

Design 36 (`docs/design/36-custom-dns.md` §5, §8): with Settings › Web Filter › "Look-ups go to: Custom server", the
phone forwards every query its lists let through to the parent's server, and to the backup while that server is
unhealthy. The emulator reaches public resolvers, so a public DoH/DoT server stands in for the parent's. The Web
filter must be on for the phone (its Web Filter page) and the launcher must be the debug build of this step or later.

1. On the PWA, Settings › Web Filter › Custom Server: **Your server** `https://dns.quad9.net/dns-query`, **Backup
   server** the prefilled `1.1.1.3`; Save. The phone syncs at once (the nudge). Then:
   ```
   adb logcat -s CustomDns DnsUpstream KidVpnService
   adb shell ping -c1 example.com          # resolves through the custom server (with Chrome open, browse too)
   ```
   logcat shows `Custom DNS server: doh` and no `Upstream: backup`. After the next status report (Sync now in the
   phone's Settings, or wait) the footer under the group reads "<phone> · Your server in use". A name on the phone's
   own lists stays blocked (it never reaches the server).
2. The same with **Your server** `dns.adguard-dns.com` (DoT by name: the bootstrap looks it up at the backup, an IP)
   and `9.9.9.9` (DoT by IP: the certificate must name the IP; Quad9's does).
3. A server whose certificate fails: `https://wrong.host.badssl.com/dns-query`. Look-ups still work (through the
   backup); logcat `Upstream: backup, reason=tls, failures=1`, then one probe per back-off (60 s, 120 s, ...); the
   footer says "Backup server since ... - your server's certificate wasn't accepted".
4. A network reset: `adb shell svc wifi disable` then `enable` (or toggle mobile data): the next query tries the
   primary again at once (`Upstream: custom` after a working server, or a new `failures=1` period).
5. A backup given as a DoH URL (`https://1.1.1.3/dns-query` - the PWA's prefill since design 36 step 3's fix round)
   with a failing primary answers too. It must filter as Cloudflare for Families does: with the primary failing,
   Cloudflare's adult-category test domain resolves to `0.0.0.0` / `::`, and the certificate names `1.1.1.3`.
6. Forget Custom Server (or pick Cloudflare): logcat `Custom DNS server: none`, the next report has no
   `dns_upstream`, look-ups go to Cloudflare as before.
7. **A network that blocks 853** (design 36 step 3). Blocking a port needs root somewhere: either the host
   (`sudo iptables -I OUTPUT -p tcp --dport 853 -j REJECT --reject-with tcp-reset` - the emulator's user-mode network
   makes the emulator process the one that connects; `-j DROP` instead for the silent kind) or the guest, which takes a
   rootable image (`google_apis`, not handy36's Play image): `adb root`, then
   `adb shell iptables -I OUTPUT -p tcp --dport 853 -j REJECT --reject-with tcp-reset` (the VPN's protected sockets
   still pass the guest's OUTPUT chain). The console's `redir` only forwards host ports into the guest, so it can't do
   this. With **Cloudflare** (then **Quad9**) picked and the rule in place, `adb logcat -s DnsUpstream`: `DoT attempt
   failed: ...` three times, then `DoT blocked: cloudflare over DoH, periods=1`; `adb shell ping -c1 example.com`
   still resolves (over `https://1.1.1.1/dns-query`, resp. `9.9.9.9` - their certificates name the IPs). With REJECT
   the very first queries resolve too (a refused DoT goes on to DoH in the same query); with DROP each query takes
   ~2.5 s more (the providers' DoT connect) until the period starts, and still resolves. After 5 min one probe (`periods=2` while the rule stays); remove
   the rule (`-D` for `-I`) and the next probe logs `DoT works again: cloudflare` - or toggle Wi-Fi for a network
   reset, which tries DoT at once. Under **Custom server** with `https://dns.quad9.net/dns-query` and a backup by name
   (`https://family.cloudflare-dns.com/dns-query`): the bootstrap's look-ups go over Cloudflare's DoH and the names
   resolve; reboot with the rule in place and logcat `CustomDns` shows `Bootstrap cache: 2 names` before any look-up
   (the disk cache). A parent's own DoT server (`9.9.9.9`) is never moved to DoH: its queries go to the backup.
   Without root anywhere this stays a device check (a router rule dropping 853, or a school Wi-Fi).

8. **Every VPN start resolves, a pause on and off keeps the network** (the 0.29.0 bug: `protect()` on a socket without
   a descriptor, launcher/CLAUDE.md "protect needs a descriptor"). With Cloudflare picked (no custom server), on a
   freshly enrolled phone (the VPN's first start comes with the enrolment's always-on consent) and again after a reboot:
   `adb shell ping -c1 example.com` resolves and `adb logcat -d -s DnsUpstream` has no `attempt failed` line. Then the
   phone's summary in the PWA: "Unlock for 2 h…" (`start_pause`) - the VPN goes (`dumpsys connectivity` has no `VPN
   CONNECTED`), ping still resolves (the network's own DNS) - and "Lock again" (`end_windows`): `Vpn: Established by
   me.vibb.launcher.debug on tun0` on a new network id, and a name not asked before (`ping -c1 wikipedia.org`) resolves at
   once, still no `attempt failed`. On 0.29.0 every query logged `DoT attempt failed: IOException` / `DoH attempt failed:
   IOException` in pairs, then `DoT blocked: cloudflare over DoH`, and ping said `unknown host` from the first start on;
   a Wi-Fi toggle didn't help. A refused protect now logs `NotProtectedException`.

What the emulator can't show (device checks, design 36 "Step 2 implementation status"): a home-network server on the
home Wi-Fi and away from it, AdGuard Home's REFUSED for a client id it doesn't allow; and, without root, step 7.

## 6n. Battery low and the emergency call reach the parent at once (design 35 step 3)

Design 35 §2.5 and §9 step 3: the launcher reports the battery in every status report and syncs on Android's
battery-low/okay edges and right after an emergency call connects. The emulator's battery is set from its console
(`adb emu ...`, or `telnet localhost 5554` + the auth token). The launcher must be the debug build of this step or
later, enrolled; watch:
```
adb logcat -s SyncRunner BatteryStatus KidInCallService
```

1. Run on battery at a normal level first: `adb emu power ac off`, `adb emu power status discharging`,
   `adb emu power capacity 50`. After the next report (Sync now, or wait) the Devices row and the phone's summary say
   "Battery 50 %".
2. **Low**: `adb emu power capacity 10`. Within seconds logcat `Sync for [battery]`, and the PWA's bell counts
   "Battery low on <phone> (10 %)" (Notifications › Inbox; with step 2's push on, the push too); the line says
   "Battery 10 %".
3. **No second one while it stays low**: `adb emu power ac on` (Android clears its low flag and sends no edge, so no
   sync; "charging" shows after the next report), then `adb emu power ac off` (BATTERY_LOW again: `Sync for [battery]`) - **no**
   second event, the latch holds (it re-arms only on battery above Android's level).
4. **Re-armed, then a second**: `adb emu power capacity 30` (BATTERY_OKAY: `Sync for [battery]`, the latch clears),
   then `adb emu power capacity 10`: a second "Battery low" event.
5. **Emergency call**: the smoke test's manual 112 step (`scripts/smoke-test.sh`'s end, or the PIN lock's "Emergency
   call" -> "Call 112?" on the emulator, which fakes 112). When the call is active logcat shows `Sync for
   [emergency_call]` at once (one per call - holding and resuming it, or hanging up, adds none), and the PWA's bell
   counts "<phone> called an emergency number" within seconds, not at the next backstop. The report after the call
   ends carries the same `last_emergency_call_at`, so no second event. An emergency call that never connects gets no
   immediate sync: its call-log row reaches the parent with the next report.
6. Before the first unlock (only on a phone with an Android screen lock, so not the usual setup): an emergency call
   then sends no sync (the anchor isn't direct-boot-aware); the first report after the unlock carries the call.

The emulator can't show (device checks, design 35 "Step 3 implementation status"): the Jelly Star's BATTERY_LOW
threshold and whether it sets `EXTRA_BATTERY_LOW`, the receiver in Doze, the emergency sync over mobile data.

## 6o. Install Now: the parent's one-time launcher install (design 37)

Design 37: Install Now on the launcher's page under Apps (`/apps/tracked/<id>`, the row with "This is the launcher
app") queues a one-time `install_update`; the phone downloads the launcher row's newest release on any network,
checks it as every self-update and installs it at once - waiting only for a call or an emergency. Setup as §6d's test
self-update: the debug launcher of this step, enrolled; a manual app with package `me.vibb.launcher.debug` made the
launcher row ("This is the launcher app" moved to it), and an APK built with a higher `-PversionCode`, uploaded with a
new release label. A manual label never parses as a launcher tag, so the row offers Install Now unless the phone runs a
build of exactly that name (the tap then answers "Already on X"). Keep the clock outside 02:00-05:00 and act inside the
debug build's 2 minutes (after that the ordinary overdue path may install it on its own at a screen-off). Watch:
```
adb logcat -s SyncRunner SelfUpdate MdmSyncWorker AppDownloads UpdateFence AppInstallReceiver CommandAnswers InstallNow
```

1. **Too old**: with the previous launcher (before this step) installed, the phone's row says "1.x is waiting.
   Install Now works from this update on." and has no button.
2. **Screen on, on mobile data**: the phone's Apps › "Download apps on Wi-Fi only" on, `adb shell svc wifi disable`
   (mobile data on). Upload the APK (an upload needs no Check Now) and tap **Install Now** with the screen on and
   an app open. The row goes "Install Now: waiting for the phone" (with Cancel) -> "Downloading 40 of 80 MB" (the
   phone's report after its receipt) -> "Installing"; logcat `Install Now <id>` and `Committing launcher ... (Install
   Now)` without a screen-off. After `MY_PACKAGE_REPLACED` Home (and the lock if LOCKED) are back; the row says "Installed - waiting for the phone's report"
   (never Install Now over the old version) until the report, then reads the new version with "Install Now · <time> · Done" behind its ⓘ ("Installed 1.x"), the Locate tab's Recent "Install
   Now · Done". The page never jumps (the poll swaps the sub-line in place and reloads at the same place when the
   state changes; with the page hidden it stops - switch tabs and back).
3. **A call holds it**: a newer APK; `adb emu gsm call 5551234`, answer, tap Install Now: "Waits for the call to end"
   (status `update_fence.waiting_for` = `install_now_call`). `adb emu gsm cancel 5551234`: the install starts within
   seconds (logcat `Sync for [install_now_call_ended]`). With the screen off during the call, a screen-off does not
   push the re-check out to the night (the alarm says `Update window check in 120 s`, not hours).
4. **Newest found**: tap Install Now, then before the phone acts (airplane mode on, then off after the next step)
   upload a higher build: the higher one is installed and named in the answer.
5. **Refused / failed**: an APK signed with another debug key and a higher versionCode -> "Failed · Refused: signed
   with another key" (the pre-check) or "Android refused the install"; the button is back and nothing is retried.
   **The download keeps failing**: stop the server's download mid-way (`kill -STOP` the server, as §5b) for > 4 min ->
   "The download kept failing" (the run's 3 retries, 30/60/120 s). Airplane mode during the download only waits
   ("Waiting for a network") - a network loss is no failure.
6. **Reboot mid-download** (`adb reboot` while "Downloading"): after the unlock the download resumes (the record is in
   device-protected prefs `install_now`) and installs.
7. **Offline phone**: airplane mode, tap: "Install Now: waiting for the phone" with **Cancel**; Cancel -> the row's
   flash "Install Now cancelled.", Recent "Cancelled". Tap again, airplane mode off: it installs.

Not reachable from the UI (server tests only): "already running" (the server shows no button for a phone that reports
the release, so it needs a report that lags - `install_now::tests`); the 25 h "No answer"; a second tap replacing a
waiting one. Device checks (design 37 "Implementation status"): the call's end over VoIP/Telecom-only calls, the
download on real mobile data, a daytime install with the update fence on and off on the Jelly Star.
## 6p. The phone turned off and on reaches the parent (design 38)

Design 38 §6: the launcher posts the shutdown from `ACTION_SHUTDOWN` and the start from the next boot's first
process start (`POST /api/devices/power`); the server holds a shutdown's push 5 minutes for a restart's start.
**`adb reboot` and `adb shell reboot -p` go through init's `sys.powerctl`, not ShutdownThread: no `ACTION_SHUTDOWN`**
- use `adb shell svc power shutdown` / `svc power reboot`, or the power menu. Setup as §6n (the debug build of this
step or later, enrolled, with a push subscription on Notifications - design 35 step 2), **with tsnet on** - the
emulator loop's normal setup (QA N1: the shutdown's client is the one prepared with the tsnet proxy). Watch:
```
adb logcat -s PowerEvents BootCover TsnetClient
```

1. **Turned off, then on**: `adb emu power ac off`, `adb emu power status discharging`, `adb emu power capacity 2`,
   `adb shell svc power shutdown`. The server log shows the `POST /api/devices/power` 204 (logcat shows nothing from
   `PowerEvents` when it got through); the inbox has "<phone> was turned off" at once ("Sending" under it), the push
   comes 5 minutes later: "<time> · battery 2 %, probably flat". The phone's summary shows "Off since <time> · battery
   was 2 %" under the battery line. Start the AVD again (`emulator -avd ...`): within seconds of its first sync (or the
   stream's first open) "<phone> was turned on · <time> · off since <time>, battery was 2 %"; the "Off since" line is
   gone.
2. **Restart**: `adb emu power capacity 60`, `adb shell svc power reboot`. No push at the shutdown; after the start
   one push "<phone> restarted · <time> · battery 60 %"; in the inbox the off row ("<phone> was turned off", or "is
   restarting" if the property was readable) shows **no** delivery line. Time the start's arrival after the shutdown
   (the server log's two POSTs): it must be well under 5 minutes.
3. **The shutdown's POST didn't get through**: stop the server, `adb shell svc power shutdown`, start the server, wait
   more than 5 minutes, start the AVD: one push "<phone> was turned on · … · off since …"; the inbox's off row ends
   "· told at the next start", unpushed.
4. **No shutdown seen**: `adb emu kill` (or `adb reboot`): nothing at once; at the next start "<phone> was turned on ·
   <time> · no shutdown seen · last report <time>, battery <n> %" (", probably flat" at 10 % or less, not charging).

The emulator can't show (device checks, design 38 §6): the Jelly Star's power menu (Power off and Restart), a real
flat battery (whether the broadcast comes, at which level), whether `sys.shutdown.requested` is readable there, and
Restart timed to the start's arrival on Wi-Fi and on mobile data against the 5-minute hold.
## 6q. Pictures in the episode list, the kid's order flip, the art pass for every item (design 39)

Design 39 §6 (`docs/design/39-music-list-pictures-and-order.md`). The whole chain (§6l Part A1's setup: the server,
the enrolled launcher, the music app, both debug builds of this branch). **The fake**: a feed with a picture per item,
on the VM next to the server:
```
python3 -I scripts/fake-audio.py --feed 250 --hls 4 --port 8732    # or --feed 20; 8732: fake-spotify.py has 8731
```
In the PWA add an RSS entry `http://127.0.0.1:8732/feed.xml` (the loopback feed is judged `lan`; its picture URLs name
the address the server used, its enclosures `10.0.2.2:8732` for the emulator - they follow `--port`; qa-39-code L3: step 4 runs the fake Spotify on 8731 beside it), tick it for the phone. Every third item
names the channel picture (`same` by URL, no request); the others are 9x8 block grids the server judges `own`
(`--selftest` models the print). **No picture budget** since 2026-10-19 (the user: all pictures ASAP): a check fetches
every picture, 200 ms apart, within its own 250 requests; the channel-URL rows cost none. `--feed 20` fills in one
check (13 GETs), `--feed 250` in one too (~167 GETs, well under a minute); the server log's `art N (o own, s same)`
counts each, `, M left` when an entry is due again at once. At 320x569 dp, nb and en:

1. `--feed 20`, after one check: the list (Liste) shows a 40 dp picture per row; the channel-picture rows show the
   plain series cover - no number, no ✓, no scrim (r12 §1); the playing row on `selected` with a 2 dp peach ring. Download two
   episodes on Wi-Fi, play one, open the list and scroll: logcat (`adb logcat | grep -i wantArt`, the launcher's
   bridge log) shows a `wantArt` with an empty `now` and the list's hashes in `later`; after a fling at most 60 extra
   pictures in `files/music/covers/` beyond the want set (`adb shell run-as me.vibb.music.debug ls files/music/covers |
   wc -l` before and after; `files/music/list_art.txt` holds at most 61 lines).
2. `--feed 250`, the entry's offline choice "Ingen" (none): after the first check every row's picture is there (on
   Wi-Fi; flipped to oldest first too) - every third row (the channel picture, `same`) stays the plain series cover. The
   entry page's "N with episode art" is about 167.
3. A one-picture entry (§6f's plain `fake-audio.py` listing, or an NRK podcast): numbers as before, the button there.
4. Flip the RSS entry while episode 5 plays: playback goes on, the line says "… · nyeste først" (en "newest first"),
   the playing row stays in view; next goes up the shown list; the player's "N av M" and the carousel cover's hint and
   progress count from the new top. Leave, reopen, restart the app: still flipped. Tap a row: it continues in the shown
   direction. The same on a fake Sonos room (§6h; with `--hls 4`, item 4 is out of the room's queue before and after the
   flip) and a fake Spotify playlist (§6l: "i rekkefølge"/"baklengs"; no restart from 0:00; flip while paused: nothing
   plays, Play continues the current track and then the shown direction; flip twice fast: one play call in the fake's
   log). TalkBack reads "Snu rekkefølgen, nyeste først" and the selected state. Swipe the app away, open it, flip, then
   play from the now-playing bar (or Home's card): next goes the shown way (the stored queue was flipped, M5).
5. Change the entry's order in the PWA to the direction the kid had flipped to: after the sync the list doesn't move and
   the button goes unfilled (the row is stale, pruned at that sync). Where an `auto` NRK serie with just under 100
   episodes is at hand, flip it to newest first and let it pass 100: the same (otherwise covered by `OrderFlipTest`).
6. "Download on mobile data" off, mobile data on, Wi-Fi off: open the list and scroll - series covers, no picture
   request in the launcher's log; Wi-Fi back: the pictures swap in without a scroll jump.
7. The keep set: the downloaded episodes are the same before and after a flip (Device › Music in the PWA, or
   `files/music` on the phone).
8. Font scale 1.3 in English (§3b): a "40 of 40 downloaded" line drops its order words (the button keeps them).
9. **All pictures ASAP on Wi-Fi** (2026-10-19, design 39's addendum). Run the fake with `--feed 400` (more than one
   check's 250 requests), tick the new entry on Wi-Fi. The server log shows the first check with `, N left`, the next
   check of that entry seconds later (not at its interval), and no `left` once done - within a few checks, a few
   minutes. On the phone, without opening the list: `adb shell run-as me.vibb.music.debug ls files/music/covers | wc -l`
   grows within a few syncs to every own picture of the entry (~267 for 400 items) and stays there (not pruned).
   Mobile data on, Wi-Fi off, "Download on mobile data" off: tick another `--feed 200` entry - nothing beyond today's
   set (the playing item's window and what streaming allows) is fetched; Wi-Fi back: the rest comes.

## 6b. Step 7: Play (optional)

FCM is gone since design 19: every build uses the SSE stream, and About This Phone says "Online
since ..." while the phone holds it. For Play tests:

- Use an AVD with a **Google Play** system image (Play Store + Play services).
- Provisioning order changes when a Google account is wanted: wipe -> `dpm set-device-owner`
  (no account may exist at that moment) -> add the Google account and the Play settings in the
  normal Settings/Play UI -> only then enroll (the first policy blocks account changes and
  starts the kiosk). The full runbook (account settings, Play settings, FRP, an enrolled phone,
  the open Play-services check) is `docs/setup/google-account.md`.
- Quick checks: `adb shell bmgr enabled` -> "Backup Manager is not activated for user 0" (the
  phone's Advanced › Restrictions says "Backup to Google · Off");
  `adb shell dumpsys alarm | grep -i backstop`;
  `adb shell dumpsys package com.android.vending | grep -i suspended` (true while managed);
  `adb shell am start -a android.intent.action.VIEW -d market://details?id=org.example`
  shows "The Play Store is closed"; Settings -> "Install from Play" (PIN) opens Play for 15 min.

## 6r. Release-candidate runs: how much (2026-10-23)

The long combined run (the §8.4-style device list, 1-2 h on this VM) found one real blocker in four RC runs: the
rc.3 run's RC3-1, a step that had only run on Android 15 before. The step runs find the bugs; an RC run mostly
confirms that main hangs together. So:

- **Default for an RC: the short run, about 20 minutes**, only what the user doesn't test on their own emulator
  with the release build: `scripts/smoke-test.sh` (§5b), Emergency SOS (five power presses) over the lock,
  three reboots (one of them unarmed, one with a call ringing), a parent pause and an override, the rollback to
  our own lock. The implementer of a step proves that step once on the emulator; the code QA reads that
  evidence and does not re-run it.
- **The long run only** when an RC gathers steps that never ran together on the emulator before, when a step
  first meets another Android version, or before a stable (non-RC) launcher tag.
- The agent's run isn't the user's: it reads logcat and frame counts on a debug build with Pixel SystemUI; the
  user sees the release build's transitions on their own AVD. Neither replaces the other.

## 6s. The plain series cover, skip 15/15, the one "Favoritter" tile (design r12)

Design r12 §4 (`docs/design/r12-music-favourites-tile.md`). Debug builds of this branch, the debug feed (§6f) or the
bridge chain (§6l Part A1's setup); the fakes as their sections say (`fake-audio.py` §6f/§6q, `fake-spotify.py` §6l,
`fake-storytel.py` §6i, `fake-sonos.py` §6h). Logcat: `VibbSpotify`, `VibbSonos`, the service's lines.

1. **§1**: `scripts/fake-audio.py --feed 20` (§6q): the channel-picture rows and the not-yet-fetched rows show the plain
   series cover - no number, no ✓, no dark scrim; the playing one ringed; a heard row says "Hørt" in its sub-line. The
   plain listing (one picture): numbers and ✓ as before.
2. **§2**: a podcast episode at 1:00: "15" on both buttons; fram -> 1:15, tilbake -> 1:00; TalkBack reads "15 sekunder
   fram"; 10 s before the end, fram stops 1 s short of it (no next item). On the fake room (§6h): fram is a settled
   seek +15 s. A Spotify show episode if the fake lists one (`--print-listing` with an episode entry), else skip.
3. **The tile**: heart, in this order, RSS episode 3 (`--feed 6`), a Spotify track 2 (fake-spotify, list mode,
   `--track-seconds 20`), an own file 1, RSS episode 5. "Favoritter" shows one tile - the peach heart on the card
   ground, "4 favoritter" - and no entry tiles under it. Tap: RSS 5 first, then own 1, Spotify 2, RSS 3 (newest heart
   first). The carousel has 4 pages with their own pictures; "Favoritt 1 av 4"; the header follows each item's entry.
   "Liste": the four rows pictured, each sub-line "<entry> · 12:34", no order button.
4. **The switches**: seek near each item's end and let it advance: RSS -> own file (no renderer change), own file ->
   Spotify (logcat: the web player attached, its page made the first time - note the seconds to the tone), Spotify ->
   RSS (ExoPlayer back; the page stays), then within 5 minutes back to Spotify by previous (warm: under a second), and
   once more after 6 minutes on the RSS item (cold again).
5. **Steps across sources**: next/previous in the player, in the notification, `adb shell input keyevent
   KEYCODE_MEDIA_NEXT` / `MEDIA_PREVIOUS`, and the launcher's lock tile (debug launcher) - each crosses RSS <-> Spotify;
   paused, a carousel swipe to the Spotify page shows it at its place and stays paused, play plays it.
6. **Runs**: heart three tracks of the same Spotify list in a row: they play as one run - logcat shows one play call
   for the three, not three; shuffle on (on a song): the current plays on, the rest permuted, runs mostly one item;
   shuffle off: the hearts' order from the current one.
7. **Can't play**: stream switch off, Wi-Fi off (`adb shell svc wifi disable`, mobile data on): the RSS streams and the
   Spotify track are skipped with "Ikke lastet ned - trenger Wi-Fi", the own file and a downloaded episode play; the
   tile dims with "Trenger Wi-Fi" when nothing of it can play. Wi-Fi on, fake-spotify `--not-premium`: the Spotify item
   shows "Spør en voksen", "Kan ikke spilles nå" once, the queue goes on and later Spotify refs are skipped; a new tap
   tries again. `--unplayable`: the track is skipped inside its run, or - last in its run - the next run starts.
   - 7b (qa-r12-music-code Q-1): the sleep sheet's 30 minutes on, Wi-Fi off and mobile data on, a favourites run whose
     last item is an uncopied stream: the queue moves on past it, and the timer is still shown and still fires.
8. **Storytel** (fake-storytel §6i): heart book 1 (downloaded) and book 2 (`--no-download`): the tile counts both; on the
   phone only book 1 plays (from its copy, no mint in the log); on the fake room both play (a mint per book).
9. **Hearts while it plays**: unheart the playing item - it plays on, the tile says 3; heart a new item - not in this
   queue; play something else, then the tile: the new heart first. "Alle" -> the RSS entry's tile: it continues where
   the RSS entry's own bookmark was, not where the favourites left it; the half-heard favourite episode shows its
   progress in its list.
10. **"Til slutten"** on the last item of a run: it pauses at the end; the player shows the next favourite at its own
    place, paused; play plays it. The minute timer across an RSS -> Spotify switch fades on the web player. On a room
    (qa-r12-music-code Q-9) the usual outcome is the 3 s ramp's pause just before the run's last item ends: the room
    sits paused on that item; play plays its last seconds, then the next run (as inside an entry's queue).
11. **Restart**: pause on the Spotify item, `adb shell am force-stop me.vibb.music.debug`, open: the library opens on
    "Favoritter", the bar shows the Spotify item paused (no WebView in logcat); Home's RESUME plays it; next goes to the
    next favourite. Again paused on an RSS item, with the queue shuffled: the order comes back the same.
12. **The room** (fake-sonos §6h): "Spill av på" the fake room during the favourites queue: the room plays only the RSS
    and Storytel refs (logcat: `sonos: favourites - N of M a speaker can't play left out`), advances across entries by
    itself (fake-sonos's log: Stop + `SetAVTransportURI` per run); back to the phone: on at the room's item, the own file
    and Spotify back in the queue. Only own files and Spotify hearted: the room says "Kan ikke spilles på Sonos", the
    phone plays on.
    - 12b (qa-r12-music-code Q-2): on the fake room, "Til slutten" during a run's last item, let it run off its end
      (Stop, the next run held paused), then play: the next run's first item plays through - no second stop at its end.
13. **An entry removed** (the debug feed without it) while its favourite plays: plays on; its other favourites are
    skipped with "Ikke lastet ned" once (qa-r12-music-code Q-10: the note's "no toast" is superseded); after the sync the
    tile's count drops. Force-stop and open with the stored queue on that gone entry: the bar and RESUME go on at the
    next favourite, not from the queue's top (Q-7).
14. **Back and start**: back from the player during the favourites queue lands on "Favoritter" with its tile; after a
    restart with it stored, the library opens on "Favoritter". A tap on the playing run's own entry under "Alle" starts
    that entry (it doesn't unpause the favourites queue).

## 6t. Safe zones on the phone: fences, the crossing's two fixes, the heartbeat (design 64 step 2)

Design 64 §4 and §9 (`docs/design/64-safe-zones.md`), the debug launcher (it takes mock fixes; a release build ignores
them and counts them in `zone_watch.mock_ignored`), a server of this branch, a zone Home (r = 150 m) on the phone and
Location Regularly. Logcat: `ZoneWatch` (`Fences: N removed, M of K added`, `Fence of zone 7 (entering=..): CROSSING
| ECHO | NOT_OURS`, `Passive feed on`), `MdmSyncWorker` (`Location heartbeat: FIX | KEPT | STILL`), `SyncRunner` (`Sync
for [zone]`, `[zone_confirm]`).

**Two kinds of fix on the emulator** (read from AOSP, not run in the implementer's sitting - confirm in step 1):
`adb emu geo fix <lon> <lat>` (longitude first) feeds the emulator's GNSS HAL, so the fix comes from the real `gps`
provider and `Location.isMock()` is **false** - what our crossing's GPS fixes see. A test provider's fix
(`cmd location providers add-test-provider ...` + `set-test-provider-location`) goes through `MockLocationProvider`,
which marks it mock: `isMock()` **true** - fine for the debug build, refused (and counted) by a release build. The
platform fence asks the **fused** provider (a hidden balanced request at most every 30 min - `GeofenceManager`'s
interval, which a device owner can't change - plus every fused fix any app causes), so the checks drive the fence with
a fused test provider and `geo fix` the GPS path to the same place. Without the test provider a crossing reaches the
fence only at its next 30-min poll: waiting that long is also a valid run of step 3.

```sh
P=me.vibb.launcher.debug
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location providers add-test-provider fused
adb shell cmd location providers set-test-provider-enabled fused true
home() { adb shell cmd location providers set-test-provider-location fused --location 59.9139,10.7522 --accuracy 20; adb emu geo fix 10.7522 59.9139; }
away() { adb shell cmd location providers set-test-provider-location fused --location 59.9229,10.7522 --accuracy 30; adb emu geo fix 10.7522 59.9229; }
```

1. **The mock flag**: `away`, then Update Location on the Locate page; `adb logcat -d | grep -i mock` shows nothing
   refused (debug). In `adb shell dumpsys location` the last `gps` location has no `mock` mark and the `fused` one has.
   Note what you see for both in the report (the finding the brief asked for).
2. **Registered**: after a sync, `dumpsys location | grep -iA3 geofence` - one geofence of `$P`, the fused request at a
   30-min interval with our work source; the Locate page's `#zones` footer says "Watching 1 zone on the phone." and
   "Every" says "30 min while the phone has zones" when the parent's interval is shorter (the select still shows the
   parent's).
3. **Inside, silent**: `home` twice, 2 min apart - the first "entering" right after a registration is dropped
   (`ECHO`) when it comes within 60 s of it; Home's side set, no event in the inbox.
4. **Leave**: `away` - the fence's exit broadcast (`CROSSING`), `Sync for [zone]` with a balanced fused fix (the fused
   test provider answers it), and 2 min later `[zone_confirm]` with a precise one (GPS first: the `geo fix` place):
   "Anna left Home" in the inbox and as a push, timed at the first fix. The Locate page's newest-fix row's title ends in
   "· fused · test location" for the test provider's fix and "· gps" for the geo fix (migration 0093).
5. **Straddle**: `set-test-provider-location fused --location 59.9148,10.7522 --accuracy 200` (100 m from the centre)
   and `geo fix` there - unsure for the server, nothing raised; no report beyond the heartbeat unless the fence fires.
6. **No flap**: `home` (arrive back, two fixes 2 min apart), then one `away` between two `home` fixes: no "left".
7. **A policy change re-registers**: the radius 150 -> 250 in the PWA -> `Fences: 1 removed, 1 of 1 added` (never two
   fences in `dumpsys location`); delete the zone -> `1 removed, none watched`; Only when asked -> none, and the passive
   feed off.
8. **A reboot re-registers**: `adb reboot`, unlock -> the first sync logs `Fences: ... 1 of 1 added`;
   `adb shell dumpsys deviceidle force-idle`, then `away` -> the broadcast still arrives (the fence's broadcast comes with
   system_server's wake lock; we are on the power-save allowlist, design 19b). `dumpsys deviceidle unforce` after.
9. **The heartbeat and the queue**: significant motion **can't be faked** - the emulator has no such sensor
   (`dumpsys sensorservice | grep -i significant` is empty; `zone_watch.motion_sensor` false on the page: "It has no
   motion sensor, so it takes a location every 30 min."), so the emulator runs the no-sensor fallback: a `FIX` at every
   due heartbeat. A passive fix: with Maps open on another place for a few seconds, a "Sync now" before the heartbeat
   is due still reports the old fix (the passive one waits in the kept slot, qa-design-64-step2-code Q-1); the next
   due heartbeat logs `KEPT` and reports it (no LocationManager call, Android's location indicator stays off). The
   queue: `adb shell svc wifi disable` and `svc data disable`, `away`, wait for the crossing's two syncs (both reports
   fail), enable the network: the next report carries the confirm's fix as its `location` and the first fix as
   `extra_locations`, and the server raises the crossing once. Then the same with the parent switching Location to Off
   while the network is down: the first report after it carries no `extra_locations` (Q-5).
   Without zones (delete the zone, Regularly kept): no `Passive feed on`, no `Location heartbeat:` line - every due sync
   takes today's GPS-first fix (Q-2).
   The moving series (design 64 §4.5) needs the sensor too, so it **can't run on the emulator** - `ZonePlanTest` covers
   its rules; the Jelly Star run below checks it (a walk with the screen off: `Moving series: started`, `Series check:
   KEEP|SIDE_CHANGED` - only a fix surely across a boundary is sent -, `Moving series: ended (STILL)` a few minutes
   after stopping; no new `started` from motion within 20 min of an end, at most 60 checks a day -
   `ended (BUDGET)` past them).
10. **Un-managed and re-enrolled**: un-manage the phone in the PWA (or enroll it anew from Settings) -> `Fences: 1
    removed, none watched`, `dumpsys location` lists no geofence of ours, the passive feed is off; after a re-enrollment
    the first report carries no `extra_locations` of the old row (Q-8).
11. **Kid Settings** (swipe left on Home): "This phone shares where it is with mum and dad." / nb "Telefonen deler hvor
    den er med mamma og pappa." under the switches while location is Regularly or Only when asked; gone with Off.
12. **Battery counts** (§5 of the note): `dumpsys batterystats --reset`, an hour still, `dumpsys batterystats $P`
    (wake locks, alarms, GPS time); then a scripted trip (`away`/`home` every 10 min). The emulator gives counts, not mAh.

Device checks left for the Jelly Star (design 64 §9): the background-location app op (`appops get $P FINE_LOCATION`
= allow), the sensor present and armed (`dumpsys sensorservice`), whether the passive listener shows Android's location
indicator (open question 4) and whether the trigger reaches a UID that holds only a foreground service (open question 5),
the moving series on a walk, a walk home with the screen off (the push within minutes), a still phone at home keeping
"Here now" for hours (`zone_watch.still`, Q-3), the battery run, a mock-location app -> `mock_location_ignored` in the
security log once per rise, with the date on the Locate row.

## 7. Reporting back

For anything odd, paste into the chat:

```sh
adb logcat -d -t 2000 | grep -iE "kidslauncher|vibb|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|CommandListener|PlayRuntime|Backstop|AppEnforcer|EmergencyDialer|LockTask|PinLock|UpdateFence|SelfUpdate|NotificationRule|CameraLock" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.
