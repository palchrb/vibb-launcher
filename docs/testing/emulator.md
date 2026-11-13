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
phone, `firstLaunchResetDue`, design 59d §8.17; the order no longer matters.) In its Settings (open until the first policy arrives): server URL `http://10.0.2.2:3100` (plain http to `10.0.2.2` is taken by a debug build
only - a release build takes `https://` or `http://` to a Tailscale IP, security review S-M1), enrollment code
from step 2. Take a snapshot ("enrolled").

**Nudging a sync** (E4/E5, 2026-10-27): the phone sends a status report with every sync, so a report "after the next
sync" can be had at once by saving any setting on the device page - but **never by unticking and re-ticking an app
under Device › Apps**: unticking uninstalls it (`AppInstaller.uninstallSilently`, by design). The safe nudge is the
Apps page's "Download apps on Wi-Fi only" off and on again (`POST /devices/<id>/app-updates`).

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
11. Design 57 (d), calls managed, WhatsApp installed and allowlisted: `adb shell dumpsys package com.whatsapp | grep
    -E "READ_PHONE_STATE|CALL_PHONE"` shows READ_PHONE_STATE `granted=true` with `POLICY_FIXED` and CALL_PHONE
    `granted=false` with `POLICY_FIXED`; take WhatsApp off the allowlist, or switch calls to unmanaged: after the sync
    READ_PHONE_STATE has no `POLICY_FIXED` (the grant itself stays until revoked in Settings). A pause changes
    neither. After a pause's end the re-suspended apps stay in `adb shell dumpsys meminfo` as cached processes: expected
    (no device-owner API ends another app's process on API 34+, `launcher/CLAUDE.md` "Non-obvious gotchas").
12. Home's fold (PLAN "Home's fold at four rows"): `jelly36` (§3), three Home contacts, 11 tiles in 3 columns (four
    rows), font scale 1.0 and 1.3 (§3b), each with gesture and 3-button navigation (`adb shell cmd overlay
    enable-exclusive --category com.android.internal.systemui.navbar.threebutton`, `.gestural` back). With the grid at
    its top the fourth row is either whole with its label readable above the fade, or cut through its icon (at most the
    top half, fading out) - never a whole icon whose label is under the fade or the edge. Scroll down and back up:
    the same. With a clip the grid scrolled to its end stops at the clip (46 dp above the bar at 1.0 with gestures),
    not 16 dp - rule (b), not a gap. No `requestLayout() improperly called` line in logcat (`-s ViewRootImpl`) while
    Home lays out. Install or remove an app while scrolled down, scroll back to the top: the fold follows the new
    row count.
    **Run 2026-10-31 (E6)** (`docs/testing/2026-10-31-e6-run.md`): passed at 1.0 and 1.3, gestures and 3-button
    (14 tiles, five rows): the clip at 1.0 sits 67 px = 44.7 dp above the gesture bar (the card of row 4 ends there,
    fading, the badge visible), 31 px above the 3-button bar; at 1.3 no clip (the bar itself cuts row 4 above its icon's
    middle) and the end stops 16 dp over the bar; the scroll round trip changes nothing. A UI dump can't show the clip
    (accessibility bounds ignore `clipToPadding`): read a `screencap` column by column. The 12 `requestLayout()
    improperly called` lines of the session were all the lock glance's (`lock_glance_top`, `lock_glance_list`,
    `lock_glance_clock`), none Home's. Not run: the row-count change (an untick uninstalls).

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
- [ ] **B6 the kept lines** (the 2026-10-27 diagnostic, branch `nf-keep-log`; `adb logcat -s NotificationRule`): with the
  filter ON, `adb shell cmd notification post -S bigtext -t 'Test' tag1 'hei'` vanishes within 1 s and the log has
  `cancel com.android.shell / shell_cmd (1 since the last report)`; with the filter OFF it stays (no line: `SWITCHED_OFF`
  is never logged). A notification the rule would have cancelled but kept is logged as
  `kept <package> / <channel>: <REASON> (N so far)` - `ESSENTIAL_PACKAGE`, `ESSENTIAL_CATEGORY`, `ESSENTIAL_CHANNEL`,
  `ALLOWED via allowlist|messaging|lock-usable` (the set that allowed it), `GROUP_CHILD_KEPT`, `ACTIVE_UNREADABLE` (a
  summary whose children couldn't be read) or `FACTS_FAILED <Exception>` on the 1st/10th/100th keep of that (package,
  channel, reason) in the process; `ONGOING`/`NOT_CLEARABLE`/`FULL_SCREEN`/`INSISTENT` once per (package, channel);
  never `POLICY_UNKNOWN`, `SWITCHED_OFF`, `UNMANAGED` or `OWN`. `Rule active: N essential packages: a, b, …` names the
  resolved essentials whenever the set changes - is `com.google.android.gms` in it? One second after each cancel the
  listener looks again: a notification still there with the same post time logs `platform kept <package> / <channel>`
  once (Android refused the listener's cancel); a cancel that threw is `couldn't remove <package> / <channel>:
  <Exception>` (warn). Every line of the filter is under the one tag `NotificationRule`.
  **A Google nag that stays (PLAN: Play services' `multidevice...WELCOME_CHANNEL_ID`) is diagnosed from this line**: its
  `kept com.google.android.gms / ... : <REASON>` names why, or `platform kept` says the cancel was refused; paste it.
  **Run 2026-10-31 (E6)** (`docs/testing/2026-10-31-e6-run.md`): the shell notification cancelled within 1 s with the
  line `cancel com.android.shell / shell_cmd (1 since the last report)`, kept with the filter off (`Rule inactive: 9
  essential packages`, no line), cancelled at once when it came back on; the `kept` shapes seen: `android / VPN:
  ONGOING (1 so far)`, `com.google.android.apps.messaging / bugle_default_channel: ESSENTIAL_PACKAGE (1 so far)` and
  `(10 so far)`, `android / PHYSICAL_KEYBOARD: ESSENTIAL_PACKAGE`. `Rule active: 9 essential packages: com.android.phone,
  com.android.server.telecom, com.google.android.apps.messaging, com.google.android.cellbroadcastreceiver,
  com.google.android.deskclock, com.google.android.dialer, com.google.android.inputmethod.latin, com.google.android.tts,
  me.vibb.launcher.debug` - **`com.google.android.gms` is not in it**. GMS posted nothing in an hour on the Play image
  (no Google account on it), so the welcome nag's line is still the phone's to show.

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

## 6k. The lock started with the screen on (design 32 step 1)

Design 32 step 2's keyguard cover (Android's keyguard as a bare Swipe cover across the sleep) was a debug-only device
experiment. Its switch, `KeyguardCoverDebug` and its receiver, was deleted in round 0b (2026-10-26) unrun, so the
cover never arms and its checks are gone; the COVER mode's code goes with design 59 phase D. §5's H2 toggle
(`LockUpDebug`) went with it.
"Stay awake" still goes off in design 59's android mode, which sets `setMaximumTimeToLock` (DPMS then writes
`STAY_ON_WHILE_PLUGGED_IN = 0`): run `adb shell svc power stayon true` again after it where a check needs it (§6f, §6j).

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

**The checks** (`smoke-test.sh`'s "Double press" step automates the first point):
- [ ] `adb shell input keyevent KEYCODE_SLEEP KEYCODE_WAKEUP` from an app, until the log says `interactive=true` (the
  fade really cancelled): the lock comes up. **Known residual** (design 32 §12, qa-32-s2-code M1):
  the lock's black splash (SystemUI logs `VRI[<last part of the package>]`, `debug` for the debug build) and Shell's
  default close of the app the kid was already in (the card). Frame-step a screenrecord: the app, then that close and
  black, then the lock - and no frame of any *other* app.
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
- [ ] **No Premium**: `--not-premium`: the player's cover greyed with "Kan ikke spilles nå" / "Spør en voksen om
  hjelp" on it, play greyed, nothing under the entry name (2026-11-09, `ui/CantPlay.kt`), and "Spør en voksen" on every
  Spotify tile;
  `"spotify": "not_premium"` in the report; no item error.
- [ ] **No Widevine**: `--no-eme`: "Virker ikke på telefonen" on the greyed cover, play greyed, logcat `spotify: web player destroyed`;
  `"spotify": "no_widevine"`.
- [ ] **The quota**: `--quota`: "Ikke tilgjengelig nå" (the player's greyed cover, play lit; the tiles); `"spotify": "quota"`; the fake
  logs one `… 429 QUOTA_EXCEEDED (--quota)` (the start's first call) and no `play:`.
- [ ] **The token refused**: `--expire-refresh` (the fake's token route answers `reauthorize`): "Spør en voksen" on
  the player and the tiles, `"spotify": "reauthorize"`, no `connect:` line.
- [ ] **No Wi-Fi, offline**: `adb shell cmd connectivity airplane-mode enable`, a cover tap: "Trenger internett" under
  the title and on the tile, nothing in the fake's log, the item unchanged (never "Ikke lastet ned",
  never the next track); next and previous say the same and don't move. `adb shell cmd connectivity airplane-mode
  disable`. (Since 2026-11-09 the player says it on the greyed cover, play lit, not under the title.) Wi-Fi off with only mobile data (`adb shell svc wifi disable`; `{"stream_mobile_data": false,
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

## 6m. A custom DNS server with a backup, networks that block 853, the secure-DNS bypass (design 36 steps 2-3, design 67)

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

**The browsers' secure-DNS bypass and "DNS can't be reached"** (design 67, `docs/design/67-dns-bypass.md` §5, E3), on
`jelly36` (Chrome preinstalled; Firefox, Edge, Brave and Vivaldi from Play). Two custom servers that can't be reached:
`tls://192.0.2.10` and `tls://192.0.2.11` (TEST-NET-1, never routed, not the tunnel's own `.1`/`.2`; two because the
server refuses the same server twice, and one alone isn't enough: the backup answers).

9. Install main's APK first and, on the enrolled phone: Chrome › Settings › Privacy and security › Use secure DNS.
   Record whether it shows "managed by your organization" (Chromium's owned-device default). Then this branch's
   build: `chrome://policy` lists `DnsOverHttpsMode` = `off`, source Platform; the switch is managed and off. Record
   the result in launcher/CLAUDE.md's browser table.
10. On main's build, set Chrome's secure DNS to Google (or in a fork that still shows the switch). Then install this
    branch's build over it: a name on the phone's lists doesn't load, and `adb shell ping -c1 dns.google` and
    `ping -c1 chrome.cloudflare-dns.com` say unknown host (on main's build they resolve). With the phone's log on, the
    phone's Blocked Activity has "Secure DNS server".
11. Firefox (Play): `adb shell ping -c1 use-application-dns.net` -> unknown host (NXDOMAIN; nothing in the log).
    Firefox Settings › Privacy › DNS over HTTPS (if present): Default -> filtered; Increased with Cloudflare ->
    filtered (falls back); Max -> pages fail (the host is blocked). Record which modes exist on Android and fix the
    Firefox line's words (`dns_filter::DOH_IGNORING_BROWSERS`).
12. Custom server `https://dns.quad9.net/dns-query`, backup `https://family.cloudflare-dns.com/dns-query` (both names
    on the built-in list): look-ups work, and with a failing primary (`https://wrong.host.badssl.com/dns-query`) the
    backup by name answers (logcat `Upstream: backup`) - our own upstreams never meet the list.
13. Custom `tls://192.0.2.10`, backup `tls://192.0.2.11`: ping three names over 40 s
    (`adb logcat -s UpstreamReach CustomDns`). logcat shows `Upstream unreachable`; after Sync now, if it gets
    through, the DNS Server footer reads "DNS can't be reached since ... - neither your server nor the backup
    answers". Set the backup to `https://1.1.1.3/dns-query` (this bumps `custom_rev`): `Upstream outage ended`, names
    resolve, and the next report gives "Backup server since ... · couldn't be reached HH:MM-HH:MM" - the ended line
    survives the rev change.
14. Offline is no outage: with the servers of step 13, `adb shell svc wifi disable` (and `svc data disable`) for 90 s
    with apps asking, then enable: no `Upstream unreachable` in logcat, no outage line.
15. Provider mode with 853 and `1.1.1.1:443` blocked (step 7's host iptables, `-d 1.1.1.1 --dport 443` and `--dport
    853`; no guest root): "DNS can't be reached since ... - Cloudflare doesn't answer from where the phone is" - the
    only phone line in provider mode.
16. Edge, Brave, Vivaldi and Opera from Play, allowlisted: `edge://policy` / `brave://policy` / `vivaldi://policy` /
    `opera://policy` show `DnsOverHttpsMode`; fill launcher/CLAUDE.md's browser table (an Opera that reads it loses
    its Each Phone line in `DOH_IGNORING_BROWSERS`). Element X still connects with the built-in list in
    force (WhatsApp: a device check).
17. Cromite allowlisted for the phone: the Each Phone footer names it ("Cromite: set its Secure DNS to Off ...");
    with its Secure DNS on, pages don't load; Off -> filtered. The Web filter switched off for the phone: the line
    goes, and `adb shell dumpsys device_policy | grep -A2 com.android.chrome` shows an empty bundle.

What the emulator can't show (device checks, design 36 "Step 2 implementation status"): a home-network server on the
home Wi-Fi and away from it, AdGuard Home's REFUSED for a client id it doesn't allow; and, without root, step 7.
Design 67's: Samsung Internet's managed config, WhatsApp with the built-in list.

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

**The last position at shutdown and low battery** (design 38 "Last position", `docs/review/qa-last-position-code.md`).
The shutdown check needs a non-mock fix: `adb emu geo fix` goes through the GPS HAL; a test provider's fix is mock on
the debug build, and the shutdown sends none - by design.

5. **Shutdown with a stored fix**: `adb emu geo fix 10.7522 59.9139`, then Update Location, so the phone has a reported
   fix; `adb shell svc power shutdown`. Logcat shows the POST's 204; after the 5-minute hold Locate's newest fix reads
   "Last position before the phone went off" (nothing during the hold), with exactly one row in the trail for it; the
   inbox's off row says "Last position from HH:MM: shown on Locate"; the push "<phone> was turned off · …" has no
   place in it. Start the AVD: the line is gone after the start's report.
6. **The kept, unreported fix** (zones on, §6t): with a fence, a passive fix lands in the kept slot unreported; then
   `svc power shutdown`: a new trail row, marked as the shutdown's.
7. **Location "Off"** on the phone's Location Settings, after a sync: `svc power shutdown` sends an `off` with no
   `location` (the server log's body size, no new trail row), and Locate shows no line.
8. **A known restart**: `svc power reboot`: Locate's line never appears ("is restarting" when the property was
   readable), nor during the 1-2 minutes before the start's report while the off's push is held.
9. **Low battery, once per episode**: `adb emu power ac off`, `adb emu power capacity 10` (`am broadcast -a
   android.intent.action.BATTERY_LOW` is refused to the shell - a protected broadcast): logcat `LowBatteryFix: Battery
   low: one fix (balanced)`, one new fix on Locate within about 30 s. Then `adb emu power ac on`, `ac off` at the same
   level: `Battery low: no fix (taken true, …)`, no new fix. Then `adb emu power capacity 30` with `ac on` (OKAY), `ac
   off`, `capacity 10`: one fix again. Right after an Update Location, the edge reports that fix (`a fix under 2 min
   old, reported`) and takes none.
10. **Low battery with Location off** (the quick-settings toggle, "Keep location on" off): the LOW edge gives
    `no fix (… location false)`, and the switch stays off.
11. **Low battery under "Off"**: `no fix (… mode off)`.
12. **Jelly Star** (device): a real LOW edge with Battery Saver auto-on at 15 % - does the balanced fix still get a
    position with the screen off (Battery Saver's location mode may be foreground-only or no GPS with the screen off)?
    A real flat-battery shutdown - does it carry the position?
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
   (`ECHO`) when it comes within 60 s of it. The test provider's fixes reach only the fence, not the server (nothing
   asks the phone for a location until a heartbeat, a crossing or Update Location - the 2026-10-24 run), so tap Update
   Location twice (a minute apart) to put two GPS fixes inside: Home's side set ("Here now"), no event in the inbox.
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

## 6v. Per-app permissions: camera off, the special accesses approved on the phone (design 68)

Design 68 §2.3 and §6 (`docs/design/68-app-permissions.md`), the debug launcher and a server of this branch, Element X,
Immich, Etar and Fossify Calendar installed and allowed. Logcat `AppEnforcer` (`Camera off waits for the call's end`,
`Platform refused android.permission.CAMERA = 2 for ...`).

0. **The app-op probe first - the gate before the launcher RC** (the code QA's L1; §2.3; each op for an app that has the access and one that doesn't, `write_settings` and
   `usage_access` with test apps): `adb shell cmd appops get <package> <OP>` against the report's `installed_apps[].access`
   (the PWA's line, or `device_status.installed_apps_json`) for `MANAGE_MEDIA` (Immich), `SCHEDULE_EXACT_ALARM` (Etar,
   Signal; and Etar again after it is battery-exempt), `WRITE_SETTINGS`, `GET_USAGE_STATS`, `USE_FULL_SCREEN_INTENT`
   (Element X). An op whose reading disagrees ships as `unknown`. No launcher RC with B1 until this passes (passed on
   `jelly36` on 2026-10-27; **the Jelly Star repeat is still owed**). ("Fullskjermvarsler" is Settings' own nb word,
   checked in the code QA.)
   **Glue only the device shows** (the code QA's L5; the pure halves are unit-tested): `AppAccessReader.states`
   feeding `readAccess` (a throwing read must show `unknown`, never `denied`), `BootHome.callMayHaveEnded`'s wake
   (`deferredCameraApplyDue` + `WindowRelock.liveCall`, step 2), and the hidden-target skip in `cameraPass` (no
   "Platform refused ... CAMERA" line per apply for a hidden camera-off app, step 2). **Still owed to a device**: a
   failed read showing `unknown`, and a camera change waiting for a live call's end with its wake.
1. **Camera off** for Element X in the PWA: `dumpsys package io.element.android.x` shows `CAMERA: granted=false` with
   `POLICY_FIXED` (done 2026-10-27). Back on: the flag is gone, the app asks again. Settings' own camera switch can't be
   seen: Android's Settings is hidden outside a pause, and the pause lifts the denial - `dumpsys` is the check.
2. **During an Element X call**: off while it holds the camera waits for the call's end; off for an app that doesn't
   hold it is written at once. A camera-off app hidden and allowed again: denied by the apply that unhides it. A
   preinstalled camera app, or any system app: no `access` at all (no switch, no line) and no App access row on the
   phone (E4's M3). In a pause the PWA says
   "Camera off · back on while restrictions are paused".
3. **Immich**: the PWA says "Media management: not approved"; Settings › App access's row says to pause first; in the
   pause it opens Android's page for `app.alextran.immich`; turned on and back, the row reads "Approved - tap to change"
   and the next report `granted`, **without the parent code asked again** on the way back (the gate holds across a page
   a row opened, at most 10 minutes; E4's L3 - Home or Recents from there asks again). The pause ending with that page
   open: Home comes to the front, the row stays.
4. **Etar**: "Etar: Alarms & reminders" approved in a pause, and **Etar's first start in the same pause** (it asks for
   the battery exemption at its first start, which can't open outside a pause - its own crash, E4's M2; the row's and
   the PWA's words say so); after it Etar works with no pause. Fossify
   Calendar has no row (it declares `USE_EXACT_ALARM`). `me.vibb.music.debug`: no row, no camera switch.

**Run 2026-10-27, E4** (`docs/testing/2026-10-27-e4-e5-run.md`): step 0's probe agrees for all five ops on `jelly36`
(granted and denied; Android's own page writes the uid mode, which the reader follows; a battery-exempt Etar reads
`granted` with Settings' switch off) - the gate passed, the Jelly Star repeat is owed. No app here declares
`WRITE_SETTINGS`/`PACKAGE_USAGE_STATS`: a manifest-only probe APK stood in. Steps 1-4 passed except: system apps get App
access rows on the phone, and Etar's first start outside a pause crashes on its own battery-optimization request (step
4). Owed to a device: the live-call deferral and its wake, a throwing read as `unknown`. Settings' camera words (step 1)
can't be reached: Settings is hidden outside a pause and the pause lifts the denial. Unticking an app under Apps
uninstalls it - don't nudge with it.

## 6u. The music app's launch splash and the connecting line on a room (music round r13)

PLAN "Music app" queue, branch `music-r13`. The debug music app with the debug feed (§6f) and the fake room (§6h).
Logcat: `VibbHandover` (the hand-over's steps, each `+N ms` since the tap), `VibbSonos`.

1. **The splash, cold**: `adb shell am force-stop me.vibb.music.debug`, then open the app from the launcher's tile, as
   the kid does (a shell `am start` may get the plain ground without the icon). Record it (`adb shell screenrecord
   /sdcard/s.mp4`, pull, step it at 24 fps): the rings on the night ground and "vibb music" (cream + peach) at the foot, the system's fade
   only, then the library's first frame **with no plain dark frame between** (the user's video had ~0.9 s of it).
   Again with an empty library (no entries in the feed): the empty state follows the splash at once. The cap: with the
   library slow (a debugger paused at `MusicViewModel`, or simply a feed of 1000 entries) the splash lets go at about
   3 s, never hangs. Once more at font scale 1.3 and in a 3-button navigation AVD (the wordmark not cut).
2. **The connecting line, a podcast on the fake room**: `scripts/fake-audio.py` (§6f) for an RSS entry and
   `scripts/fake-sonos.py --transition-s 4 --duration 120` (§6h; 4 s of TRANSITIONING after each Play makes the line
   visible). "Spill av på" Stua, tap the RSS tile: the player shows "Kobler til høyttaleren…" in the times' place from
   the tap until the room plays, then the times; nothing below the seek bar moves. `adb logcat -s VibbHandover`: the
   steps from `tap: PLAY_ENTRY` through `the speaker resolved`, `RemoveAllTracksFromQueue answered`, `SetAVTransportURI
   answered`, `Play answered`, `the play's answer on main`, the settle polls, `the first PLAYING poll of our stream`,
   `the player shows playing` to `the connecting line ends` - save the lines for design 54's "Hand-over timing" table
   (a cold first play after 5 minutes idle, then a warm second tap). Next/previous on the room: the line shows briefly
   for each step (a `(no tap)` timeline).
3. **The failure path**: stop `fake-sonos.py` (Ctrl+C) with the room chosen and tap a tile: the line shows, then the
   existing outcome after the existing SOAP timeout - "Kan ikke spilles på Sonos" and the phone (a first start). On a
   live session, the script's `drop` (§6h) and next: "Får ikke kontakt med høyttaleren" under the entry name once the
   polls fail, the times back under it. No new wait anywhere.
4. **Storytel and NRK on the room** (§6i, the NRK fixture): the same line and the same steps (Storytel adds `the
   Storytel URL minted`).
5. **Spotify on the room** cannot be checked yet: design 54-a2 (the controller's share-link path) isn't built, so a
   Spotify tile on a room still says "Kan ikke spilles på Sonos" at once (`speakerDropReason`). When 54-a2 lands the
   share-link steps (`SetPlayMode NORMAL answered`, `the ShareLink request sent/answered`, `the start item's page read`,
   `Seek TRACK_NR answered`, `the speaker's sleep timer set or cleared`) are already logged by `SonosTransport.playShare`.

The code QA's checks (`docs/review/qa-music-r13-code.md`, "Emulator checks to add"; the fix round's line numbers):

6. **Splash, timed** (Q-6): count the frames from the tap to the first content frame. With a normal library the splash
   ends well **before** 3 s - a release at about 3 s every time means the content signal never fired (M7). No frame of
   plain night between the splash's fade and the tiles; the system's exit fade only, the icon at rest for the whole
   hold (no fade or shrink before the exit).
7. **The start tile** (Q-10): with a stored now-playing row (play something, force-stop, open), the library's first
   frame already shows the last played tile, with no jump right after the splash; with no row (a fresh install) tile 0.
8. **Recreate and warm start**: change the font scale with the app open (no blank flash, no 3 s freeze). Back, then
   open it from the tile again (the splash, then content). Open it from the media notification (SystemUI: the icon
   splash). The Home card's resume (`EXTRA_RESUME`): the splash, then the player, and the room or phone plays as before.
9. **A double tap on a room** (`fake-sonos.py --transition-s 4`, Q-1): tap a tile, and another before the first plays.
   The `VibbHandover` lines carry two numbers (`#n`, `#n+1`); the first play's `Play answered` and `the play job done`
   sit under `#n` (timed from its own tap), the second's under `#n+1`. The line shows until the **second** play
   reaches PLAYING. A carousel swipe on the room starts its own `tap: SKIP_TO` timeline (Q-9).
10. **TalkBack** on the player while connecting: swiping past the seek bar reads "Kobler til høyttaleren…" in the
    times' place. It isn't announced by itself, as the room's other lines (none is a live region).
11. **Spotify on the phone, cold** (`fake-spotify.py --slow-ready`, §6l; Q-3, the user's decision): with the web
    player's page gone (6 minutes after the last pause, or a fresh process), tap a Spotify tile: the player shows
    "Kobler til…" in the times' place from the tap until the first playing state (the tone), then the times; a step
    shows it briefly. `--not-premium` / Wi-Fi off: the line gives way to "Spør en voksen" / "Trenger internett" under the
    entry name and the times come back - no new wait. Note the seconds from the tap to the tone for the user.

Round M1 (`music-splash-b`; PLAN "Round M1"): the system splash shows the rings alone, the app's first frame adds "vibb
music" 24 dp under them, and a failed stream's real cause is logged. These replace step 1's "at the foot" and step 6's
"the system's exit fade only"; the rest of steps 1 and 6-8 stands.

**Run 2026-10-26** (`docs/testing/2026-10-26-e1-run.md`): steps 1, 6, 12-16 passed on `jelly36`; 17 needs a real kill (a
media foreground service survives `am kill`), 18 showed no wordmark on a warm start (the 400 ms hold of round M1b covers
it), 2-4 and 9-10 not run (the fake room needs SSDP from the emulator).

**Run 2026-10-26, E3** (`docs/testing/2026-10-26-e3-run.md`): §6m checks 10, 12-15 passed on `jelly36`; 9 is set by the
launcher (log) but `chrome://policy` and every browser screen (11's DoH modes, 16, 17) are device checks - Chrome and Fennec
draw nothing under swiftshader; L2's READ_PHONE_STATE needs WhatsApp (device). Offline there needs `svc wifi disable` and
`svc data disable`; the phone reports `dns_upstream` and its blocked events only with a status report (nudge with a real
policy change).

12. **The hand-over, frame by frame** (cold, from the launcher's tile, recorded as in step 1, stepped at 24 fps): the
    rings on the night ground alone, then "vibb music" (cream + peach) fading in right under them while the rings don't
    move - **at most a pixel** (the shell truncates the icon's bounds: at 240 dpi the canvas is drawn 430.2 px instead of
    432, at 440 dpi also 0.9 px up and left), no slide, no scale step, no shift by the status bar (compare the rings' top
    edge in the frames before and after the fade). The rings sharpen as the system splash fades (its icon is a 108 dp
    bitmap scaled 1.78x) - not a scale step. Then the wordmark **held whole and still** (round M1b: at full strength
    at least 400 ms from the system splash's last frame, about 10 frames at 24 fps; the 3 s cap still wins) before the
    library fades in over the splash; no plain night frame anywhere. Nothing is cut: the wordmark's ink starts
    about 107 dp under the screen's centre. Note the frames from the tap to the wordmark and to the library.
13. **Empty library** (no entries in the feed): the same hand-over, then the empty state right after the system splash's
    fade (not at 3 s).
14. **Font scale 1.3** (`adb shell settings put system font_scale 1.3`, cold start): the splash identical to 1.0 (both
    images are paths in dp). With the app open, change the font scale back: the screens come back with **no splash** over
    them (a configuration change keeps the view model, which says the content was shown) and no blank flash.
15. **3-button navigation** (an AVD with `hw.mainKeys = no` and 3-button navigation, or `adb shell cmd overlay
    enable-exclusive --category com.android.internal.systemui.navbar.threebutton`, then check `adb shell settings get
    secure navigation_mode` reads 0): the rings still don't move at the hand-over (the system splash and the app's both
    cover the whole screen, bars included); compare the bottom 48 dp of the frames before and after the hand-over (the
    navigation bar's ground unchanged); the wordmark is well above the navigation bar.
16. **The no-network words and the log** (`fake-audio.py` for an RSS entry): with Wi-Fi on, make the host's name fail -
    the launcher's Web Filter on a custom DNS server with **both** the primary and the backup unreachable (e.g.
    `tls://192.0.2.1` for both, TEST-NET-1, never routed; one alone isn't enough - the backup answers, §6m), or a
    listing whose enclosure host doesn't exist (`fake-audio.py --advertise nohost.invalid --print-listing ENTRY` into the
    debug feed) - and tap the tile: the line reads "Trenger internett" / "Needs the internet", not "Kan ikke spilles nå";
    `adb logcat -s VibbPlayer` shows "Playback failed: ERROR_CODE_IO_... (200x): ... <- java.net.UnknownHostException:
    Unable to resolve host ..." with the real host and no query string. Stop `fake-audio.py` instead (a refused
    connect): today's "Kan ikke spilles nå", and the log names the `ConnectException`. A **public** RSS entry (not `lan`)
    pointing at fake-audio on 10.0.2.2: "Kan ikke spilles nå" (the guard's refusal, not the network), and the log's
    `PrivateAddressException`. Wi-Fi and mobile data both off: still "Ikke lastet ned - trenger Wi-Fi" (the streaming
    gate first).
17. **A restored task** (qa-music-splash-b Q-1): open the player, Home, `adb shell am kill me.vibb.music.debug` (a
    background kill keeps the task and its saved state), tap the tile: the system splash's rings, then "vibb music" under
    them, then the library fading in - no plain night frame (record at 24 fps as step 12). The same with "Don't keep
    activities" on and the music paused in the service (a warm process, a new activity).
18. **A warm start's two fades** (Q-3): with music paused (the service alive) and "Don't keep activities" on, tap the
    tile and step the recording: the system splash fades, the wordmark is then held at full strength at least 400 ms
    (round M1b), and only then the library fades in - never a faint wordmark flash. A start with no system splash at all
    (if one can be made, e.g. from inside the app's own task) holds the app splash about 0.5 s with the content ready
    (the 400 ms hold and the 500 ms hand-over wait run concurrently from its first frame, not 0.9 s).

## 6w. Dagen: the day view, the title rules, the glance's line and reminder row, the kid's own events (design 69)

Branch `day-view`; `wm density 240` (§3b). Logcat: `CalendarStore`, `GlancePanel`. Nothing below needs adb for the
feature itself - the seed is a test shortcut; the real path is ICSx5 from the catalog (step 5).

1. **Seed the provider** (quote each command whole for the device shell; if the shell's uid lacks the calendar
   permission, skip to step 5's feed):
   ```
   adb shell "content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=dagen&account_type=LOCAL' --bind account_name:s:dagen --bind account_type:s:LOCAL --bind name:s:Skolen --bind calendar_displayName:s:Skolen --bind ownerAccount:s:dagen --bind calendar_access_level:i:700 --bind sync_events:i:1 --bind visible:i:1"
   adb shell "content query --uri content://com.android.calendar/calendars --projection _id:name"
   adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:<id> --bind title:s:'Gruppe 2- skoleåret 2026/2027: Matte' --bind description:s:'Viktig at man er klar 08.30. Ta med gymtøy.' --bind dtstart:l:<ms> --bind dtend:l:<ms> --bind eventTimezone:s:Europe/Oslo"
   ```
   A weekly one: `--bind rrule:s:'FREQ=WEEKLY;BYDAY=MO' --bind duration:s:PT6H15M` instead of `dtend`; all-day:
   `allDay:i:1`, `eventTimezone:s:UTC`, UTC midnights. `date +%s000` gives now in ms. **A colon inside a `--bind`
   value must be escaped as `\:`** (`title:s:'Skole\: Matte'`), else `content` answers "Binding not well formed".
2. **The tile and the page**: after a sync Home has "Dagen" right after the phone book (the blue tile); the PWA's Device ›
   Calendar lists "Skolen · Phone" (the report) and the local "Min kalender" once Dagen was opened (the kid may add events
   by default). Dagen: the heading is the weekday over the date on every page, today too; "← I dag" on a later day,
   "I dag →" on an earlier one, none on today; swipe a year out and back (endless both ways, a 10 dp peek of the
   neighbours' cards at rest: their edges show at x 0-10 dp on both sides, the card 16 dp in); "Pågår nå" and the peach ring on an ongoing event; "Ingenting i kalenderen." on an empty day; a far-future
   day (2040) is an empty day, no error.
3. **Caps and "›"** at font 1.0 and 1.3 (§3b): a long title and a long description wrap to two lines each with "…", and
   only those rows show the small "›"; a row whose text fits has none. At 1.3 the header's date under the weekday is
   whole (the header grows), an all-day row's time column is "All" over "day" ("Hele" over "dagen"), and in en-US every
   time carries AM/PM ("12:38 AM", the column as wide as the page's widest time); "Ny avtale"'s day reads "Tuesday,
   October 27". A tap opens the event's page with the whole
   description; a URL in it is not a link. TalkBack: one node per row, "Pågår nå, 14:45 til 15:30, …", action "Åpne
   hendelsen".
4. **Rules from the PWA**: add "Starts with «Gruppe 2- skoleåret 2026/2027:» · Skole: + the rest"; the preview shows
   "Skole: Matte" while typing (no page jump, no `settings_saved` line); after the next sync Dagen shows "Skole: Matte";
   a "Contains" hide on the description ("gymtøy") hides the event - **move the hide rule above the "Starts with" rule
   first** (Title rules' up arrow): the first rule that matches wins, and added last the "Starts with" rule matches the
   title before the hide is asked (as designed, design 69 §3.4).
5. **The real path**: ICSx5 from the catalog's F-Droid source, ticked for this phone, On Home off under Apps (it stays
   allowed, gone from Home and the drawer; in a pause it shows); in a pause add a feed from the host (`python3 -m
   http.server 8088`, `http://10.0.2.2:8088/dagen.ics`) with a weekly series with an `EXDATE` and a moved instance, an
   all-day event, a weekend camp, a UTC RRULE across 2026-10-25, a `VALARM` of 15 min, and the itslearning shapes ("R
   omerriket", long descriptions, "5. trinn"). **ICSx5 syncs only after its own "Calendar access - GRANT" card on its
   start screen is granted** (in the pause; until then its sync fails silently - from adb: `requestsync -n
   "'Calendar subscriptions'" -t at.bitfire.icsdroid -a com.android.calendar -F --is --ib --ex`). Check the [verify] items: the calendar's NAME is the feed URL (the key
   stays across a rename of the display name), its account type `at.bitfire.icsdroid`, access level 200; with a time
   rule on, ICSx5 is not suspended (`dumpsys package at.bitfire.icsdroid | grep suspended`) and still syncs; Dagen's
   open asks a sync (`dumpsys content` shows a manual sync of the account); `DISALLOW_MODIFY_ACCOUNTS` doesn't stop
   ICSx5 adding its account.
6. **The glance** (lock the phone): with both lock switches off, nothing of the calendar on the glance. "Next event line"
   on: "Neste: Skole: Matte 10:00" under the alarm line in its style; with music + 3 message rows and 3-button
   navigation it yields (row 3 stays partly in sight). Reminders "The calm row" and the calendar's reminder "15 min
   before": 15 minutes before the event the row comes first ("… om 15 min", the start under it), silent - no sound, no
   vibration, no heads-up, nothing in the shade, the screen stays dark until pressed; it counts down to "nå" and is gone
   at the start; a swipe sideways does nothing at all (not dismissed, no keypad), TalkBack has no "Fjern"; its tap asks
   the keypad ("Skriv koden for å åpne Dagen") and the code opens Dagen on that day (its own task, `wm_destroy_activity`
   shows no `DayActivity`); under a time rule the tap gives the plain keypad. The master
   switch off removes it whatever the calendar says; the "Neste" line yields while the row is up. "own": a `VALARM` of
   15 min gives the row at 15, a day-before alarm none. Dagen open while a reminder is up shows it under the header.
7. **The kid's own events**: the "+" is there from day one (the local "Min kalender"); "Ny avtale" with what, day (the
   shown day), from, length, place → Lagre → the row with a peach start (since 2026-11-09; green before) and "Min kalender" as its second line; its page
   has Endre and Slett; a feed's event has neither. In the PWA pick a DAVx5 calendar the kid may write to (a CalDAV test
   server, e.g. Radicale): the new event uploads with its `vibb-` UID, comes back on the next sync and can still be
   changed; "Nowhere (no +)" removes the "+". **The pickers** (the user's jellystar2, 2026-10-27): "Ny avtale"'s day and
   time pickers at font 1.0 and 1.3, with the system in light and in dark mode (`adb shell cmd uimode night yes|no`):
   every day number, the weekday row and both month arrows visible on the night panel, the chosen day night on peach;
   the time picker's clock-face numbers visible, the chosen one night on peach.
8. **Dagen's own task** (2026-10-27, branch `day-task`): open Dagen from Home, tap Recents (`KEYCODE_APP_SWITCH`) - a
   card titled "Dagen" with the launcher's icon shows the day (no empty frame); Home - Android's task-close animation
   (the view shrinks away, not the theme's fade); Back from the day - the same; the reminder row's tap + code from the
   lock - the same card, on the reminder's day. An event page open, then Home, then the tile: the task comes back with
   the page on top (as any app); the reminder row then lands on the day view itself. A time rule beginning while Dagen
   is open, then Recents › Dagen: the time-rule screen comes back on top at once (`dumpsys activity activities | grep
   -E "DayActivity|LockActivity"`); `adb shell dumpsys activity recents | grep -c "\.day"` is 1.
9. **A colour per calendar** (2026-10-27, branch `calendar-colours`, mockup §6 B): seed a second LOCAL calendar
   ("Fotball", step 1) with an event on the same day as Skolen's. In the PWA's Device › Calendar every calendar but
   the kid's own pick has a "Colour" row ("Farge") under its Reminder: before any save its select says "Pick for me"
   and no swatch shows; change anything on the form (a reminder) - the page comes back at the same place, not the top,
   and every shown calendar now has a swatch and its name selected, each a different colour (Skolen Sky, Fotball
   Forest, the kid's "Min kalender" none - peach is its own); pick "Sea" for Fotball and the swatch follows. A save on
   the links group alone does the same (add a link, §6y, without touching the calendars form: every reported calendar
   gets a swatch); a phone set up on the phone alone gets its dots from its own report as soon as it has two
   calendars (QA L7). After the
   sync Dagen's rows: an 8 dp dot in the calendar's colour in its own column before the time, the start **and** the
   end time of every row at one left edge (a row of a calendar without a colour keeps the empty column), the kid's own
   event's dot peach; under the list the key "● Skolen   ● Fotball   ● Min kalender" only on a day with events from
   more than one calendar (a day with Skolen's alone has none); TalkBack reads a row on such a day with its calendar
   last ("…, Gym, Skolen") and the key as "Kalendere: Skolen, Fotball". Lock the phone with the quiet reminder row
   up: the disc holds a 16 dp dot in the calendar's colour instead of the calendar glyph (the glyph stays for a
   calendar without one); the row's subtitle is the time alone - the calendar's name is the key's job on Dagen, not
   built on the lock row (QA L1). Hide Fotball in the PWA, seed a third calendar with an event (it takes the next
   colour, not Fotball's - a hidden calendar's colour stays reserved, QA M1), then show Fotball again: three different
   dots, three entries in the key. Delete the third calendar from the provider: after the phone's next report its
   Colour row is gone and its colour free again (QA L2). The phone's own "Min kalender" never has a Colour row
   (QA L3). A `color` the launcher doesn't know (edit the policy by hand) is no dot, never a wrong one.

**Run 2026-10-27, E5** (`docs/testing/2026-10-27-e4-e5-run.md`): steps 1-7 on `jelly36` (en-US, UTC) passed except: the
reminder row's tap + code lands on Home, not Dagen (`DayActivity` started from the PIN lock without a new task - High),
the header's date line is clipped at font 1.3, and the 10 dp peek doesn't show. Every [verify] item of step 5 passed
with ICSx5 2.4.3 from the catalog (grant its calendar access on its own start card in the pause, or it never syncs). The
"Contains" hide of step 4 works once moved above the "Starts with" rule (first match wins). A `--bind` value with a
colon needs `\:`. Device checks: Dagen on 2040 (the provider answers), TalkBack's action words, music + 3 rows with
3-button navigation, the DAVx5/Radicale half. **Fixed on `day-view` after the run** (to re-check in the next run): the
reminder row's Dagen in its own task (H1), the header and the time column at 1.3 (M1), the peek (L1), a sideways swipe
on the reminder row doing nothing (L2), AM/PM and "Tuesday, October 27" in en-US (L4).

**Run 2026-10-31 (E6)** (`docs/testing/2026-10-31-e6-run.md`, `jelly36` in Europe/Oslo): steps 8 and 9 passed. Step 8:
the Recents card `Vibb One (debug) Dagen`, one task with the `.day` affinity, the reminder row's code opens Dagen on the
reminder's day with no `wm_destroy_activity` of `DayActivity` (E5's H1 fixed), the event page comes back on top from the
tile, a time rule beginning over Dagen puts the time-rule screen on top and Recents › Dagen brings it back at once (the
bare `grep -c "\.day"` counts the one task's lines - 9 here; count `Recent #… A=…debug.day` instead). Step 9: the dots
`[45,235][57,247]` (8 dp) with every time at x=66, the key only with more than one calendar, TalkBack's calendar last,
the lock row's disc a 16 dp dot of exactly SEA (console screenshot) with the time alone under it, "Pick for me" before
any save, the auto-pick at the link add and at a report, a hidden calendar's colour reserved (Kor took Berry), a deleted
calendar's freed at the next report (K4 took it), "No free colour" with eight taken. E5's fixes seen: the 10 dp peek
(`[0,138][14,231]`), AM/PM, the header whole at 1.3. The animations and the 390 px page are device checks; a hand-edited
`color` wasn't run. **Note for the poster**: the calendars form carries one `key=` per calendar - a tool that folds
repeated fields into one (`pwam.py`) updates only the last calendar.

## 6y. Calendar links: a link pasted in the PWA, the phone's own calendar of it (design 72 step 1)

Branch `calendar-feeds-1`; `jelly36`, `wm density 240`. Logcat: `CalendarFeeds`, `CalendarStore`. No ICSx5. The feed is
served from the host as in §6w step 5: `python3 -m http.server 8088` in a scratch directory holding `school.ics` (start
from `server/testdata/calendar_feeds/series.ics` and `itslearning.ics`), the link `http://10.0.2.2:8088/school.ics`.

1. **Add**: Device › Calendar › Calendar links (Kalenderlenker): paste the link, no name -> the page lands on the
   link's row (`#link-<uid>`), the name is the feed's `X-WR-CALNAME` (else the host), the row says "Hentet hh:mm · N
   hendelser" and, since `10.0.2.2` is the host's LAN, the plain-http warning. "Calendars shown" lists it at once:
   "Kalenderlenke · Venter på telefonen". A `webcal://` link works the same; `http://` to a public host is refused,
   the form as typed. Without the music key (rename `data/keys/music-secret.key`, restart) the form says why.
2. **The phone**: at the next sync (the add nudges) a LOCAL calendar `vibb-feed-<uid>` under account `Vibb One`
   appears: `adb shell "content query --uri content://com.android.calendar/calendars --projection _id:account_name:account_type:name:calendar_displayName:calendar_access_level:cal_sync1:cal_sync2"`
   - access level 200, `cal_sync1` = the policy's rev. The PWA's row loses "Venter på telefonen"; Dagen shows the
   events: the weekly series without its EXDATE day, the moved instance on its new day and time, the cancelled one
   gone, the all-day camp on its dates, "skoleåret" whole and "R omerriket" as the feed has it, a title rule on it.
   The VALARM's minutes show in Dagen's "own" reminder mode.
3. **[verify] the provider's forms**: `adb shell "content query --uri content://com.android.calendar/events --projection _id:_sync_id:original_sync_id:original_id:originalInstanceTime:rrule:exdate:duration:eventTimezone:eventStatus --where \"calendar_id=<id>\""`
   - overrides linked (`original_id` = the master's `_id`), the EXDATE honoured (no instance that day - else set
   `EXDATE_COLUMN = false` in `day/FeedEvents.kt` and check the cancelled override rows instead), UNTIL ends the series
   on its day, the cancelled override makes no instance (`Instances` for that day).
4. **DST**: the itslearning `Z` weekly series moves an hour locally across 2026-10-25 (faithful); a `TZID=Europe/Oslo`
   one stays at its time across 2027-03-28.
5. **A change**: edit `school.ics` (a title), "Hent nå" -> the row's time updates, the next sync rewrites only that
   series (logcat `Applied a calendar link: 1 rows deleted, 1 series written`); a second sync makes no request.
   "Hent nå" again within 5 minutes: "Hentet for et øyeblikk siden".
6. **Empty**: replace the file with an empty `VCALENDAR` -> the row says "Lenken ga ingen hendelser - viser forrige";
   the day keeps its events (until the third empty fetch or 24 h).
7. **2000 events**: a generated feed of 2000 events (as `ics::tests::the_caps`) -> time the first apply (logcat
   timestamps from `Made a calendar link's calendar` to `Applied a calendar link`); kill the process mid-apply
   (`am kill`), the next sync completes it.
8. **Remove**: Fjern… -> the calendar goes from the phone at the next sync, its "Calendars shown" row greys (settings
   kept); add the same link again -> the same calendar key, its settings back.

**Run 2026-10-31 (E6)** (`docs/testing/2026-10-31-e6-run.md`, `jelly36` started with `-timezone Europe/Oslo`): steps
1-8 passed, every [verify] item included - the provider linked the overrides (`original_id` = the master's `_id`),
honoured both EXDATE forms (`EXDATE_COLUMN` stays true), ended the UNTIL series on its day with the RDATE after it, made
no instance for the cancelled override; the `Z` series moved an hour across 2026-10-25 and the `TZID=Europe/Oslo` one
stayed at 16:00 across 2027-03-28; 2000 events applied in 3.0 s; a SIGKILL mid-apply (`run-as me.vibb.launcher.debug
kill -9 <pid>` - `am crash` does nothing to the device owner) left 600 rows and the restart's sync rewrote all 2000
(`600 rows deleted, 2000 series written`). **The feed's link must be the VM's own LAN address** (`http://192.168.1.92:8088/…`
here): the server fetches it, and `10.0.2.2` is only the emulator's name for the host. Three words of this section don't
match the build: a `webcal://` link is https (a plain http host refuses it: "Couldn't reach the link"), a renamed music
key is replaced by a new one at start (the form says nothing; the old links' URLs are `key_lost` at their next fetch),
and a removed link's row doesn't grey - it reads "· Phone" until the phone's next report and then goes, with the
settings kept (the re-add got Sun back). The 390 px page wasn't rendered this session.

## 6z. CalDAV write-back: the kid's own events in the parent's calendar (design 72b)

Branch `caldav-write`; `jelly36`, `wm density 240`. Logcat: `KidCaldav`, `CalendarFeeds`, `CalendarStore`; the server's
log (`calendar_kid_events`, never a title). No DAVx5. **Radicale on the host** (as design 72 §10), plain http. The
server, not the phone, talks to Radicale, so its address is **the host's own LAN address** (`hostname -I`; the server
judges it LAN and allows plain http with the warning) - not `10.0.2.2`, which is only the emulator's name for the host
(E6, E7 L4). Radicale reads its rights file only at start, and the server's discovery needs `R` on the root
(`collection:` empty) - without it the PROPFIND gets 403 and the PWA says "Passordet ble avvist". `rights` writes the
file with the calendars named as its arguments read-only, `radicale` (re)starts it:

```
python3 -m venv ~/radicale && ~/radicale/bin/pip install radicale
mkdir -p ~/radicale/data && printf 'forelder:test-only-not-a-real-password\n' > ~/radicale/users
cat > ~/radicale/config <<EOF
[server]
hosts = 0.0.0.0:5232
[auth]
type = htpasswd
htpasswd_filename = $HOME/radicale/users
htpasswd_encryption = plain
[rights]
type = from_file
file = $HOME/radicale/rights
[storage]
filesystem_folder = $HOME/radicale/data
[logging]
level = debug
request_header_on_debug = True
EOF
rights() {  # the read-only calendars as arguments (none: all writable)
  local IFS='|'
  { printf '[root]\nuser: .+\ncollection:\npermissions: R\n'
    [ $# -gt 0 ] && printf '[ro]\nuser: forelder\ncollection: forelder/(%s)(/.*)?\npermissions: r\n' "$*"
    printf '[owner]\nuser: forelder\ncollection: forelder(/.*)?\npermissions: RrWw\n'; } > ~/radicale/rights
}
radicale() { pkill -f 'radicale --config'; sleep 1; ~/radicale/bin/radicale --config ~/radicale/config \
  > ~/radicale/radicale.log 2>&1 & }
rights; radicale                    # both calendars are made while everything is writable ...
D=http://localhost:5232/forelder; A='forelder:test-only-not-a-real-password'
curl -u "$A" -X MKCALENDAR $D/familie/ ; curl -u "$A" -X MKCALENDAR $D/skrivebeskyttet/
rights skrivebeskyttet; radicale    # ... then "skrivebeskyttet" turns read-only
```

The server runs on the host (`dev-rebuild.sh`); Device › Calendar › Legg til kalender… › CalDAV-konto: server
`http://<the host's LAN address>:5232/`, username `forelder`, the password above. Tick "familie" and "skrivebeskyttet" on
the phone's account page. Before the pick, the kid has "Lekser" (today, ended), "Kino" (tomorrow) and "Tur" (next week)
in "Min kalender" (Kalender's "+"). Listing Radicale: `curl -s -u "$A" $D/familie/ -X PROPFIND -H 'Depth: 1'`, a
resource `curl -s -u "$A" $D/familie/<uid>.ics`.

| Step | Expected |
|---|---|
| Open the select in Barnets avtaler | "familie · <host> (skrives av serveren)" is offered; "skrivebeskyttet" is not (Test read it as read-only). |
| Pick "familie" | Only the kid's coming events appear in Radicale (`curl`); not "Lekser". The status line "Skrevet hh:mm · 2 avtaler" after the next fetch, and no "venter på å bli skrevet" beside it once they are written (E7 L6). |
| The kid adds "Kino 2" in Kalender | It is in Radicale within a sync (the save asks for one) and shows once on the phone (not twice: the feed skips it). |
| Edit "Kino" by `curl -X PUT` (new DTSTART) | The phone shows the new time after the next fetch and sync; the status line meanwhile "1 endring fra kalenderen venter på telefonen". |
| Make "Tur" recurring by `curl` (add `RRULE:FREQ=WEEKLY`) | It leaves "Min kalender" and shows read-only from the feed (in the calendar's colour); the kid can't change it. |
| Delete "Kino 2" by `curl -X DELETE` | It is gone on the phone. |
| The kid deletes "Kino" | It is gone in Radicale, and no ghost appears on the phone between the delete and the next fetch. |
| Offline: two added, one deleted, back online | Radicale matches the phone after the next sync. Airplane mode leaves the emulator's path to the host open (E7): stop the server instead. |
| An event far ahead (2028) | It is written. |
| `rights familie skrivebeskyttet; radicale` (no Test), then the kid adds an event | Orange "Kalenderen tar ikke imot avtaler - velg en annen" and "1 venter på å bli skrevet"; the account stays signed in (no "Passordet ble avvist"). |
| `rights skrivebeskyttet; radicale`, then "Hent nå" on the calendar (or the kid's next change) | The event is in Radicale and the orange line is gone at that fetch (E7 L5); without either it goes at the calendar's hourly fetch. |
| Untick the marked calendar on the account page | The notice "Barnets avtaler skrives ikke lenger til familie - …", the select falls back to "Min kalender (på telefonen)"; what was written stays in Radicale. |

Also look at: every PUT/DELETE in Radicale's log carries `If-None-Match`/`If-Match`; no event title in the server's or
the phone's log. **A real account** (the user's iCloud preferred, else Fastmail or Nextcloud), each a [verify] of the
note: the UTC body is accepted and shows at the right local time in the account's own app; a PUT answers with an ETag
(or the GET after it finds our body); Test fills `writable` from `current-user-privilege-set`; iCloud's rewrite of the
body after a PUT (`X-APPLE-*`, its own DTSTAMP) is not read as a parent's edit (no hand-back after the first fetch).

## 6x. The weather on the lock glance (design 75 step 2)

Branch `lock-weather`; `jelly36` at `wm density 240` (the Jelly Star's 320 dp) and `handy36` at its own density (360 dp);
logcat `WeatherPull`, `WeatherStore`, `WeatherLockPanel` (counts and classes only - never a place or a temperature).
The server needs no MET: point the phone at a server whose `weather_cells` row holds the contract's recorded hours, or
serve `server/testdata/weather.json`'s `answer` / `answer_words` from a throwaway endpoint as the phone's
`/api/devices/weather` (and a `now_answers` row as `/weather/now`). Device › Weather on, Location Regularly with a
fresh fix, or a weather zone. Set the emulator's clock inside the answer's hours (2026-11-03 15:20 Europe/Oslo).

1. **The line**: symbol, degrees and, with `answer_words`, "Regn fra kl. 16" beside the date at 360 dp / font 1.0;
   on its own line under the date on the Jelly Star (every day, the reference "Regn fra kl. 14" decides); at font 1.3
   and 2.0 (`adb shell settings put system font_scale`): the place per design §5.3's table, words dropped - never cut.
   Across an hour's turn (clock to 15:59 -> 16:00) the words change to "Regn nå" and the mark does not move.
2. **The card**: a tap on the mark (and on its 48 dp hit area just above/below it) folds the day out under the
   face's lines; morning/afternoon/evening, the current column raised, the mm in the rain blue, "2° / 7°" (no place name since 2026-11-04) and
   "Mørkt kl. 17:02"; a second tap or the card folds it; power off/on folds it; a swipe up from the mark still asks for
   the unlock. The "Neste" line stays when the card opens.
3. **The nowcast**: open the card with `/weather/now` answering at once -> "Regn om ca. 25 min" at the card's top;
   delayed 2 s (a slow endpoint) -> nothing inserted, the next open within 2 min shows it at once.
4. **Stale**: the answer's `checked_at_ms` 61 min old -> "Oppdatert kl."; 3 h + 1 min -> no line; the switch off in
   the PWA -> the line goes at the next sync and no `weather` request leaves the phone (logcat, the server's log).
5. **TalkBack**: the mark reads "Været nå: Delvis skyet. 7 grader. Regn fra kl. 16." with expand/collapse; the card
   reads its periods, place, low, high and the dark.
6. **The look**: the symbols on a bright photo wallpaper and on Sky/Sunset; the clock twin on `jelly36` unchanged with
   the weather off and on (design 59d's profile: the date row's baseline is the date's).

## 6v. The SMS engine (design 77 step 1, no screens yet)

Run on `handy36` (Android 16, `-wipe-data`) on 2026-11-09, branch `sms-engine`, and again after the code QA's fix
round (an allowed SMS shown, a stranger held and posted, the reply delivered; a re-delivery key per message in
`received_key`). A phone created by the server starts with both channels "Ikke styrt" (the fix round's M2): set SMS
"Styrt av Vibb" in the PWA once the phone has reported `sms_engine_v1`, or in `device_policy.sms_mode` for a test
database. Until step 4's PWA pages, the parent's answers are written to the server's
database by hand (`device_sms_names`, `device_sms_held.dropped_at`). There are no screens: check the engine's store
with `adb exec-out run-as me.vibb.launcher.debug cat databases/messages.db > m.db` (and `-wal`), the notifications
with `adb shell dumpsys notification --noredact`, the server's `device_sms_held` and `device_status.sms_state_json`.

- `dpm set-device-owner` right after a fresh boot may answer "already some accounts on the device" while GMS's
  account check is still busy (0 accounts listed): retry for a minute.
- After the first sync: `cmd role get-role-holders android.app.role.SMS` names the launcher; `call_state.state` is
  `unmanaged` (calls not managed), `sms_state.mode_applied` `managed`, `role_held` true.
- **A contact with `sms_in`**: `adb emu sms send +4791234567 Hei` -> a `message` row (`SHOWN`) and a `MessagingStyle`
  notification on `launcher:messages` with "Reply".
- **A stranger**: `adb emu sms send +4790000041 ...` -> a `held` row, no notification; the row reaches the server's
  `device_sms_held` within seconds (state `held`, `expires_at - at` = 24 h, no text).
- **A sender name**: `adb emu sms send DNB Kode 1234` - the console sends letters as an alphanumeric TP-OA (TON 101):
  held as `ALPHANUMERIC`/`alphanumeric`. `adb emu sms pdu <hex>` answers OK but delivers nothing on this image (no
  `NEW_SMS` in `logcat -b radio`, also for a numeric PDU), so an alphanumeric sender reading like a number can't be
  injected here - `SmsSenderTypeTest` and `SmsRulesTest` cover it.
- **The parent's answers**: `device_sms_names` gets `DNB`, the stranger's row a `dropped_at`; the next sync (another
  hold asks for one within a minute) drops the stranger's row (`dropped` on the server, gone on the phone) and
  releases DNB's message into `message` with its first receive time (`released` on the server).
- **The reply**: the shade's "Reply" on Mamma's notification -> an `OUT` row, origin `NOTIFICATION`, `SENT`, then
  `DELIVERED` by the emulator's delivery report. A sender name's notification has no "Reply".

### 6v, step 2: the kid's message screens (design 77 §6.7)

Run on `handy36` (`-wipe-data`, `wm density 240`) on 2026-11-09, branch `sms-screens`, against a throwaway server whose
database was seeded by hand: calls and SMS "Styrt av Vibb", "Block USB debugging" off, Mamma `+4790000001` and Pappa
`+4790000002` (calls and texts both ways) and Bestemor `+4790000005` (texts out only, no calls). The launcher's
language comes from the policy (`device_policy.launcher_language`, `nb`/`en`) - `cmd locale set-app-locales` is undone
by the next Home. A device owner can't be force-stopped: to make the phone sync after a database edit, kill its
process as its own uid (`adb shell run-as me.vibb.launcher.debug kill <pid>`) and start Home.

- **The tile**: after the first sync the role is ours and Home shows "Meldinger" after the phone book (teal, the chat
  glyph); `adb emu sms send +4790000001 ...` (and from Pappa) -> its badge counts the unread.
- **The list**: one row per conversation, newest first - avatar, name, the last line ("Du: ..." for the kid's own),
  the time in the system's clock format, a peach dot while unread. "Ny melding" lists Mamma, Pappa and Bestemor (the
  text-only contact too), never a free number.
- **The thread and the compose bar**: bubbles with "I dag"/"Today", the kid's on the right in the peach tint with
  "Levert"/"Delivered" under the last. Opening it reads it (the badge drops) and clears its notification.
- **QA N2, the scripted double-send**: type a reply, hide the keyboard (`input keyevent 111`; a tap with the keyboard
  up lands on it), then `adb shell 'input tap <send x y>; input tap <send x y>'` -> one `OUT` row (origin
  `KID_SCREEN`, one `client_id`) in `messages.db`, one bubble, `DELIVERED`.
- **Deleting**: long-press a bubble -> its peach ring and "Slette meldingen? / Det kan ikke angres." in place of the
  compose bar; Slett removes it. Long-press a row in the list -> "Slette samtalen med Pappa?"; Slett removes the row.
- **Ways in**: the contact sheet's Message (Mamma) opens her thread over the list (Back goes to the list, no "opens in"
  line); `adb shell am start -a android.intent.action.SENDTO -d smsto:90000005 --es sms_body Utkast -p
  me.vibb.launcher.debug` opens Bestemor's thread with "Utkast" in the field, unsent. The notification's tap: on this
  AVD the shade didn't open from adb in this run, so only its PendingIntent was checked
  (`dumpsys activity intents`: `ThreadActivity`, identified by the conversation id) - tap it on a phone.
- **The read-only history**: `sms_mode = 'unmanaged'` -> the role goes back to Google Messages, the tile stays (history),
  the list and the thread say "Meldingene her kan bare leses." / "These messages can only be read.", no "Ny melding",
  no compose bar. (Google Messages' own tile is on Home next to it then - in English both read "Messages".)

### 6v, step 3b: MMS pictures (design 77 D8, D10-D12, D14, D16)

Run on `handy36` (`-wipe-data`, `wm density 240`) on 2026-11-09, branch `sms-mms`, with step 2's seeded server plus:
Pappa's "Bilder i meldinger" off, Bestemor texts out only, and the phone's own number `+4790000010` in
`device_own_numbers` (slot 1). **What the AVD can't do, and the debug build's way round it** (src/debug's
`MmsDebugReceiver`, `adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.sms.MmsDebugReceiver ...`;
none of it is in a release build - `checkReleaseHasNoDebugHook`, `DebugHookAbsentTest`):
- the console's `sms pdu` delivers nothing (step 1's run), so no WAP push arrives: `--es push_hex <hex>` commits an
  m-notification-ind exactly as `MmsPushReceiver` does; `python3 -I scripts/fake-mmsc.py push N FROM LOCATION` prints
  one;
- the AVD's SIM (310260) has MMS off in its carrier config (`enabledMMS=false`: the platform's MMS service answers every
  download and send with error 7, and `cmd phone cc` is refused on this image): `--es stand_in
  http://10.0.2.2:8080/mmsc` plays the platform's part - the download fetched over HTTP and written through our own
  `MmsFileProvider` URI (its capped pipe), the send-req POSTed, the result sent to the same PendingIntent;
- the server's MMSC table is empty until step 0: `--es mmsc_host 10.0.2.2 --es mccmnc 310260` puts the fake server on
  the list (memory only - a process death forgets all three, so nudge syncs without killing the process: an SMS from a
  stranger asks for one, `adb emu sms send 4790000077 Hei`). Since design 83 the policy has no row for the AVD's SIM
  until a parent allows an address, and the hook's host joins that SIM's row - "6v, design 83" below says which
  scenario uses the hook and which the parent's allow.

The fake MMSC: `python3 -I scripts/fake-mmsc.py serve 8080 <out-dir>` (port 8080: D10 took 80, 443 or 8080 only;
since design 83 a host alone covers those three and any other port is its own `host:port` entry).

- **Fail closed without the table** (no `mmsc_host` yet): a push from Mamma starts the isolated process
  (`ActivityManager: Start proc ...:mmsparse ... /u0i1` - an isolated uid - and "isolated not needed" once unbound) and
  shows at once as "Bildet kunne ikke hentes" / "The picture couldn't be fetched", nothing fetched. Without the stand-in
  the platform's error 7 waits: "Bilde venter - trenger mobildata" / "Picture waiting - needs mobile data".
- **An allowed picture**: with the host and the stand-in, a push from Mamma -> `GET /mms/11` at the fake MMSC -> the
  bubble shows the picture (our own JPEG, 1200x900, re-encoded from the isolated process's pixels) and the text, the
  notification names Mamma with the text, and the MMSC gets an m-acknowledge-ind (`POST`, type 0x85). A tap opens it;
  "Lagre" puts `Pictures/Vibb One/Vibb-<random>.jpg` (no EXIF) into the gallery.
- **The other cases** (`push` N FROM LOCATION, then the store with `run-as ... cat databases/messages.db`):
  - Pappa (pictures off), `.../mms/12?from=%2B4790000002`: fetched, the text shown, "Et bilde ble ikke vist", no "+";
  - a stranger `+4790000041`: a `held` row with the push (84 bytes), **no GET**;
  - `http://attacker.invalid:8080/mms/14`: blocked `bad_location`, **no GET** (since design 83 kept as "Bildet
    kunne ikke hentes" and reported, still no GET; `bad_location` is a shape failure, `http://x@10.0.2.2:8080/mms/14`);
  - `.../mms/15-group` (To: the kid and Bestemor): a conversation "Mamma, Bestemor" with "Mamma" over the bubble; a
    text reply from it is one send-req to both (`To: +4790000001, +4790000005`);
  - `.../mms/16-stranger-group`: fetched, then blocked `group`, nothing shown;
  - `.../mms/17-gone` (404): "Bildet er ikke lenger tilgjengelig".
- **D16, the allow**: the stranger's contact added with only "Kan sende melding til" (by hand in the database until
  step 4's pages) -> at the next sync the push is judged again and only then fetched (`GET /mms/19...`), shown with its
  first receipt time. A released push whose fetched `From` isn't the push's (the fake MMSC's default `From`) is dropped
  after the fetch and counted `malformed` - D10's second From check.
- **Sending (D11)**: in Mamma's thread "+" -> "Ta bilde" / "Velg bilde" in the screen. "Velg bilde" opens the system
  photo picker (in kiosk too); a 3000x2000 JPEG with a GPS position in its EXIF became our 1280x853 JPEG of 176 KB with
  no EXIF, previewed above the field with a cross; Send -> one send-req (type 0x80) at the fake MMSC with that JPEG and
  the text, "Sendt" under the bubble. "Ta bilde" opens the AVD's camera (`com.android.camera2`, the allowed camera
  app) in capture mode; Done brings the picture back as a preview, its file in `cache/capture/` deleted once read.
- Screenshots of the run (en and nb) are in the session's `scratchpad/sms-mms/shots/`.
- **The store's upgrade from step 1/2 (v1 -> v2, code QA test 9)**, 2026-11-09 after the fix round: main 77671a37's
  build (step 2, store v1) installed, enrolled, an SMS from Mamma shown and a stranger's SMS held; two step-1 pushes
  added to its v1 `pending_push` by hand (a contact's and a stranger's - the AVD can't deliver a WAP push; pull
  `databases/messages.db`, insert, write it back under another name with `exec-in "run-as ... sh -c 'cat > ...'"`,
  `mv` it over and remove the `-wal`/`-shm`), then this branch's build installed over it (`install -r`, data kept).
  At its first open the store is version 2: the SMS and the held SMS are there, Mamma's push is judged (failed closed:
  no MMSC list yet) and the stranger's held with its push. With the fake MMSC put on the list, the next sync fetched
  Mamma's (code QA L10: an unknown-operator MMS is fetched once the list knows the SIM) and answered it with an
  m-notifyresp-ind (type 0x83, L2). The parse ran in its own instance (`...MmsParseService:parse3/u0i3`, L3); the
  "Velg bilde" picker was the system's (`com.google.android.photopicker`, M3), and the picture it sent matched the
  source to JPEG noise (pixels read with `pread`, L4).
- **Not on the AVD**: a real carrier's MMS service and MMSC, a real push, the platform's own byte stream into the
  provider (the stand-in writes it), HEIF/AVIF, the parse service dying on a crafted message (JVM tests:
  `MmsEngineTest`'s three deaths) - step 0 on the Jelly Star.

### 6v, design 83: the MMSC list and the parent's allow

**Run 2026-11-11** on `handy36` with its kept data (enrolled, `wm density 240`; Mamma `+4790000001` a contact with
"Bilder i meldinger", Pappa `+4790000002`, the phone's own number `+4790000020`), main at 8793669c. Step 3b's set-up:
the debug launcher (0.23.6, `install -r`), `fake-mmsc.py serve` on 8080 and on 8002, the stand-in
(`--es stand_in http://10.0.2.2:8080/mmsc`, in memory - set again after every process death), server 0.73.0 on the
host with a throwaway database, the PWA in nb driven by the host's Playwright at 360 and 320 CSS px. **The whole chain
ran**: report, question, allow, nudge, fetch. Below, a statement was **seen** unless it says "read in the code" or
"not run". Times are UTC on the host's clock, which was 1.0-1.3 s ahead of the phone's (a logcat line can read
earlier than the tap that caused it). The AVD's data was copied before the start and put back after it, checksums
compared. The AVD's SIM is 310260 (the phone reports it as "T-Mobile"): Android's list has five
names for it, all hints (`grep '^310260 ' server/testdata/mmsc_hosts.txt`), so the policy has **no row** for it
until a parent allows an address. Two ways onto the row, and each scenario uses one:

- **The hook** (`--es mmsc_host 10.0.2.2 --es mccmnc 310260`): for every run that is about something else than the
  list - step 3b's cases, reactions, the thread's pictures. In memory, merged into the SIM's row; it never reaches the
  server, so the PWA shows no address. Not used in this run.
- **The parent's allow** (no `mmsc_host` broadcast in this process's life - kill the process to forget one): for
  everything below. **On kept data, two things first** (both seen):
  - a push number the store has had before is dropped without a trace: `push 31 ...` logged "Push of 84 bytes kept:
    true" and then nothing - no bubble, no report, no GET. This data's store held `T-fake-0031`..`0035` and `0041`
    from earlier runs (read in the code: `MmsFlow.judgePush`, `PushOutcome.Duplicate`). The run used 131 and up;
  - the fake's default `To` is `+4790000010`, which is not this data's own number: a location without
    `?to=%2B4790000020` is fetched after the allow and then blocked as a group with a stranger (design 80; the phone's
    `blocked` row of kind `group`) - with push 131 the "Bildet kunne ikke hentes" bubble went and no picture came.
    Every later location carried `?to=%2B4790000020`.
  - *Kept and reported* - seen. `push 131 +4790000001 http://10.0.2.2:8080/mms/131` at 06:39:42.4:
    `I/MmsDebug: Push of 85 bytes kept: true`, the store's row `FAILED`/`mmsc_unknown`, "Bildet kunne ikke hentes" in
    Mamma's thread (`k1-thread.png`), **no GET**. The phone asked for the sync itself: a status report 2.6 s after the
    push with `sms_state.sim_operators` `[{"mccmnc":"310260","name":"T-Mobile"}]` and `mmsc_unlisted`
    `[{"at":...,"from":"+4790000001","host":"10.0.2.2","mccmnc":"310260"}]`, a `device_mmsc_hosts` row `open` and one
    `mmsc_host` event. The Calls page asks in `#mmsc`, right below `#held`, under its own heading "Bildeadresser" -
    not under "Meldinger som venter", which is not on the page while no message is held. The pass saw the first
    build's five blocks (`p1-question.png`): the question, the picture, "Adressen står ikke i Androids liste for
    SIM-kortets operatør (T-Mobile). Listen har:" and the five names, the warning and the allow's reach, then
    "Tillat" and "Ikke tillat". **Since the same day's shortening (the user: fewer words) the question reads** "Hente
    bilder fra 10.0.2.2?", "Bildemelding fra Mamma 07:39.", in orange "Ukjent adresse for T-Mobile. Tillat bare hvis
    Mamma virkelig sendte et bilde da.", "Tillat" and "Ikke tillat", and a closed "Mer" holding the list line and
    "Tillater du, hentes senere bilder fra adressen uten spørsmål, til du fjerner den." - the short form is the
    server's tests' alone so far, not seen on an emulator. The inbox has "Testtelefon: et bilde fra Mamma venter" /
    "Telefonen vil hente det fra en adresse du må godkjenne."; the device page's Meldinger row reads "1 melding
    venter".
  - *Allow -> fetch* - seen. "Tillat" at 06:44:02.4 -> the page comes back at `/devices/1/calls?mmsc=1#mmsc-1`, the
    row in the opened "Bildeadresser" group at the foot: "10.0.2.2", "T-Mobile · Tillatt 11. nov. av <account>",
    "Fjern". The phone: `I/MmsDebug: stand-in download of http://10.0.2.2:8080/mms/131: result -1, http 200`,
    `GET /mms/131` at the fake 1.75 s after the tap, and its cached policy has
    `"mmsc_hosts":[{"mccmnc":"310260","hosts":["10.0.2.2"]}]`. The picture in the thread: push 141 at the now allowed
    address was fetched 0.7 s after the push with no question, answered with an m-notifyresp-ind (`POST`, type 0x83)
    and shown (`a3-thread.png`).
  - *The hint* - seen. Push 132 at `http://mms.tracfone.com/mms/132?to=...` -> a report 2.1 s later, its own row and
    event; the question has "Adressen står i Androids liste over MMS-adresser for SIM-kortets operatør (T-Mobile)."
    in place of the list, and no warning line (in the short form: no orange line in the row, that sentence under
    "Mer"). "Ikke tillat" -> "mms.tracfone.com", "T-Mobile · Ikke tillatt",
    "Tillat likevel" in "Bildeadresser", no GET. A second push there (134): kept, no new row, event or question.
    (Never "Tillat" this one on an AVD with internet: the name is a real operator's.)
  - *Another port* - seen. Push 133 at `http://10.0.2.2:8002/mms/133?to=...` while `10.0.2.2` was allowed: kept, a
    report 2.1 s later, its own `open` row and event, and a question naming `10.0.2.2:8002` with the not-in-the-list
    text and the warning (in the short form: the orange "Ukjent adresse for T-Mobile…" in the row, the list under
    "Mer").
  - *A restart in between* - seen. The process killed between push 133 and the answer (a new pid, the stand-in set
    again, the store's row still `FAILED`/`mmsc_unknown`); "Tillat" on `10.0.2.2:8002` ->
    `stand-in download of http://10.0.2.2:8002/mms/133?to=%2B4790000020: result -1, http 200`, the GET at the 8002
    fake 0.8 s after the tap, the picture in the thread (`r1-thread.png`).
  - *Take back* - seen. "Fjern" on `10.0.2.2` (one tap, no confirmation) -> "Ikke tillatt" with "Tillat likevel";
    the phone's cached row was `["10.0.2.2:8002"]` within seconds. Push 142 at `http://10.0.2.2:8080/...`: kept
    (`FAILED`/`mmsc_unknown`), the next report names `10.0.2.2` again, the row stays declined, **no GET**, no event,
    no question. "Tillat likevel" six minutes later: fetched 0.8 s after the tap and shown with its first receipt
    time (6:49 a.m.).
  - *An expiry in between* - seen, with a notification whose `X-Mms-Expiry` is 100 s (the fake's is seven days; the
    run's own helper swapped the PDU's expiry field - `fake-mmsc.py` has no switch for it). Push 150 at
    `http://10.0.2.2:8003/mms/150?to=...`: reported and asked as the others. The question was still open, with
    nothing in it about the expiry, 1 min 52 s after the picture had expired; "Tillat" then -> no "stand-in download"
    line, **no GET**, the store's row `GONE`, the bubble "Bildet er ikke lenger tilgjengelig" (`e1-thread.png`), the
    PWA's row "Tillatt". As designed (design 83 §4; read in the code: `MmsFlow.unlisted()` leaves the notification's
    own expiry out, "its sender's word", and reports the address for seven days) - but a parent is asked about a
    picture that can no longer come, and allowing gives the kid only the "no longer available" bubble.
  - *A stranger in the callback window* - seen. The window was opened with an emergency call from the lock glance
    ("Nødsamtale" -> "Ringe 112?" -> "RING"; the AVD's dialer showed "Emergency number" with a running timer, ended
    after about 18 s): the next status report has `callback_window_until` ten minutes after the call and the server an
    `emergency_call` event (ten minutes is the provisional window: the call was off-hook for less than
    `PLACED_CONNECT_EVIDENCE_MS`, 20 s - read in the code, `CallRules.kt` and `PlacedCallWatch.kt`). Push 160 from
    `+4790000077` at `http://10.0.2.2:8004/mms/160?to=...` 36 s after the call was placed:
    `Push of 102 bytes kept: true`, the store's row `FAILED`/`mmsc_unknown`, **no GET**. The phone asked for no sync
    (none in two minutes); the report a PWA action nudged out, the window still open, names only the two addresses
    left unlisted from before, not `10.0.2.2:8004`; no row, no event, no question. The kid: "+4790000077, Bilde" in
    the list, and in the thread "Ukjent avsender", "Bildet kunne ikke hentes", "Du kan ikke svare her"
    (`s1-stranger.png`) - and it stays so, no parent is ever asked (design 83 §4).
  - *The SIM's operator changes* - **not run**: this AVD cannot change it. Its image is a user build
    (`ro.build.type=user`, `ro.debuggable=0`, no `su`), and the emulator writes
    `<avd>/modem_simulator/iccprofile_for_sim0.xml` anew at every start: its IMSI line set to 310410 with the emulator
    off was gone after the cold start and the SIM still read 310260 / "T-Mobile" (seen: `gsm.sim.operator.numeric`,
    the next reports' `sim_operators`). The JVM and server tests stand for it
    (`the_policy_has_the_reported_sims_entries`). Seen by the way: across that cold start the phone's cached row
    (`["10.0.2.2","10.0.2.2:8002"]`) held, and push 181 at the allowed `http://10.0.2.2:8080/...` was fetched at
    once with no question.
  - *A 253-character address on a narrow screen* - seen. The host: four labels of 63, 63, 63 and 53 letters and
    digits and `.invalid`, no hyphen, pushed as from Mamma; it came in through the phone's own status report, whole,
    as a real one would. The question at 320 CSS px (`n320-question.png`): the address breaks over nine lines inside
    its card, nothing is cut, both buttons are on the screen; `innerWidth` and the document's, body's and main's
    `scrollWidth` are all 320, the cell is 288 wide (16 to 304) with `scrollWidth` = `clientWidth`, and
    `scrollTo(500, 0)` leaves `scrollX` at 0. At 360 px the same in eight lines (`n360-question.png`). After "Ikke
    tillat", the row in "Bildeadresser" at 320 px (`n320-declined.png`): the address takes sixteen short lines in the
    left column, "Tillat likevel" stays on the screen at the right, "T-Mobile · Ikke tillatt" below; no sideways
    scroll. The inbox and the device page at 320 px: nothing past the viewport - their texts carry no address; the
    address is printed by `device_calls.html` alone (read in the code). The one thing on the Calls page reaching past
    320 px is not design 83's: the link `messages.google.com/disable-chat` in the RCS line's folded info text
    (`section#settings`, left 163, right 356), which makes no sideways scroll.
  - **Times**: push -> the report at the server 2.1-2.6 s (once 16.4 s: the 253-character push came 45 s after the
    report before it and its report exactly a minute after that one - the cause not looked into); "Tillat" -> the
    phone's GET 0.8-1.75 s.
  - **Also seen**: the inbox (`n320-inbox.png`) had five rows of the same two lines, one per address, differing only
    in their age - no row says which address, and a row still reads "venter" once its question is answered (read in
    the code: `calls_mmsc.rs::answer` marks the event read; the row stays in the list). After each cold boot a
    SystemUI "isn't responding" dialog ("Wait"), and once the stock launcher on top for a few seconds before Home -
    the AVD's, not design 83's.
  - Screenshots of the run are in the session's `scratchpad/mmsc-emu/shots/`.
- **Not on the AVD**: what an operator's notification names, whether a listed name matches it, which network the
  platform fetches on (the stand-in fetches over the AVD's only network), redirects - design 77's step 0 on a real
  SIM, with its two checks for design 83's gate (design 77 §9, "Design 83's gate, step by step": a test phone's debug
  launcher, this script and hook, **no stand-in**).

### 6v, step 5: the lock glance's Meldinger rows (design 77 §6.8)

Run on `handy36` (`-wipe-data`, `wm density 240`) on 2026-11-09, branch `sms-glance`, against a throwaway server
seeded as step 2's (calls and SMS "Styrt av Vibb", Mamma, Pappa, Bestemor) plus a Child PIN (`kid_pin_hash`/`_salt`,
PBKDF2-HMAC-SHA256, 210,000 rounds, 4 digits - the own lock, no Android credential). The emulator was started under
`flock ~/build.lock` and held it while it ran: a Gradle build that started under a first try OOM-killed it.

- `adb emu sms send +4790000001 ...`, two from Pappa and one from a stranger `+4790000041`, then power off and on:
  the glance shows "Pappa / 2 nye meldinger" and "Mamma / Ny melding", newest first, each with the contact's avatar
  and the teal message badge; no row for the stranger (held, D16). TalkBack's text (UI dump): "2 nye meldinger fra
  Pappa, 19:11".
- Meldinger's level set to Full text (then `device_policy.sms_lock_text = 1`; since design 82's migration 0135:
  Lock Screen & PINs > Notifications > Messages, or `UPDATE device_lock_glance_apps SET show_text = 1 WHERE
  device_id = ? AND package_name = 'me.vibb.builtin.messages'`, with `sms_mode = 'managed'`), a sync: "Pappa · 2 nye / Og matpakka", "Mamma / Middag kl 17, kommer du hj…" (one
  line under 720 dp).
- A tap on Mamma's row: "Skriv koden for å åpne Meldinger" / "Enter your code to open Messages"; the PIN opens her
  thread (`GlanceOpener: Glance: opened the messages`), Back gives the list. At the next lock-up her row is gone (read).
- Pappa's row swiped sideways: gone (`Glance: a row dismissed (sms)`); his next SMS brings it back as "· 3 nye".
- **The notice wake** (after the code QA's H1/M1, a6ba594d), with `device_policy.lock_wake_on_notification = 1` and the
  screen off, 35 s between steps (the gate's 30 s gap):
  - the first message after a row was swiped away lights the lock (`PinLock: Notice wake`);
  - the next message in that row doesn't;
  - a new row's message under `cmd notification set_dnd priority` doesn't;
  - with Do Not Disturb off again, it does.

  The AVD's "Stay awake" (`stay_on_while_plugged_in` 1) keeps a woken screen on past round 7's 15 s, so turn it off by
  hand between the steps (`docs/review/qa-sms-glance.md`, "L4's emulator run").
- The glance's window is `FLAG_SECURE`: screenshots with `adb emu screenrecord screenshot <host path>`; the language
  with `cmd locale set-app-locales me.vibb.launcher.debug --locales nb` right before the lock-up (Home undoes it).

### 6v, design 79: the phone book as one list, search, the kid's pictures

Run on `handy36` (`-wipe-data`, `wm density 240`, three runs) on 2026-11-09, branch `phone-book-list`, against a
throwaway server (calls and SMS "Styrt av Vibb", twelve phone-book contacts with Æ Ø Å names, `113` "Ambulanse", one
text-only contact; later 40 more, 54 in all). Missed calls: `adb emu gsm call <+number>`, 6 s, `adb emu gsm cancel`.
A sync on demand: `adb install -r` of the same debug APK (the process start syncs) - bouncing the network reopened the
SSE stream but didn't always sync. Typing into the setup dialogs: one character per `input text` (the IME dropped
some of a long string).

- Two missed calls from Pappa, one from Ola: the list leads with Pappa ("2 tapte anrop 9:51", red, the count badge) and
  Ola, then A-Å with Æ Ø Å last, "Venter på svar", "Ambulanse / Nødnummer" last with the red Ring. Leaving: Home's badge
  for Pappa is gone at once; the next visit has both in their A-Å place.
- A missed call while the phone book is in front: the call's screen comes over it (the visit ends), and the return
  starts a new visit at the list's top with the caller first.
- The emergency Ring asks "Ringe Ambulanse?" (Avbryt / Ring). During a live call every other Ring is dimmed.
- Font scale 1.3 and 2.0: the buttons on their own line under the name; the header keeps room for the "+".
- Search "o": Ola, Ørjan (prefix), Sofie Østby (a word), Bestemor, Kristoffer Bergersen (inside), the matched part in
  peach, "5 treff". "Lina": "Fant ingenting" and "Be om å legge til «Lina»" -> the form with the name filled in.
  Meldinger's "Ny melding" with "e": six hits by name.
- The request with a picture: the circle -> "Bilde av Lina" -> Ta bilde (the emulator camera) -> the framing (drag) ->
  Bruk bildet -> the circle shows it; Send -> "Sendt"; the row under "Venter på svar" shows the picture. Adding Lina in
  the server's tables and a sync: the request's row goes and Lina's contact has the picture (the hand-over);
  `files/contact_request_photos/` and `cache/request_photo_draft/` are empty.
- The card's long-press on the photo: "Bilde av …" with Velg bilde / Ta bilde / Fjern bildet ditt. A rotated JPEG (EXIF
  orientation 6) from the picker frames upright; a 60 MB JPEG is refused (no framing, straight back to the card). Fjern deletes the
  file. Home's long-press on Mamma opens only the card. Mamma removed on the server at a sync: her picture's file goes.
- Not checked here: TalkBack's custom actions (the row's sentence was read from the UI dump), the Jelly Star's fling
  through 50 rows (§9's pocket-call check), an incoming call before the first unlock (the AVD has no Android lock).
- **After the code QA's fix round** (a fourth run, with a Child PIN `1234` in `device_policy`): Home's card -> "Bilde
  av Mamma" open -> power off and on: the lock's glance alone, and after the PIN Home has neither the card nor the sheet
  (M1). A missed call from Pappa, the phone book, Home, then the phone book started and tapped at once: the tap lands
  on an empty list, no card opens, the list fills A-Å (L1). "o" typed with the keyboard up: Back closes the keyboard
  ("o" kept), Back clears it, Back leaves (L10). "o" 5 hits, "ø" (Gboard: hold o) 2 - Ørjan, Sofie Østby -, "å" (hold
  a) 1 - Åse, not Anna (M3). A request with a picture from the picker: "Sendt / Mamma og pappa får beskjed …" (L5), the
  waiting row, Lina added on the server, a sync (`adb install -r`): Lina's contact has the picture,
  `contact_request_photos/` and `request_photo_draft/` are empty.

## 6aa. SMS reactions (design 81 steps 1-3)

Run on `handy36` (`-wipe-data`, `wm density 240`) on 2026-11-10, branch `sms-reactions` after merging main edeb78a6;
what was seen, and what an emulator can't show, is in `docs/review/qa-81-code.md`, "Emulator pass". The setup of §6v:
a server, the phone enrolled, SMS and calls "Styrt av Vibb", contacts with `sms_in`, `sms_out` and `mms` (the run used
Mamma `+4790000001`, Pappa `+4790000002`, Bestemor `+4790000005`), the own number `+4790000010` for groups. On
`jelly36`, or `handy36` with `adb shell wm density 240` for the Jelly Star's 320 x 569 dp (back with `wm density
reset`). The engine's store and the notifications as in §6v (`messages.db` and `-wal`, `dumpsys notification
--noredact`, `dumpsys vibrator_manager`'s "Recent vibrations"); the console's `sms send` takes the text after the number
as it is, quotes, `«»` and emoji included (quote the whole command for the shell).

- **Seed the lock-task features as a real phone gets them** (`device_policy.lock_task_features = 63`, the server's
  default): with 0 there is no NOTIFICATIONS feature, and in kiosk Android mutes every notification
  (`dumpsys notification` shows `mDisableNotificationEffects=true`). The same happens while handy's PIN lock is up
  (`featuresWhileLocked`) - so check sounds and buzzes unlocked.
- **The store's upgrade:** install main's debug build first, enroll, receive a few SMS (store v4), then this branch's
  build with `install -r`; `run-as ... cat databases/messages.db` (and `-wal`): `PRAGMA user_version` 5, the four
  `reaction_*` columns and `message_reaction`. A fresh store to compare with: kill the process from its own uid
  (`run-as me.vibb.launcher.debug kill <pid>` - a device owner can't be force-stopped), `run-as ... rm -f
  databases/messages.db*`, Home, one SMS.
- **A sync:** after a database change, kill the process as above and start Home (its start sync); while the debug
  hook's `mmsc_host`/`stand_in` are needed (memory only), an SMS from a stranger asks for a sync instead.
- **Android's notification cooldown** attenuates a buzz that follows another notification from the launcher within
  seconds: wait a minute between the posts you measure.

- **An incoming reaction attaches:** the kid sends Mamma "Kommer kl 5"; then
  `adb emu sms send +4790000001 'Likte «Kommer kl 5»'` -> the thread shows no new bubble, a chip with 👍 on the kid's
  bubble's bottom-left edge; `message` has the row with `reaction_to` = the bubble's id, `reaction_op` `ADD`; Home's
  badge and the list's last line don't change. The same with `'Liked “Kommer kl 5”'`, `'Loved "Kommer kl 5"'` (straight
  quotes), `'Satte et hjerte ved «Kommer kl 5»'` (replaces the 👍), `'Reagerte med 🎉 på «Kommer kl 5»'`, and
  `'Fjernet 🎉 fra «Kommer kl 5»'` (the chip goes, no notification).
- **An unknown form stays text:** `'Likte «filmen»'` (no such bubble), `'Reacted with a sticker to “Ok”'`,
  `'Liked “Kommer kl 5” !'` -> ordinary bubbles, notified as messages.
- **The notification:** after an add, `dumpsys notification --noredact` shows the conversation's notification on
  `launcher:messages_reactions` with the line "Reagerte med 👍 på «Kommer kl 5»" and public "Ny reaksjon"; the
  emulator's vibrator (`dumpsys vibrator_manager`) one 40 ms effect (80 ms until 2026-11-10), no sound - with the phone unlocked (above). Under a time rule without calls, and with
  Do Not Disturb on (`adb shell cmd notification set_dnd priority`, back with `off`), the same post goes on
  `launcher:messages_silent`, no vibration. No wake on the lock either way.
- **The long press:** on Mamma's bubble the panel - two rows of four 56 dp cells (❤️ 👍 👎 😂 / ‼️ ❓ + and the kid's
  other reaction), "Fjern reaksjonene" once Mamma reacted to it, "Slett" - fits at 288 dp; a tap on the thread closes
  it; TalkBack's actions list "Reager med ❤️" ... "Slett". On the kid's own bubble, or under a time rule, only
  "Fjern reaksjonene" / "Slett", or the delete confirm at once.
- **Sending:** tap 👍 -> the chip shows dimmed at once, the stored `OUT` row's body is exactly `Likte «Hei»` (nb
  launcher) or `Liked “Hei”` (en), `SENT` -> full colour. "+" -> the picker: "Velg en reaksjon", the group strip, five
  columns, no skin tones, no empty boxes; pick 🎉 -> `Reagerte med 🎉 på «Hei»`, and 🎉 leads "Nylig brukt" next time
  - every opening starts at the top, whatever group the last one scrolled to (the run's one fix).
- **MMS:** with `scripts/fake-mmsc.py` (§6v step 3b) a group text reaction (`Likte «…»` to the kid and Bestemor)
  attaches in the group's thread when its text arrives; a picture plus the same text stays a picture message. While
  an MMS waits (mobile data off), `'Likte et bilde'` by SMS stays a plain message, whatever the MMS turns out to be (a
  text, a reaction, dropped); once a picture is fetched, the same words attach to it (code QA M1).
- **The store's SQL** (no JVM test opens SQLite; `docs/review/qa-81-code.md`, "What the emulator pass must check"):
  a v4 install (design 80's) with messages upgraded to v5, and a fresh one - `PRAGMA user_version` 5, the same
  `.schema message`, `message_reaction` present; with reaction rows present, `unread()` and `lastMessage()` ignore
  them (Home's badge, the list's dot and last line).

Screenshots: the chip, the panel and the picker at 288 dp.

## 6ab. A thread follows its newest message, the phone book on the night ground (2026-11-10)

Run on `handy36` (`-wipe-data`, `wm density 240`) on 2026-11-10, branch `sms-follow-book-ground` (b2f5ebad), with §6v's
setup (lock-task features 63, a light colour wallpaper picked in kid Settings) and main's debug build of the same day
as the control (`install -r` over the branch's and back). "At the end" below is measured, not judged by eye: in
`uiautomator dump` the last row's bottom is the list's bottom less its 8 dp padding.

- **The thread at the end follows:** one incoming SMS; five back to back (`adb emu sms send` in a loop), three rounds,
  one of two-line texts; eight sent at the same moment, four rounds; the kid's "Levert" line and Mamma's
  `'Likte «Kommer kl 5»'` making the last bubble taller; a reaction on an earlier bubble; the keyboard opening and
  closing; the field growing to five lines and emptied again; a picture from the fake MMSC (§6v step 3b), alone and
  between texts. The last message was whole at the end every time.
- **Scrolled up stays put:** one and then three incoming SMS, and a picture: the visible rows' bounds are the same
  before and after. Back at the end by hand, the next one is followed again. The kid's own send while scrolled up goes
  to the end.
- **An older page keeps the place:** 125 messages, the thread reopened (a page is 100), dragged slowly until the page's
  first row shows: the rows are where they were once the older page is in, and the next drag shows it above. A fling
  runs through the page's edge without a jump.
- **The control did not show the miss:** main's build followed every burst too, the eight at once included. The
  overtaken list update that drops the old scroll depends on timing this AVD doesn't produce; that the miss is gone is
  for the Jelly Star to show.
- **The phone book:** the list, the search with hits and "Fant ingenting" at font scale 1.0 and 1.3, and the contact
  sheet and the request form at 1.0, are `kid_ground` (`#0C0C14`) from the top edge to the bottom, like Kalender, with
  light status icons and a light navigation handle; Home on the light wallpaper keeps its dark ones. Main's build shows
  the rows on the wallpaper.
- **The buzz:** a reaction with the thread closed plays one 40 ms step (`dumpsys vibrator_manager`, "Recent
  vibrations"). The 40 ms reaches only a channel not yet created: an install that already ran a build with reactions
  (a debug build on the lab phone, an AVD without `-wipe-data`) keeps 80 ms until its data is cleared.
- **What `handy36` can't show:** its status bar is 136 px (the Jelly Star's and `jelly36`'s: 36), so with the keyboard
  up the thread's list is 104 px, and 7 px under a five-line field - shorter than one bubble. The same on the phone
  book: with the keyboard up the list is shorter than a row, and on b2f5ebad the search's first hit lay against its
  bottom with its top cut (main's build too; fixed in the round below). The keyboard-up views are `jelly36`'s or the
  phone's to judge. The fake MMSC's picture is 160 x 120: a portrait picture's tall bubble was not run.

**The code QA's fix round (ee9ef7a1), the same day**, on the AVD's kept data (no `-wipe-data`, `install -r`; no server
was up - the phone ran on its stored policy):

- **Unread until the end is reached:** scrolled up, one SMS in: the rows' bounds are the same, `message.is_read` is 0.
  Home without scrolling down: the Meldinger tile reads "Meldinger, 1 ulest" with its
  badge. The tile brings the same thread back, still scrolled up and still unread; flung to the end it is read and the
  badge is gone. Back to the list instead: the row reads "1 ulest", and the thread opened from it is at its end and
  read. Scrolled up, Home, a message in (its notification posted), the thread again: unread and the notification
  still there until the end is reached, then read and the notification gone. At the end with the thread in front an
  incoming message is followed and read at once, as before.
- **The kid's own send while the list coasts:** a fling coasts for about 1.9 s here (`dumpsys gfxinfo ... framestats`).
  A draft typed, the keyboard closed, `input swipe 240 300 240 690 50` and a tap on Send 10 ms, 0.4 s and 0.9 s after
  the swipe: the thread ended at its end with the sent bubble whole every time (four runs). The build before the fix
  was not run against the same taps, so that the miss existed here is not shown.
- **The phone book's first hit:** the keyboard up, "o" (5 hits), "ol", back to "o", and "o" typed from a list scrolled
  down: the first hit's top is at the list's top every time (the row's picture 10 dp under it, as in a list at rest),
  at font scale 1.0 and 1.3; the list itself is still shorter than a row on this AVD.

## 6ac. A thread's "Ny melding" mark (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`) on 2026-11-10, branch
`thread-new-mark` (d1fc6b65). No server was up - the phone ran on its stored policy: managed SMS, Mamma
(`+4790000001`, a thread of 250 messages at the start) and Pappa (`+4790000002`). Measured in `uiautomator dump`
(1.5 px per dp): the list's frame is `[24,226][456,719]`, the compose bar `[24,725][456,803]`, and "at the end" is the
last row's bottom at 702. A slow `input swipe` at x=400 scrolls without a fling; the store is read with `run-as ...
cat databases/messages.db` (`message.is_read`).

- **The mark comes and the rows stay:** scrolled up, one SMS in: `thread_new`, a Button "Ny melding",
  `[161,647][318,719]` - 104.7 x 48 dp, its centre at x 239.5 of the frame's 240, its bottom the list's bottom, 6 px
  over the compose bar; `is_read` 0. A second: "Nye meldinger", `[146,647][333,719]`. Every visible row's bounds are
  the same before and after both. In en (`cmd locale set-app-locales me.vibb.launcher.debug --locales en`, for one
  thread visit) "New message" `[149,647][331,719]` and "New messages" `[144,647][336,719]`.
- **The tap, and the end by hand:** a tap on the mark: the last bubble's bottom at 702, the mark gone, unread 0. With
  Home between (the tile "Meldinger, 2 uleste", the notification posted): the thread comes back scrolled up with
  "Nye meldinger" and its rows where they were; the tap reads it, the notification is gone and the tile reads
  "Meldinger". Recents and back (`KEYCODE_APP_SWITCH` twice) keeps the mark and the rows too. A slow drag to the end
  does what the tap does. A tall last bubble is whole after the tap: a five-line text `[24,454][369,702]`, and a
  picture from the fake MMSC with its text `[24,347][369,702]` (237 dp) - on the kept data the push's location needs
  `?to=%2B4790000020`, the phone's own number there; the default `To` is blocked as `group`.
- **The keyboard, the font scale:** with the keyboard up the frame is `[24,226][456,330]` and the mark
  `[161,258][318,330]`, over the compose bar at 336; a tap goes to the end, the keyboard stays. Opening and closing
  the keyboard keeps the mark and the rows. At font scale 1.3 "Ny melding" is `[142,639][338,719]` (130.7 x 53.3 dp)
  and "Nye meldinger" `[121,639][358,719]`, centred, the rows unmoved.
- **No mark:** the kid's own send with the mark showing (the thread goes to the end and is read); the delivery report
  of that send arriving after the kid scrolled up again; Mamma's `'Likte «…»'` for a bubble on screen and for one above
  it - a reaction is no row of its own (a `message` row with `reaction_to`, never counted unread), the bubble's chip
  and "Reaksjoner: 👍 fra Mamma" are all that change; an SMS in Pappa's thread while Mamma's shows "Ny melding" (it
  stays "Ny melding", Pappa's gets its notification); a message followed at the end and the kid scrolling up 3 s, 1 s,
  0.5 s, 0.2 s and at once after it (five of five: no mark, read).
- **A fling to the end and straight back up:** with the drag back about 200 ms after the fling began (one `adb
  shell`, no pause) the end wasn't reached in 8 of 10 - the mark stayed and the message stayed unread - and was in 2:
  read, no mark. With 0.05-0.5 s between them: 14 of 14 read, no mark. The mark never showed over a read thread in the
  24 tries, and never stayed away over an unread one.
- **What is open for a bubble:** "Slette meldingen?" up (`messages_confirm [24,593][456,806]`, the frame ending at
  581), an SMS in: the mark is `[161,509][318,581]`, over the confirm; the tap closes the confirm, the compose bar is
  back, the thread at its end and read, and the bubble is still in the store. The same with the reaction panel
  (`messages_react` from 515, the mark `[161,431][318,503]`): the panel closes, nothing is sent. The emoji picker
  takes the list and the mark away and Back brings both back, the rows where they were, still unread; the picture
  choice in the compose bar's place leaves the mark over it (`[161,456][318,528]`).
- **A delete while the mark shows:** in the long thread a deleted bubble is filled from above - the rows under it keep
  their place - so the end doesn't come into view and the mark and the unread message stay. In a short one (Pappa's:
  three in, two of the kid's own, scrolled to its top, one more in) the first delete leaves the new message under the
  edge and the mark up; the second makes the thread fit, the new message's bottom is at 702, the mark is gone and the
  thread read.
- **Another thread opened meanwhile:** every way in from outside the list - the notification, `smsto:` - starts the
  list under the thread (`CLEAR_TOP`), so the thread is a new activity (another `ActivityRecord` in `dumpsys activity
  activities`), not `onNewIntent`. Pappa's notification tapped 0.7 s after a fling's lift in Mamma's thread (a fling
  coasts 1.9 s here), `am start -a android.intent.action.SENDTO -d smsto:...` 20 ms after a fling, and the same 1.2 s
  into a 4 s `input swipe` still held down: the other thread is at its end, without a mark and read, and its next SMS
  is followed. `onNewIntent` itself was reached only by two taps on two rows of the Meldinger list 20-30 ms apart
  (`input tap 240 400 & sleep 0.03; input tap 240 283`; two `START ... ThreadActivity` lines 70-85 ms apart, 5 of 26
  tries): the second row's thread shows, at its end, no mark, the next SMS followed.
- **TalkBack** (`settings put secure enabled_accessibility_services
  com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`, `accessibility_enabled 1`;
  its first start in the kiosk puts "App is not available" over the screen - its tutorial is refused - and Back takes
  that away). No `uiautomator dump` while it runs - that connection suspends the other accessibility services; its
  focus is read as the green frame in a screenshot and its speech as the TTS engine's `Synthesis request` lines in
  logcat (this user build logs no text). A message followed at the end: no request. The mark coming: one request in
  nb and one in en-US 2.2-2.6 s later; "Ny melding" turning into "Nye meldinger": the same pair. The frame stays where
  it was when the mark comes. Keyboard focus moved to the mark (the field tapped, `input
  keycombination SHIFT_LEFT TAB` twice): the frame is on the mark; Enter: the thread is at its end and the frame is on
  the header's back button, 0.4 s and 3 s after - not on the last bubble and not on the mark that left.
- **Animations off:** frames taken in the same `adb shell` as the tap (`input tap 240 683; screencap ...`), compared
  in the mark's rectangle with the settled screen. At `animator_duration_scale` 1 the first frame showed the mark
  half faded over a list already at its end in 2 of 3 runs; at 0 the first frame was the settled screen in 4 of 4.
  Coming, a `screencap` takes about a second here: no frame caught the mark mid-fade at either scale.
- **A press:** held down, the pill is lighter (`#47455B` for `#34324A`) and the pixels at the target's corners and
  beside the pill are the picture and the bubble under it; let go, it goes to the end.
- **Found, not fixed:** (1) a drag that begins on the mark scrolls nothing and taps nothing: with the mark showing,
  `input swipe 240 683 240 400 1500` leaves every row's bounds and the mark as they were, the same drag at x=400
  scrolls - 105-158 dp of the list's lower edge, where a thumb may well start. (2) Over a received bubble the pill is
  the bubble's own colour with a 1 dp outline and covers part of its text or picture until the tap. (3) A reaction for
  a bubble on screen while scrolled up moves the rows over that bubble up by the chip's 18 dp (the list keeps its
  lower rows; the follow round's behaviour, not the mark's). (4) The Meldinger list's header action reads "Ny
  melding" too (a new conversation). After the pass: (1) and the focus after the mark's tap (the header's back
  button, above) were fixed on the branch (cc0e143f; `docs/notes/launcher/calls.md`, the mark) and run again the same
  day on the same AVD and data - the six "Re-check" points below.
- **Re-check, a drag from the mark:** with the mark showing, `input swipe 240 683 240 400 1500` moves every row 279 px
  (twice), the same drag at x=400 277; the mark stays, the message stays unread, the end isn't in view - no jump at
  the lift, no tap. Three rounds of 183 px up and 117 px down: from the mark 174, 174, 174 and 106, 106, 105; at x=400
  174, 173 and 106, 105; at x=60, on a bubble, 174, 172 and 105, 105. A short fling (77 px in 150 ms, six each way)
  ends 105-113 px on from the mark and 108-113 beside it. The slop is the list's own: 14, 20 and 30 px in 600 ms move
  the rows 2, 8 and 18 px from the mark and at x=400 alike and tap nothing; 10 px from the mark is a tap.
- **Re-check, the tap:** `input tap` on the mark (far up and near the end), in its target's corner (164,650) and five
  sloppy ones (`input swipe` of 3-9 px in 30-600 ms, up, down and sideways): each time the last row's bottom at 702,
  the mark gone, unread 0.
- **Re-check, the mark leaving under the finger:** 140 px and the new row from the end, one swipe from the mark of
  433 px in 3 s: a frame at 2.4 s has the list at its end and no mark, the finger still down; after the lift the
  thread is read, and the next drags at x=400 move the list (191 px for 200 down, 140 for 150 up). The finger in steps
  (`input motionevent DOWN 240 683`, then `MOVE` 20 px at a time): 380 px up the mark is gone and the thread read; 200
  px down again in the same gesture the rows follow (145 px - the end's stretch is let go first) and 60 px up again
  they move 60; the lift moves nothing, the next drag moves the list, a new SMS brings the mark and its tap goes to
  the end. A 50 ms fling from the mark about 1000 px from the end, three of three: the end is reached in the coast,
  read, no mark, and the next drag moves the list (191-193 px for 200).
- **Re-check, a tap on the mark while the list coasts:** `input tap 240 683` 20 ms after a fling's lift, in the same
  `adb shell`: after a fling up the list at x=400, after one towards the end from the mark (183 px in 50 ms from
  about 2000 px up - with no tap it stops short of the end, the mark still up, two of two) and after one up the list
  from the mark; two tries each, six of six at the end, read, no mark.
- **Re-check, the focus after the tap:** the field tapped, Back, `input keycombination SHIFT_LEFT TAB` twice: `focused`
  is on `thread_new` (the keyboard is up again, the mark `[161,258][318,330]`); Enter: `thread_field` is `focused` 0.4
  s and 3 s after, the last row's bottom at 313 (the frame ends at 330), read, no mark. With TalkBack on, the green
  frame is on the mark, and after Enter on the field `[132,342][348,408]`, 0.4 s and 3 s after - with both the
  keyboard's and TalkBack's focus on the mark the frame follows the keyboard's. A finger's tap: no view `focused`
  before or after, `mInputShown=false`; with the field focused, a keyboard that is down stays down and one that is up
  stays up, the focus on the field.
- **Re-check, long presses and the reaction panel:** the finger held 1.5 s on the mark over a wide bubble
  (`[24,583][369,719]`): no panel, the pill pressed (`#47455B`); lifted, the thread is at its end and read - a press,
  as before. A long press at (60,683), on the bubble beside the mark: the panel (`messages_react` from 515, the mark
  `[161,431][318,503]`). With the panel open a drag from the mark (100 px up) moves no row, closes nothing and taps
  nothing; the same drag at x=400 closes the panel; the mark's tap closes it and goes to the end, nothing sent (10
  sent rows before and after).
- **What `handy36` can't show:** TalkBack by touch - an injected `input tap` isn't touch exploration here (one tap on
  the mark with TalkBack on went straight to the end), so the double-tap, the words spoken and where accessibility
  focus lands without keyboard focus are the phone's to show (TalkBack's own keys - Alt+Left, Alt+Enter and the
  other keymaps' - did nothing injected with `input keycombination`); a second finger beside the one on the mark; the
  focus going to the last bubble where there is no compose bar; a thread switch through `onNewIntent` with the old list
  coasting or held (no way in keeps the activity; the two-tap way leaves it 0.1 s old); the touch sound (`-no-audio`);
  a real thumb's fling and return; the keyboard-up view (the list is 104 px, §6ab); the read-only line in the compose
  bar's place (needs a rule from a server); a portrait picture's bubble (the fake MMSC's is 160 x 120).
- Screenshots of the run are in the session's `scratchpad/mark-shots/`.

## 6ad. Meldinger as an app: its Apps row, the lock level and the buzz (design 82) (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`; the AVD's data copied first and put
back after) on 2026-11-10, branch `meldinger-app` (0952a091, with main's §6ac mark merged in). The branch's server ran
on the host (port 3111, the phone at `10.0.2.2`) on a copy of an earlier pass's database, at migration 0134 when it
started; the PWA was driven through headless Chromium (390 x 844, nb) with JavaScript and without it. The phone is in
the kiosk (Home roots lock task), managed SMS, Mamma (`+4790000001`) and Pappa (`+4790000002`) with their kept
threads. A save's place is read as the scroll of `main.container` and the form's cell's top, before the save and after
the page it lands on. A vibration is read in `dumpsys vibrator_manager`'s "Recent vibrations" and the launcher's
"Notice buzz" log line. Google Messages is allowed on this AVD, so in nb two rows are named "Meldinger" until the
built-in one is renamed.

- **Migrations 0135 and 0136 on an upgraded database:** the server took its own backup
  (`backups/pre-migrate-0134-0136.db`) and came up at 0136: `device_lock_glance_apps` got `me.vibb.builtin.messages`
  with `show_text` 0 (Name only) beside Element X's row, `lock_buzz_on_notification` is 0. A second copy of the 0134
  database with `sms_lock_text = 1` set by hand first came up with `show_text` 1 (Full text).
- **An older Vibb One against the new server** (the launcher already on the AVD, before `install -r`): `#lock-buzz`
  shows the orange "Denne telefonen vibrerer ikke ennå - oppdater Vibb One" under the switch, the Apps row "Vises på
  telefonen når Vibb One er oppdatert", and the lock row at Av "Denne telefonen viser fortsatt navn og antall -
  oppdater Vibb One". The old launcher does what the lines say: at Av its lock still shows the name and the count, and
  at Hele teksten it shows the text (the derived `call_policy.sms_lock_text`). After `install -r` and the new
  launcher's first report (`messages_app_v1`) the three lines are gone.
- **The Apps row:** Device › Apps lists "Meldinger" with the sub "Meldinger · En del av Vibb One" under Allowed, and
  "Navn og ikon" takes the name "Beskjeder", the icon "e-post", the colour "Plomme" and "På Hjem"; the summary reads
  "Beskjeder, med ikonet «e-post» på plomme". Saved with JavaScript: the name (moved 0 px), the icon and the colour,
  "På Hjem" on (the cell's top -396 before and after). Without it: "På Hjem" off lands on the row's anchor, the cell's
  top at the top of the view. After a save "Navn og ikon" is closed again (the form every app's row has). A refused
  save (a name of 30 characters) comes back with the form open and "Høyst 20 tegn - hjemskjermen viser omtrent 10.":
  the row's top is at the top of the view, with JavaScript too - 396 px from where the Save button was, not the
  page's top (scroll 211).
- **S1, a reset that leaves the row unlisted:** staged, not reached by the phone - the phone's network off, SMS set to
  not managed, and `has_history` set to false by hand in the newest report of the pass's own database (the kept
  threads can't go without a wipe; for the second run the display row was put back by hand). Tilbakestill without
  JavaScript: `/devices/1/apps#allowed`, the Allowed group at the top of the view (scroll 123), the row gone. With
  it: the hash is `#allowed`, the scroll 607 before and after, the row gone.
- **The Lock Screen row and the buzz switch:** "Varsler på låseskjermen" lists the built-in app under the name from
  the Apps row ("Beskjeder") with Av / Bare navn / Hele teksten; the row is not there while SMS is not managed.
  `#lock-buzz` is "Vibrer ved varsler", "Én kort vibrering, ingen lyd, for appene på låsen nedenfor og tapte anrop".
  Every save of the level and of the switch stays where it was with JavaScript and lands on its anchor without it.
  (Calls & Contacts' SMS mode, saved to stage the above, moved 26 px once: the page got shorter above a scroll that
  was near its end; the save back stayed.)
- **Home:** the rename, the icon and the colour reach the tile without a restart of the launcher (the same pid all
  through the pass): the tile "Beskjeder" with the envelope on plum, content description "Beskjeder, 3 uleste", the
  PIN prompt "Skriv koden for å åpne Beskjeder", the list's header "Beskjeder". "På Hjem" off: the tile leaves Home,
  messages still arrive and the lock's row still opens the thread; on: the tile is back. Under a pause of all
  restrictions with "På Hjem" off the tile is on Home ("Åpen til ...", "Lås igjen") and it is gone again when the
  pause ends. With SMS off and the kept threads the tile stays, without a badge, and Meldinger is read-only:
  "Meldingene her kan bare leses." in the list and as the thread's line, no "Ny melding" action; the Apps row's sub
  adds "SMS er ikke styrt av Vibb".
- **The lock at each level:** an SMS from Mamma on the locked phone. Hele teksten: "Mamma", "Hei Emma, middag klokka
  f…", the time, the row's small badge the chosen icon on plum. Bare navn: "Mamma", "Ny melding" (three unread: "3
  nye meldinger"). Av: no row, and no buzz for the next message. A level saved in the PWA while the lock shows - Bare
  navn, Hele teksten, Av, Bare navn - is on the lock's rows when they are read 5 s later.
- **The buzz:** the switch on, the phone locked, a message that brings a row: one step of 40 ms, `usage:
  NOTIFICATION`, from `me.vibb.launcher.debug` (the dump's durations read 41-88 ms here). Pappa's row and then Mamma's
  3 s later: one buzz, for the first; a row 19 s after that: one. None with the switch off (the row comes); none
  unlocked; none with the ringer on Silent, one on Vibrate; none with Do Not Disturb on and none under a time rule's
  silence ("Stilletid" on the lock) - the row comes in both. Mamma ringing and an SMS from Pappa meanwhile: no buzz
  while it rings (the lock yields to the call screen), then Pappa's row, "Tapt anrop fra Mamma" and one buzz as the
  call ends. No vibration from the notification's channel beside the buzz in any dump (`launcher:messages` has
  `mVibrationEnabled=false`) - one per message.
- **What the buzz doesn't do:** a message in a thread whose row is already on the lock (the second unread, the row not
  swiped away) gave no buzz - only a gained row does (`smsGainsRow`, the wake's rule too), so the rows were swiped
  away between the steps above. (Changed after this pass, 2026-11-10: every new message the lock shows buzzes, the
  10 s gap kept - design 82 §14, "The buzz for every new message"; seen in §6af.) Under Do Not Disturb and a rule's silence an SMS never gets as far as `noticeCame()`
  (`smsPostAlerts`), so the buzz's own filter and silence gates were not seen working apart from that.
- **Recents and TalkBack, by dump:** Meldinger's task has `taskDescription label="Beskjeder"` and the overview reads
  "Vibb One (debug) Beskjeder" (L4). Content descriptions: the tile "Beskjeder, 3 uleste" and "Beskjeder"; the lock's
  rows "Ny melding fra Mamma, 4:18" at Bare navn, "Ny melding fra Mamma, Hei Emma, middag klokka fem, 4:18" at Hele
  teksten, "3 nye meldinger fra Pappa, ...".
- **Main's mark in this build:** scrolled up in Mamma's thread, an SMS in: `thread_new` "Ny melding"
  `[161,647][318,719]`; the tap goes to the end and reads it.
- **Found, not design 82's** (reproduced on main, explained and fixed since: §6af)**:** the first pause of all restrictions (the Parent PIN in Settings; Home and
  Meldinger the locked tasks, a call screen and the lock's tasks earlier in the log) left the phone on Android's "App
  is not available" for Vibb One: the system's Home start was caught by the lock-task interceptor (`startActivity ...
  BlockedAppActivity`, then `Attempted Lock Task Mode violation ... HomeActivity` three times), with
  `mLockTaskPackages` already empty, `mLockTaskModeState=LOCKED` and the blocked screen's own task the only locked
  one; the Home key did nothing. `am task lock stop` didn't help, `am stack remove <its root task>` did. Two more
  pauses - one with the same two tasks, one after a ringing call and a lock row's unlock into the thread - went
  through: 1 of 3, on an emulator logging "Slow operation" at the time. The branch changes no lock-task or pause
  code; whether main alone does it wasn't tried. The log and two screenshots are in the session's
  `scratchpad/m82/logs/` and `m82/shots/`.
- **Not run, not proven:** L1 (a failed engine read dropping the lock's rows - no cheap way to make the read fail,
  and no unit test holds it: it stands on the code, `SmsHome.refresh`); the Recents label after a reset; TalkBack by
  touch and the lock row's action label (§6ac's note); whether the notification and the buzz are felt as one or two
  on the phone, Android playing the buzz from the background there, and Android's own vibration settings - the Jelly
  Star's; the buzz for another app's row (Element X's).
- Screenshots of the run are in the session's `scratchpad/meldinger-shots/`.

## 6ae. A message's time under the last bubble of its run (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`) on 2026-11-10, branch
`bubble-time` (15f37355, without design 82; the re-check on the commit after it). No server was up - the phone ran on
its stored policy, as in §6ac: Mamma (`+4790000001`), Pappa (`+4790000002`), the list's frame `[24,226][456,719]`,
"at the end" the last row's bottom at 702 (1.5 px per dp). New messages by `adb emu sms send`; `settings put system
time_12_24 24` (it was unset). What the AVD doesn't do by itself:

- **A failed send** is airplane mode (`cmd connectivity airplane-mode enable`, the send, `disable`): the delivery is
  `FAILED` (`sms:2`). `adb emu gsm voice off` fails nothing - the message is delivered.
- **Pictures** are §6v step 3b's fake MMSC, the stand-in set by `MmsDebugReceiver` (in memory: a process death
  forgets it, and a picture sent without it fails as `mms:12`, a second way to a failed line). The kid's own:
  "+", the camera, the shutter and the tick at (240,764), send.
- **A reaction** is the contact's "Likte «text»" as an SMS: a 👍 chip on the bubble with that text, the kid's or the
  contact's.
- **A new day and an empty message** are an edit of the store, since every kept message is from one day and none is
  empty: the launcher's process killed as its own uid (`run-as me.vibb.launcher.debug sh -c 'kill -9 $(pidof ...);
  cat > databases/messages.db; rm -f databases/messages.db-wal databases/messages.db-shm'`, the edited copy on its
  stdin - `am force-stop` does not end the Device Owner's process here), four messages of one run moved to 23:58,
  23:59, 00:01 and 00:02 and one message's `body` set to `''`.

What it showed:

- **One time per run:** four received within a minute: one "14:27", under the last; three more: "16:53" under the
  third, the time of the last one. A message of the other side ends the run (its own line under it), and so does a gap
  over 5 minutes. Across midnight: "I går", two bubbles, "23:59"; "I dag", two bubbles, "00:02".
- **The measures (text size 1.0):** a one-line bubble is 61 px; inside a run the next bubble starts 10 px (6.7 dp)
  under it; the line's box starts 3 px (2 dp) under the run's last bubble and is 36 px (24 dp) high, its text at the
  top of the box, so the time stands close to its bubble; from a run's last bubble to the next run's first is 49 px
  (32.7 dp). A thread of short messages reads calmer than with a time in every bubble, and the runs stand apart as
  groups; the 24 dp under each run does not look loose at this size. Not changed: it is also the height of the failed
  line's tap.
- **The kid's own:** with the keyboard up "Sendt · 16:54" `[333,277][456,313]` becomes "Levert · 16:54"
  `[328,277][456,313]`: the same right edge and height, nothing else moves (the frames of a screen recording across
  the send too). "Sender …" is never on screen - the emulator's radio answers faster than a frame. A picture stops at
  "Sendt · 17:07" (MMS has no delivery report). The state is the newest sent message's alone: once a newer one
  exists, an older run's line is its time alone.
- **A failed send:** "Ikke sendt - trykk for å prøve igjen" in red `[161,666][456,702]`, no time; a run of four
  ending in it shows no time at all. With the radio back, a tap on the line sends it again: it is the thread's newest
  with "Levert · 17:00". A failed message with a delivered one after it keeps its red line, and the run's last has
  the state and the time.
- **A message joining a run while the thread follows:** the line leaves the bubble before, stands under the new last
  one, and the last row's bottom is at 702 before and after.
- **Reactions, pictures:** a chip `[321,629][367,663]` overlaps its bubble by 7 px and the line starts 3 px under the
  chip, on both sides ("Levert · 17:00" under the kid's, "17:00" under the contact's); the thread stays at 702 when
  the chip comes. A picture alone: the bubble `[112,235][456,663]`, the picture `[133,249][435,651]` - 14 px over and
  12 px under it, as a text bubble's text - and the line 3 px under the bubble. A received picture with a text as a
  run's last: `[24,336][369,663]`, "17:06" under it.
- **The text size:** at 1.3 the failed line is one line `[86,666][456,702]`. At 2.0 it is two lines
  `[24,602][456,702]`, set to the right, inside the row; "Levert · 17:09" `[214,651][456,702]` and, in 12 h,
  "Levert · 5:09 p.m." `[157,651][456,702]` are one line; a time alone is 51 px high.
- **The one move, measured:** the run's last bubble cut by the list's lower edge (`[24,664][137,719]`, its line under
  the edge), a message joining its run: every visible row comes down 39 px (26 dp: the line's 24 and its 2), once for
  each message, and the "Ny melding" mark comes. Scrolled further up, the last row off the screen: nothing moves.
- **The "Ny melding" mark (§6ac) with the new rows:** scrolled up, one SMS in: the mark `[161,647][318,719]`, the rows
  unmoved; its tap: the last line's bottom at 702, no mark. A drag of 120 px from the mark scrolls the thread 109 px,
  the mark stays.
- **TalkBack's tree** (`uiautomator dump --compressed`, which leaves out what accessibility ignores): a time alone is
  no node; a state's line is one node described "Levert" or "Sendt", the failed line by its whole text; every
  bubble's description has its time ("Pappa, 16:53, Hei Emma", "Du, 17:07, Bilde", the reactions after it).
- **An empty message** is a pill without content, `[24,558][66,584]` (28 x 17 dp), "16:53" under it as a run's last,
  described "Pappa, 16:53". Before, its time was the bubble's content.
- **A fault found here and fixed:** every line was `clickable="true"` in the tree, a time alone too -
  `setOnClickListener(null)` makes a view clickable, and it ran after `isClickable`. The listener is set first now.
  Re-check on the rebuilt APK: a time alone (both sides) and "Levert · 17:31" are `clickable="false"
  focusable="false"`, the failed line is `true`, and its tap still sends ("Hentes naa", "Levert · 17:31").
- **Seen beside it, the same on `main`, not changed:** a failed picture's line offers the tap to try again, but the
  retry sends the message's text alone (`MessageActions.retry`). A picture alone: nothing is sent (the row stays
  `FAILED`, no POST at the fake MMSC) and the line says "Ikke sendt" until the thread is read again. A picture with
  "Se": the tap sends "Se" as an SMS ("Levert · 17:36") and the failed picture message is gone from the store. And
  with a picture attached and the keyboard up the compose bar `[24,232][456,466]` is higher than the room over the
  keyboard (its top at 430): the list has no height, and the text field and the send button `[384,394][456,466]` are
  half under the keyboard - a tap on the button's visible half sends.
- **What `handy36` can't show:** "Sender …" on screen, and the line's width changing from it; a carrier's delivery
  report coming minutes later, and a real MMS; what TalkBack speaks (§6ac: an injected tap isn't touch exploration);
  the thumb's feel of the 26 dp move on the cut row; how the spacing reads on the phone's own panel.
- Screenshots of the run are in the session's `scratchpad/bubble-shots/`.

## 6af. The buzz for a second message, and the pause that hung on "App is not available" (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`; the AVD's data copied first and put
back after) on 2026-11-10: part A and part B's count on `main` (92530147, with design 82's buzz for every message and
§6ae), the fix on branch `pause-hang`. Main's server ran on the host (port 3111) on §6ad's database copy, the PWA
through headless Chromium. The phone is in the kiosk (Home roots lock task, the app block on), managed SMS, Mamma
(`+4790000001`) and Pappa (`+4790000002`); new messages by `adb emu sms send`. Lock task is read from the system's own
protolog - `wm logging enable-text WM_DEBUG_LOCKTASK`, again after every boot: `Allowlisting`, `Allowing features`,
`removeLockedTask`, `setLockTaskMode`, `startLockTaskMode` - and from `dumpsys activity activities`
(`mLockTaskModeState`, `mLockTaskModeTasks`, `mLockTaskPackages`). After `install -r` on a fresh boot SystemUI may
answer "isn't responding" in a loop (load 20); a reboot and a wait for the load to fall under 3 ends it.

### A. The buzz for every new message (design 82 §14)

Meldinger's level "Bare navn", "Buzz for a notification" on, the wake switch on (set in the PWA for the pass), the
phone locked and dark. The emulator has no motor: a buzz is the launcher's `I/PinLock: Notice buzz` line and the
vibrator service's record of it (`dumpsys vibrator_manager`, "Recent vibrations": `me.vibb.launcher.debug`, `usage:
NOTIFICATION`, `played: Step=40ms(amplitude=1.00)`); a wake is `Notice wake` and `mWakefulness`.

- **A gained row, as before:** the first SMS from Mamma: `Notice buzz` 18:07:21.892, `Notice wake (proximity=false)`
  18:07:21.906, Asleep to Awake, the row on the glance.
- **A second message in the same thread, more than 10 s later:** 27 s after the first: `Notice buzz` 18:07:48.974 and
  no `Notice wake`. Again with the screen off first (off at 18:08:06, the SMS at 18:08:14): `Notice buzz`
  18:08:14.479, `mWakefulness=Asleep` before it and 4 s after it - the buzz alone, nothing woken.
- **Two more within 10 s** (after each of the two above): no line, no vibration.
- **The level Off** (saved in the PWA, the policy on the phone at 18:08:33), an SMS at 18:08:43: no buzz, no wake.
- **The vibrator service's whole record for the pass:** four NOTIFICATION entries, all the launcher's, 40 ms each, at
  18:06:16.006 (a gained row from Pappa while the wake switch was still off), 18:07:21.892, 18:07:48.974 and
  18:08:14.479 - one for one with the log lines, none for the messages inside the gap or at Off.
- **Seen beside it:** a message 116 s after the launcher's process started got no buzz and no wake - the first two
  minutes of a process are silent for both (`NoticeBusy`), so wait them out after an `install -r`. After a notice
  wake the emulator's screen stayed on, so "not woken" is the second case above, with the screen put out first.
- **What `handy36` can't show:** the buzz in the hand, whether the notification and the buzz feel like one, Android's
  own vibration settings on the phone, another app's second message (Element X's).

### B. "Pause all restrictions" and "App is not available"

One start: from Home in the kiosk (`mLockTaskModeState=LOCKED`), the drawer, Settings behind the Parent PIN, "Sett alle
begrensninger på pause", the Parent PIN again, 7 s, then the logcat and `dumpsys activity activities` kept. Ended the
way a parent would, with Home's "Lås igjen".

- **On main: 1 stuck of 10** (the second; an eleventh went through). The stuck one sat on Android's "App is not
  available" for Vibb One, `mLockTaskModeState=LOCKED`, `mLockTaskPackages` empty, the only locked task
  BlockedAppActivity's own (`type=home A=1000:android`) - §6ad's picture, on a quiet emulator this time.
- **The cause, from the protolog.** `setLockTaskPackages(admin, [])` is one call on one system thread: `Allowlisting
  0:[]`, then Home's task is taken out of lock task (`removeLockedTask ... last task, reverting locktask mode`) and
  cleared, and the system starts Home again inside the same call - and only after that the features are reset
  (`Allowing features 0:0x10`). The end of lock-task mode is only posted to a handler. If Home's start is checked
  before that handler ran, lock task still reads LOCKED with the app block (bit 64 of the kiosk's `0x7f`) and an empty
  list: the start is swapped for `com.android.internal.app.BlockedAppActivity`, whose own start then locks the phone
  to its task. The stuck start: `Allowlisting 0:[]` 18:12:58.406, the revert .407, Home's start .408, the
  BlockedAppActivity intent .410, `Allowing features 0:0x10` .414, `setLockTaskMode: Locking to Task{... type=home
  A=1000:android}` .620. In the ten clean starts the revert and Home's start were 2 to 31 ms apart - a race between
  two system threads, not something the load decides alone. (§6ad's `Attempted Lock Task Mode violation` lines are
  the launcher's own Home starts refused afterwards; none was tried in this start.)
- **What was guessed and what it is:** the guess - the launcher's `reapply` empties the list while lock task is
  LOCKED, Home's restart is refused as a violation, and only Home's `reconcileKioskMode()` would stop lock task - is
  right about the list emptied under LOCKED and about the blocked screen ending up the only locked task. It is not a
  refused start of the launcher's and it does not wait for `reconcileKioskMode()`: it is the system's own Home
  restart inside `setLockTaskPackages`, swapped by the app block before the posted end ran. `AppEnforcer` can't end
  lock task first itself (`stopLockTask()` is an activity's, and Settings is on top).
- **Out of it without a reboot:** the screen stays until its OK is tapped. OK finished BlockedAppActivity, lock task
  went to NONE, the system started Home, the pause was running, and "Lås igjen" put the kiosk back (LOCKED). The Home
  key does nothing while it is up (§6ad). Seen once, on the emulator.
- **Unlocking the PIN lock with the kiosk off** has no such race: the lock's task finishes and ends lock task before
  the list is emptied.
- **The fix, part 1** (`LockTaskChrome.writeLockTask`, pure `unpinFeatures`): before the list is emptied the features
  are written without the app block, in a write of their own; a failed emptying writes them back. Twelve starts on
  that build: none stuck, `Allowing features 0:0x3f` before `Allowlisting 0:[]` in every one, no BlockedAppActivity
  line.
- **A second race, seen at the twelfth of those:** the Home the system restarted resumed while the emptying call was
  still running, read the stored kiosk switch - still on, it was written after the call - and asked for lock task
  with nothing allow-listed: `startLockTaskMode: Task{... type=home ...}`, `Mode default, asking user` - Android's
  screen-pinning request, in the middle of a pause (the emptying 18:45:37.341, Home's start .353, the request .399).
  The pass's next tap accepted it: `mLockTaskModeState=PINNED`, the list empty, the pause still running. Back and
  "Lås igjen" from there put the kiosk back (LOCKED, `0x7f`, the list). This path is main's too; it was 1 of 23
  starts in this pass.
- **The fix, part 2:** the stored switch goes off before the list is emptied too (and back on if the emptying
  fails). Home, still alive under Settings, hears the switch change and stops lock task itself when it gets there
  first - then nothing is cleared and Settings stays in front; when the emptying gets there first Home is restarted
  as before, and reads the switch off.
- **Twenty starts on the fixed build: none stuck, no pinning request.** `Allowing features 0:0x3f` before
  `Allowlisting 0:[]` in all twenty, no BlockedAppActivity, no `startLockTaskMode`, `mLockTaskModeState=NONE` after
  each. Twelve went Home's way (`stopLockTaskMode` first, Settings left on top, Back to Home), eight the emptying's
  (Home restarted in a new task). Every end: `Allowing features 0:0x7f` before the list, `startLockTaskMode ...
  Locking fully`, LOCKED on Home's task, the launcher in the list. Three more started and ended from the PWA ("Unlock
  for 2 h", "Lock again") with Home in front: the same, Home's way each time.
- **The count is not the proof:** at 1 in 10 before, twenty clean starts alone would happen by luck about one time in
  eight. The proof is the order in the protolog - without the block bit a start is never swapped, and with the switch
  off Home never asks for lock task.
- **Seen beside it:** after a pause's end the allow-list is written three times within about two seconds (15, 14,
  then 13 packages): `com.google.android.documentsui` and `android` are in the first writes and leave as the apps
  are suspended again and the helper intents resolve to one handler. The same before the fix.
- **What `handy36` can't show:** how often either race comes on the Jelly Star, whether its build resets the
  features after the packages the same way, and whether OK gets a real phone out of the blocked screen.

## 6ag. A failed picture's retry, and the compose bar with a picture over the keyboard (2026-11-10)

Run on **`jelly36`** (480 x 854, 240 dpi, a 36 px status bar, the gesture bar `[0,818][480,854]`) on 2026-11-10, the
debug launcher of branch `retry-picture` at its merge of main (main cc1396b4 with §6af, plus the retry and the compose
bar's layout). The AVD had never been started, so the pass made its phone from nothing and put the AVD's directory
back after: `-timezone Europe/Oslo`, the launcher made Device Owner as in §4, the server's address
(`http://10.0.2.2:3111`) and "Enroll with code" in the launcher's Settings, main's server on the host on a copy of
§6ad's database (managed SMS, Mamma `+4790000001`, Pappa `+4790000002`, the kid's code and the Parent PIN its own),
the PWA through headless Chromium. Pictures are §6v step 3b's fake MMSC and `MmsDebugReceiver`'s `mmsc_host` and
`stand_in`; the kid's own picture is "+", "Ta bilde", the shutter and the tick, both at (240,764). Gboard's top with
the keyboard up is y = 429 (`dumpsys window`, `type=ime frame=[0,429][480,854]`), so the room under the header is
`thread_body` `[24,126][456,429]`, 303 px; with it down `[24,126][456,818]`.

What the AVD doesn't do by itself, beyond §6ae's list:

- **`adb shell input text` types ASCII only** ("å" throws a `NullPointerException` in `sendText`) and raises the
  keyboard; a Delete key event with the keyboard down does not raise it, and a Back with it down leaves the thread
  (the draft and its picture are kept). `input keycombination 113 29` and a Delete empty the field. The dumped *text*
  of the field can lag while it is typed; the bounds don't - count the lines on a screenshot.
- **`adb install -r` restarts the launcher:** the PIN lock comes up (a swipe on the glance, then the kid's code) and
  the debug hook's MMS settings are gone.
- **A contact's switches from the PWA:** "Bilder i meldinger" is the contact form's `mms`, "Meldinger" its `texts`
  (the one-way `sms_out` sits in a closed "Avansert"); the phone had the change within 12 s of the save.
- **`uiautomator dump` to `/data/local/tmp`**, and the dump read before any tap: `/sdcard/ui.xml` can't be removed by
  the shell on these images, and an old dump answers for a new screen.

### A. "Prøv igjen" on a failed picture

A failed picture is one sent with no stand-in set (`FAILED`, `mms:12`); the retry is the tap on its line with the
stand-in on. Read from the fake MMSC's kept POSTs (the JPEG inside and its SHA-256 against the `part` row's) and the
launcher's `messages.db`.

- **A picture with "Se hva jeg fant":** the tap: one POST, with the JPEG (19 067 bytes, the part's hash) and the
  text, to Mamma. The failed row is gone and one new row is there, `SENT`, its client id `retry:<the failed id>`.
- **Two taps 80 ms apart** (two `input tap` from one shell; the gap between the injected events was not measured):
  one POST, one new row.
- **A picture alone:** one POST with the JPEG and no text; the new row's body is empty.
- **"Bilder i meldinger" switched off for Mamma between the failure and the tap** (the stand-in on, so a send would
  have gone): the "+" leaves the compose bar; the tap: no POST, the row still `FAILED` with its picture, the toast
  "Dette bildet kan ikke sendes". After that refused tap the line says "Ikke sendt" without the offer until the
  thread is read again (reopened: the offer is back - texts are still allowed; `retryOffered` asks the text gate
  alone, not changed here). Pictures on again, the tap: the POST with the JPEG and the text, the row `SENT`.
- **A failed text alone** (airplane mode, `sms:2`; airplane mode off): the tap sends it as before, "Levert · 20:56",
  nothing at the MMSC.
- Four POSTs in the whole pass, one for each retry that should send; none for the refused tap.

### B. The compose bar, measured

`uiautomator` bounds in px (1.5 px per dp), Mamma's thread, the text typed with `input text`. The send button is
`[384,342][456,414]` with the keyboard up and `[384,731][456,803]` with it down in every row; the bar's bottom is 414
and 803.

| Picture, lines | Keyboard up: field | preview | list | Keyboard down: field | preview | list |
|---|---|---|---|---|---|---|
| yes, 1 | `[132,342][348,408]` | 144 x 144 | 48 | `[132,731][348,797]` | 144 x 144 | 437 |
| yes, 2 | `[132,341][348,408]` | 144 x 144 | 47 | `[132,730][348,797]` | 144 x 144 | 436 |
| yes, 3 | `[132,309][348,408]` | 144 x 144 | 15 | `[132,698][348,797]` | 144 x 144 | 404 |
| yes, 4 | `[132,277][348,408]` | 144 x 127 | 0 | `[132,666][348,797]` | 144 x 144 | 372 |
| yes, 5 | `[132,245][348,408]` | 144 x 95 | 0 | `[132,634][348,797]` | 144 x 144 | 340 |
| no, 5 | `[132,245][348,408]` | - | 107 | `[132,634][348,797]` | - | 496 |

- **Holds in every row:** the field's bottom (408) and the send button's (414) are above the keyboard's top (429)
  with one to five lines; the preview is 96 x 96 dp with up to three lines and gives way with four and five (its
  picture cropped to the row, the cross whole), 1 px lower than computed; with the keyboard down it is 96 x 96 dp
  throughout.
- **Main's build, the same phone, a picture and five lines, keyboard up:** the bar `[24,132][456,463]`, the field
  down to 457 and the send button `[384,391][456,463]` - 34 px under the keyboard, the list without height.
- **The other panels in `thread_body`**, each opened on this build and on main's (`install -r` of the main checkout's
  debug APK, built before this branch's layout: no `thread_body` in its resources), the same thread at its end:

  | Panel | Bounds, both builds | Pixels under the status bar |
  |---|---|---|
  | The line ("Du kan ikke svare her", Mamma's texts off) | `thread_line` `[24,728][456,800]`, the list to 722 | the same from the line down |
  | The picture choice | `[24,540][456,806]`, the list to 528 | the same from the panel down (the list had one message more) |
  | The react panel | `[24,515][456,806]`, the list to 503 | identical |
  | The delete confirm | `[24,593][456,806]`, the list to 581 | identical |
  | The emoji picker | `[24,132][456,806]` | identical |

### C. The Settings gate's Parent PIN dialog at this height

`am start` of the launcher's `SettingsActivity`, the phone in Norwegian. The title "Skriv inn koden for å åpne
innstillingene" is two lines.

- **No code set** (the PIN's hash and salt emptied in the pass's database copy, a PWA save for the sync; put back
  after): the dialog `[24,67][456,787]`, the reason "Innstillingene er låst. Sett en opplåsingskode for denne
  telefonen på serveren for å åpne dem." in three lines `[45,605][435,691]`, whole, the keys and OK dimmed, "Avbryt"
  and OK `[222,709]…[438,781]` whole.
- **A wrong code** (six digits): the dialog `[24,94][456,759]`, "Feil kode. Prøv igjen." one line
  `[45,632][435,663]`, whole. Five digits: "Skriv inn minst 6 sifre.", one line in the same place.
- Not raised here: the longer lines (one try left, the lockout, an app with foreign access).

### What `jelly36` can't show

The Jelly Star's own keyboard and its height (the table is Gboard's 425 px on this image); a carrier's MMSC and a
real refusal; two taps of a finger rather than two injected ones; a display size or font scale other than the
default. `handy36`'s rows of the compose bar stay computed (`docs/notes/launcher/calls.md`). Screenshots of the run
are in the session's `scratchpad/retry-shots/`.

## 6ah. The kid's look: Mørk or Lys - step 1's pass (design 78)

**Steps, written with the build on 2026-11-10 and added to in the code QA's fix round the same day
(`docs/review/qa-78-step1-code.md`, L8). Run in part on 2026-11-11 on `jelly36`**: "The run of 2026-11-11" at the end
of this section has what passed, the one finding (the launcher's own language) and what was never reached - the host
killed the emulator for memory before B.9. B.13-B.16 and the control on main came on 2026-11-11 with the language
fix; "The second run of 2026-11-11" after it has them and the rest (B.7's install half, B.9-B.11, C-F), with what was
still not run. The steps themselves are no result. Step 1 is the mechanism and
Kalender (`docs/notes/launcher/ui-polish.md`, "The kid's look: Mørk or Lys"); the row in kid Settings exists in the
**debug** build only.

**Brought to the design's revision 3 on 2026-11-11** (§12: a night and a light manifest activity per page, nothing
asked of the system), for the pass of `kid-look-2`, with no emulator: the setup, the command block, A's create line,
B.3, B.4, B.7, B.10 and B.12 reworded, a note before B.13, B.16 closed, B.17-B.20 new. None of the reworded or new
steps had been seen running when they were written. The first two runs at the end of this section were `kid-look-1`'s,
under the old mechanism (the app's own night mode), and stay as they were written. **Run on 2026-11-11 on
`jelly36`**: "The run of 2026-11-11 on `kid-look-2`", the last of the three, has every open path in both looks
measured frame by frame (no frame of the other look), B.3-B.6, B.17-B.20, two findings under the kiosk after a process
kill, and what was not run.

Setup: **`jelly36`** (§3), the debug launcher of branch `kid-look-2` as Device Owner (§4) on a wiped AVD or a restored
clean one - a launcher that ran `kid-look-1` left a night mode in the system's record of the package, and only a wipe
or an uninstall takes it away (design §12.4) -, enrolled, with §6w's
calendars: Kalender on Home, events today in two calendars with colours (§6w step 9), one in progress, an all-day one,
one with a description and a place, the kid's own "Min kalender" (the "+"), and a reminder that can come up on the
glance (§6w step 6). The phone in Norwegian. For the before pictures: main's debug APK built from the commit the
branch started from, installed over it with `adb install -r` (§6ag: that restarts the launcher and the PIN lock comes
up).

```
adb shell cmd uimode night yes|no                                        # the SYSTEM's dark theme, not the look
adb shell run-as me.vibb.launcher.debug cat shared_prefs/kid_look.xml    # choice and applied
adb logcat -b events -c                                                  # before each open or change
adb logcat -b events -d | grep -E "wm_on_create_called|wm_relaunch_activity|wm_destroy_activity" | grep -E "\.day\.|kidsettings"
adb shell dumpsys activity recents | grep -c "\.day"                     # Kalender's task: 1, or 0 after a change
adb shell settings put system font_scale 1.3                             # and 1.0 after
adb shell dumpsys activity activities | grep -E "mLockTaskModeState|mLockTaskModeTasks"   # the kiosk and its tasks
adb logcat -d -s Application | grep -E "The launcher's language took|Unlocked setup"   # an unlocked start's cost
adb logcat -d -s PageLook                                                # E: a page of the other look's class, a refused change
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton   # .gestural back
```

The look is picked in kid Settings (Home's "Innstillinger" tile): the section "Utseende" after the background
section, Mørk | Lys.

### A. The starting window, on the real code (step 0's recording again)

Record with the device's own `screenrecord` and step every frame, as §6k's frame-stepping rule. Eight recordings: Mørk
and Lys, each from Home's Kalender tile and from the glance's reminder row (the tap, the kid's code), each **cold**
(no Kalender task: right after a change of look, or the card swiped away in Recents) and **warm** (the task behind:
Kalender, Home, Kalender). Then the four tile recordings once more with the system's dark theme the other way
(`cmd uimode night no`).

- [ ] No frame of the other look between the last frame of Home (or the keypad) and the page's own first frame: the
  starting window has the look's ground.
- [ ] In Lys the status bar's icons and the gesture pill are dark from the first frame with the light ground. **Known
  from step 0**: on the cold open from Home's tile the icons stayed white for about 230 ms and then tinted over about
  95 ms; the user accepted it for now. Write down the frame count again for each of the eight; a longer or a new case
  is a finding, the known one is not a failure of the run.
- [ ] The events log for each open: `wm_on_create_called` once for the look's class (`DayActivity` in Mørk,
  `LightDayActivity` in Lys), no `wm_relaunch_activity`.
- [ ] With Kalender open in each look: `cmd uimode night no`, then `yes`, then Battery Saver on and off
  (`adb shell cmd battery unplug`, `adb shell settings put global low_power 1`, `0`, `adb shell cmd battery reset`).
  The page keeps its look and the events log shows no relaunch and no new `wm_on_create_called`.

### B. A change of look

1. Open Kalender, go Home, open kid Settings. The control shows the applied look. Screenshot.
2. Tap **Lys**. The preview strip turns to the paper ground with the lilac received bubble and the peach own one;
   nothing else on kid Settings changes (compare with step 1's screenshot: only the control and the strip differ; no
   flash, no relaunch of kid Settings in the events log).
3. `kid_look.xml`: `choice` and `applied` both `light`. `dumpsys activity recents | grep -c "\.day"` is 0, and the
   events log has `wm_destroy_activity` for the old look's class (`DayActivity` here, `LightDayActivity` on the way
   back). Recents shows no Kalender card. **With the kiosk on** (an
   allowlist and kiosk in the policy; do steps 1-3 once so): `mLockTaskModeState=LOCKED` before the tap and after it,
   Home's task still in `mLockTaskModeTasks` and Kalender's no longer; kid Settings stays in front and nothing of the
   kiosk shows or drops (no "App is not available", Back and Home as before).
4. Home, the Kalender tile: Kalender in Lys from its first frame (A), one `wm_on_create_called`, for
   `LightDayActivity`.
5. The same back to **Mørk**. Then with an event page open in Kalender's task before going Home: both activities are
   destroyed at the tap, and the next open is the day view.
6. Tap the look that is already applied: nothing is destroyed, Kalender's card stays in Recents.
7. Tap Lys, then `adb reboot` within a few seconds. After the unlock: `kid_look.xml` says `light`, Kalender opens in
   Lys from its first frame. The same after `adb install -r` of the same APK (the process restarted). This shows that
   the look survives a reboot and a process start, no more. Since revision 3 there is no value of the system's beside
   the stored one; a process that dies between the tasks' removal and the write leaves the old look and no Kalender
   task, which a reboot by hand cannot be timed to hit (`PageLookTest` holds the order).
8. A phone that never ran this build (the update): install main's debug APK on a wiped `jelly36` with the system's
   dark theme **off**, provision, then `adb install -r` this build. `kid_look.xml` has `applied` = `dark` without any
   tap; Kalender opens with a night starting window (no light frame), although the system is light.

9. **Kalender's task alive only in Recents.** Open Kalender, go Home, and kill the launcher's process as its own uid
   (`adb shell run-as me.vibb.launcher.debug sh -c 'kill -9 $(pidof me.vibb.launcher.debug)'`); the launcher starts
   again and the PIN lock comes up. Unlock: the recents count is still 1, although the new process has no
   `DayActivity`. Kid Settings, tap the other look: the count is 0, and the next open from Home is a clean one in the
   new look (A), one `wm_on_create_called`.
10. **A start names the stored look's class.** With Mørk applied and no Kalender task (right after a change),
   write `kid_look.xml` back with both values `light` and kill the process (`run-as me.vibb.launcher.debug sh -c 'cat >
   shared_prefs/kid_look.xml; kill -9 $(pidof me.vibb.launcher.debug)'`, the edited copy on its stdin). After the
   unlock Kalender's cold open from Home is Lys from the starting window on, although no change ran: the unlocked
   start read the stored look and the tile's start names `LightDayActivity` (one `wm_on_create_called` for it, no
   `E PageLook`). Tap Mørk after.
11. **A change that waits, written by hand.** With Mørk applied and Kalender's task behind, write the file with
   `choice` = `light` and `applied` = `dark` and kill the process the same way. After the unlock, Home's resume runs
   the change once: both values `light`, the recents count 0, and Kalender opens in Lys. Go to kid Settings and Home
   again: the events log shows nothing destroyed and nothing closed (the resume has nothing left to run).
12. **The unlocked start's cost.** After each process start above (the `install -r`, the reboot, the kills), read the
   `Application` lines: `The launcher's language took N ms` (the default prefs file, at most one call to Telecom and
   one binder call, on every unlock-to-Home path; the language fix's code QA, L2) beside `Unlocked setup took N ms`
   (design 59's head, which does not include it). Write the numbers down; one that isn't small beside the head's is a
   finding. `The kid's look took N ms` is gone with revision 3: the start only reads one small prefs file and makes no
   call (the spike measured that read: 122 ms at the first start, 3 and 6 ms after; design §12.7.2).

Steps 13-16 were written on 2026-11-11 with the language fix (design 78 §5.3, "The cause" and "Decision"), from
Android 16's source and with no emulator: **none of them has been seen running**. They need the phone's own language
to be English (`adb shell settings get system system_locales` reads `en-US`) and the launcher's `nb` from the policy
(the PWA, the phone's language choice). (They were run since: "The second run of 2026-11-11".) **Since revision 3**
the launcher sets no night mode, so on a wiped AVD the cause steps 13 and 14 were written for is gone - the spike read
`nb` after both of its boots with the unlocked start's ask switched off (design §12.7, §12.7.2) - and what step 13 says of every second boot no longer applies. The
language code stays as built (design §12.9 point 2), so steps 13-15 stay: 13 and 14 are expected to pass with no
English frame, and 15's refusals have nothing to do with a night mode.

```
adb shell cmd locale get-app-locales me.vibb.launcher.debug --user 0     # the launcher's languages in the system
adb logcat -d -s LauncherLocales PageLook                                # "Launcher language: nb", or a refused call
adb logcat -b events -d | grep -E "wm_on_create_called|wm_relaunch_activity" | grep -E "HomeActivity|PinLock"
adb shell aflags list | grep expedite_activity_launch_on_cold_start      # if the image has aflags: write its state down
```

13. **The language over three reboots.** `get-app-locales` reads `[nb]` and Home is Norwegian. Kid Settings, **Lys**.
   Open Kalender, go Home (its task stays behind). `adb reboot`. After the unlock: the keypad if one shows, Home, kid
   Settings, the phone book, Meldinger and Kalender ("onsdag", "Pågår nå", never "Wednesday" or "Happening now") are
   all Norwegian, `get-app-locales` reads `[nb]`, the log has `I LauncherLocales: Launcher language: nb` once for the
   process start and no `Couldn't set the launcher language`, and Kalender's cold open is Lys from its first frame
   (step 7). **Reboot twice more** with Kalender left open each time, the same checks after each: by the source every
   second boot starts from a stored record without the languages and the one after from a record with them, so three
   boots cover both. For each boot write down Home's lines in the events log - one `wm_on_create_called`, and whether
   a `wm_relaunch_activity` follows it (the design's named cost: the first activity created once more when the
   language comes back) - and step a recording of the boot's first Home (`screenrecord` started as soon as `adb` answers)
   for a frame in English: **an English frame the kid can see is a finding**, a relaunch with none is the cost as
   written. The stored record itself (`/data/system_ce/0/package_configs/`) can be read only on an image with root.
   Before the fix (4845b74d, the run of 2026-11-11) the first of these reboots left every screen in English.
14. **A process that was alive before the unlock.** With a PIN on Android's own lock screen (design 59) and the look
   Lys: `adb reboot`, wait at the lock screen until `adb shell pidof me.vibb.launcher.debug` answers, start a
   recording, unlock. The same checks as step 13, on two reboots in a row. Here Home can be mid-launch when the
   launcher's files open: a Home drawn in English first is the design's second named cost - write down whether it is
   there and for how many frames.
15. **A parent's change with the page tasks alive.** Open Kalender, go Home, open Meldinger, go Home (both cards in
   Recents). Change the phone's language to English in the PWA and wait for the sync. Nothing changes while Home just
   stays in front; leave Home and come back (the phone book and Back). Two outcomes are the design's, and which one
   comes depends on what the process's last such call ran under (write it down with the flag's state from the block
   above): **set at once** - `Launcher language: en`, `[en]`, Home created again in English, no `Couldn't set the
   launcher language`, both cards still in Recents and each page in English when opened; or **refused, then set** -
   one `W LauncherLocales: Couldn't set the launcher language` and right after it, in the same resume, `Launcher
   language: en`, `dumpsys activity recents | grep -c "\.day\|MessagesActivity"` is 0 and both cards are gone. A
   warning with no `Launcher language: en` after it is the design's "what is still refused": write down which
   activities were alive (`dumpsys activity activities | grep -E "Hist|mActivityType"`) and at which later Home resume
   it got through. Back to `nb` with **Kalender in front** when the sync lands: nothing happens on Kalender. **Home
   straight from the page** (the Home gesture with Kalender in front; once more from a Meldinger thread) is the
   code QA's M1 and has its own outcome: set at once, or **refused twice, then set late** - two warnings in that
   resume (the page is closed but not yet destroyed), both cards gone, and about two seconds later, with Home still in
   front, `Launcher language: nb` from the one late ask (`LANGUAGE_RETRY_DELAY_MS`). Write down the time between
   Home's `wm_on_resume_called` and the page's `wm_destroy_activity` in the events log: the delay has to be longer.
   A late ask that is refused too, or two warnings with no third ask while Home stayed in front, is a finding. Then
   open the page again and go straight Home twice more: once the language is set, nothing is asked and no card is
   closed. **A standard-type activity that stays** (the code QA's M2): open the phone book through a `tel:` link in
   another app (`adb shell am start -a android.intent.action.DIAL -d tel:123` from that app's task, or a link in the
   browser), go Home, change the language, and resume Home several times with Kalender opened again each time:
   Kalender's card is closed at the first refused resume only (when its late ask ran), and never again for that
   language; at each refusal write down `dumpsys activity processes` for the launcher. Then once with the PIN lock up
   when the sync lands (at the unlock's Home: set at once, or refused and set by the late ask or at the next Home
   resume), and once during a call (nothing until Home after the call, and nothing
   relaunched during it). **The rest the design names**: Kalender in front, kill the launcher's process as its own uid
   (step 9's line), unlock, go Home, change the language in the PWA, leave Home and come back. If it is refused here
   (twice in one resume, Kalender's card gone for nothing) it should stay refused at every plain Home resume and get
   through at the Home resume right after the PIN lock (screen off, on, the code) or after `adb install -r`: write down
   each. Refused still after an unlock with the PIN lock is a finding; so is any screen in a mix of the two languages.
   A text half typed in a thread is gone when a refusal closed Meldinger's task, until step 2 keeps it: known.
16. **Closed by revision 3** (2026-11-11). The step looked for `E PageLook: The app's night mode wasn't set` with
   `Can't change activity type once set`, seen twice in the second run below. The launcher no longer asks the system
   for a night mode (design §12.2 R1), so there is no call left to refuse and the code cannot print the line. What can
   refuse a change now is step 20's.

Steps 17-20 were written on 2026-11-11 from the code QA of `kid-look-2`, with no emulator: **none of them has been
seen running**. (They were run since: "The run of 2026-11-11 on `kid-look-2`".)

17. **A page of the other look's class, as its task's root** (design §12.2 R6's check). With Mørk applied and
   Kalender's card in Recents (open Kalender, go Home): write `kid_look.xml` with `choice` and `applied` both `light`
   and kill the process (step 10's line). Unlock, open Recents and **tap Kalender's card** - a tap on the card, not
   `am task focus`. Expected: one night starting window, `E PageLook: DayActivity was created in the light look: its
   task is closed`, nothing of the page drawn, no crash (`adb logcat -d -b crash` is empty), no card (the recents count
   is 0) and Home back in front; the next tap on Home's tile opens Kalender in Lys from its first frame (A), one
   `wm_on_create_called` for `LightDayActivity`. If the overview has no card to tap after the kill - the spike saw the
   task listed in `dumpsys activity recents` and no card for it (design §12.7.2) - try with the kiosk off, and only
   then `adb shell am task focus <id>`; write down which route was run: the card's own tap is what has never been
   seen. Tap Mørk after.
18. **The same, not as the root.** With Mørk applied: Kalender, an event's page open over the day view, Home; then
   step 17's file, kill, unlock and tap on the card. Expected: `E PageLook: EventActivity was created in the light
   look: its task is closed`, both records gone (`dumpsys activity recents | grep -c "\.day"` is 0), Home in front,
   and **no** `W PageLook: The other look's task didn't close as a task`. A day view that shows under the closing
   event page, in either look, is a finding.
19. **Under the kiosk.** Steps 17 and 18 once with the kiosk on, with step 3's checks: `mLockTaskModeState=LOCKED`
   before the tap and after it, Home's task still in `mLockTaskModeTasks` and Kalender's no longer, no "App is not
   available", Back and Home as before - both removals go through the lock-task controller.
20. **A refused change.** Read `adb logcat -d -s PageLook` after every part of the pass: any `E PageLook: The look
   stays dark: ...` or `The look stays light: ...` (`a page task is still there after its removal`, `the new look was
   not written`) is a finding - write down the step, `kid_look.xml` and the recents count as they were. So is
   `E Application: The kid's look wasn't read`.

**The control on main** (a bug older than step 1, by the source; main's debug APK on a wiped `jelly36`, the phone in
English, no look to choose): set the launcher's language to `nb` in the PWA - Home takes it (`Launcher language: nb`),
the first change of the process's life. Without a process start in between: open Kalender, go Home, change the
language to English in the PWA, leave Home and come back. Expected: `W LauncherLocales: Couldn't set the launcher
language` with `Can't change activity type once set` at every Home resume for as long as Kalender's card is in Recents,
the launcher staying Norwegian; with the card swiped away (kiosk off) or after a reboot it is set. If main sets it at
the first Home resume instead, the reading of the source is wrong about main: say so.
**Corrected by the second run of 2026-11-11** (seen on `jelly36`, main's APK installed over this build, not a wiped
AVD): main refuses, but not "at every Home resume for as long as the card is in Recents" - at every Home resume where
Kalender's activity is the one the process follows (Home straight from Kalender). An activity of Home's own task added
in between (the phone book and Back) lets the change through with the card still in Recents.

The waiting change by a real tap (a tap while a page task is started, run at Home's next resume) is **not reliably**
reached by hand: with kid Settings in front the page the kid left is stopped, so the tap would have to land before
that page has had its `onStop` - which comes once Home has resumed and gone idle, well before a finger is through
Home's tile and at the row. To try it (only where "Utseende" is on kid Settings' first screen): Kalender in front,
then Home, the Innstillinger tile and Lys as fast as one shell line sends them (`adb shell "input keyevent
KEYCODE_HOME; input tap <tile>; input tap <Lys>"`). A hit is `choice` = `light` with `applied` = `dark` in
`kid_look.xml` and Kalender's card still in Recents; the next Home runs the change. A miss is step 2's ordinary change
and no finding. `PageLookTest` holds the rule, and step 11 shows the stored half of it every time.

### C. Kalender in both looks

Each of these in Lys beside the mockup (`docs/design/78-mockups.html`) and in Mørk beside main's build of the same
screen (Mørk must be the same picture as main's; anything that differs is a finding):

- [ ] The day view: the header with its back disc and arrow, the date, an event row (start time in the accent, the
  title, the second line dim), the calendar dots of both calendars and the kid's own (each clear of the white tile and
  of the paper), "Pågår nå"'s ring, an all-day row, the key under the list, the "+" disc with its night glyph, an empty
  day's line, a reminder shown under the header. A row pressed: the ripple is visible and dark in Lys.
- [ ] An event's page: title, time, description and place, the calendar's dot and name, "Endre" and "Slett" (its own
  event), "Slett"'s confirm dialog - a white panel with dark text and accent buttons in Lys.
- [ ] "Ny avtale": the field labels and fields, the keyboard up, "Lagre". The **date picker** and the **time picker**
  in both looks at font 1.0 and 1.3: the panel, every day number, the weekday row, both month arrows, the header and
  the chosen day (night text on the peach fill), the clock face's numbers, the buttons. **Look at today's number while
  another day is chosen**: the framework draws it in the peach fill, which is 2.6:1 on Lys's white panel - say how it
  reads.
- [ ] The status bar's icons and the gesture pill on each of the three screens, and with the keyboard up.
- [ ] **3-button navigation** (the overlay command above; `adb shell settings get secure navigation_mode` reads 0;
  `.gestural` back after). In **Mørk**, the bottom of each of the three screens with main's build and with this build:
  the same picture, the system's scrim behind the three buttons included (`kid_page_nav_contrast`; the code QA's M1).
  In Lys: dark buttons on the paper with no scrim behind them, each one clear to see, with the keyboard up too.

### D. The screens that are not pages: before and after

Screenshots with main's build and with this build, each with the system's dark theme **on** and **off**, and on this
build with the look Mørk and again Lys: Home, the phone book, Meldinger's list and a thread, the contact card, the
emergency menu, kid Settings (its Wi-Fi, Bluetooth and sound lists too), the drawer, parent Settings behind its PIN
dialog, the time-rule screen, the PIN lock with its glance, an incoming and an ongoing call (§5), and the "link
blocked" notice (a `market://` link while the Play Store is suspended, `docs/notes/launcher/push-and-play.md`).

- [ ] System dark **on**: this build is main's picture on every screen, in both looks.
- [ ] System dark **off**: only what AppCompat draws by itself differs from main - a row's ripple while pressed, a
  control's tint, an unthemed switch or divider, parent Settings' preference widgets (design D2). Anything else - a
  ground, a text colour, an icon - is a finding. The "link blocked" notice: its ground, text and OK button as before.
- [ ] Lys against Mørk on this build: no difference on any of these screens; their starting windows are night in Lys
  too (record the phone book's and Meldinger's cold open from Home in Lys).
- [ ] None of them is relaunched by a change of look or by `cmd uimode night yes|no` (the events log), the call screen
  during a call included.

### E. TalkBack and the font scale

- [ ] TalkBack on kid Settings (the image's own TalkBack if it has one; otherwise read the nodes with
  `uiautomator dump`, to `/data/local/tmp` as §6ag): the heading "Utseende", then "Mørk" and "Lys" as two choices with
  their selected state, then the caption. The preview strip is not a stop and its two texts are not read.
- [ ] Font scale 1.3: the control's segments stay at least 48 dp high with their words whole, the strip keeps its
  56 dp with both bubbles inside it (the received one may end in an ellipsis), the caption wraps; Kalender's three
  screens and both pickers in Lys.

### F. To note, not to judge

- Android 16's "make more apps dark" (Accessibility; step 0's image had no such switch): if this image has it, what it
  does to a Lys page.
- A release build has no row. It is checked from CI's release APK on an emulator of its own, never from a local
  build on a phone: kid Settings shows no "Utseende", Kalender is night, and `kid_look.xml` has `applied` = `dark`
  after the first unlock.

### What `jelly36` can't show

The Jelly Star's own Android build (step 0's repeat there is still open - a debug build on a test phone, never the
kid's), a finger's tap instead of an injected one, an Android 14 phone's bars under a Lys page.

### The run of 2026-11-11 (`jelly36`), stopped before B.9

`kid-look-1` at 163aceb5 (the debug APK from one gated `assembleDebug` on the clean tree, 2026-11-10 23:54 UTC), on a
wiped `jelly36` (Android 16, 480x854, 36 px bar), headless, `-timezone Europe/Oslo`. Main's debug APK (bc547c84) was
installed and provisioned first, for the before pictures, and this build put over it with `adb install -r` (B.8). The
system's language en-US, the launcher's own `nb` from the policy, the kiosk on, gesture navigation. Every line is
**seen** (on `jelly36`: a picture or `dumpsys`), **measured** (a recording stepped frame by frame, or a log line) or
**inferred**, and says which; made-up names only.

**Stopped at 01:38 UTC**: the host's kernel killed the emulator for memory (`Out of memory: Killed process ...
(qemu-system-x86)`, 4.7 GB on a 12 GB host where another session's Kotlin daemon held 3.4 GB) during the pass's second
reboot. By the standing rule it was not started again. The AVD was put back from the copy taken before the pass (22
files, SHA-256 identical).

**Passed**

- **A, the events log** (measured; 12 recordings - system dark on: both looks, tile and glance, cold and warm; system
  dark off: both looks from the tile, cold and warm): one `wm_on_create_called` for `DayActivity` on every cold open,
  none on a warm one, no `wm_relaunch_activity` anywhere.
- **A, the other look's frame** (measured): Mørk never shows a light frame, with the system light too. Lys from the
  tile goes from Home's night frame through the system's cross-fade (66-240 ms) to the paper and never back; from the
  glance the page slides in from the right over the lock, already drawn in Lys.
- **A, the status bar in Lys** (measured: how long white ink stands on the paper, the tint's fade included). From the
  tile, cold: 255 ms (system light), 379 ms (system dark), 498 ms on the first open after a boot with the emulator
  loaded - step 0's case, somewhat longer here. From the tile, **warm: 201 ms and 165 ms** - new beside step 0's "cold
  only"; on a warm open the system's task-to-front animation also shows a thin night edge around the light page for
  about two frames. From the glance, cold and warm: dark ink from the first light frame.
- **The late tint's cause** (measured): the page has a starting window (`Splash Screen me.vibb.launcher.debug`,
  `ty=APPLICATION_STARTING`) although `launcherBaseTheme`'s `windowDisablePreview` is inherited, and it has the look's
  ground. With the launcher's process held (SIGSTOP) right after the tap, that window alone goes from night to paper in
  about 130 ms, and the bar's ink still turns dark only 210-310 ms after the first light frame. So it is neither the
  starting window's lifetime nor the inherited `windowDisablePreview`: the status bar follows the app transition late,
  whoever owns the window. No theme item changes that; nothing was changed.
- **A, the flips** (seen; Kalender open in Mørk and again in Lys): `cmd uimode night no`, `yes`, Battery Saver on and
  off, and Battery Saver with the system light - no lifecycle event, the same `ActivityRecord`, the pictures identical
  below the status bar (in Lys the last one differs in the reminder's minute only). On this image Battery Saver is on
  in `dumpsys power` and does not move `mComputedNightMode`, so that half says less than its name.
- **Home on a switch of the system's dark theme** (seen): no create and no relaunch on this build; main's Home was
  created again by the same `cmd uimode night yes`.
- **B.1-B.3, the kiosk on** (seen): at the tap on Lys only the control and the strip differ from the picture before
  (x 45..434, y 510..685) and kid Settings has no event; `DayActivity` is finished and destroyed, `kid_look.xml` is
  `light`/`light`, the recents count 0, `mLockTaskModeState=LOCKED` before and after with Home's task alone in
  `mLockTaskModeTasks`, nothing of the kiosk shown or dropped. **B.4**: A's cold Lys recordings are that open.
- **B.5** (seen): with an event page open in Kalender's task, the tap on Mørk finishes and destroys `DayActivity` and
  `EventActivity`, `dark`/`dark`, count 0, still `LOCKED`; the next open is the day view, one `wm_on_create_called`.
  **B.6**: a tap on the applied look - no event, the count stays 1.
- **B.7, the reboot** (seen, measured): after Lys and `adb reboot`, `light`/`light`, and Kalender's first open is Lys
  from its first light frame with one `wm_on_create_called`. (During that boot Android's own runtime restarted once:
  `system_server` died on a `DeadSystemRuntimeException` in `TelephonyManager` after `com.android.phone` stopped
  answering under the boot's load - not the launcher's - so the launcher's process started twice.)
- **B.8** (seen, measured): main's build with the system's dark theme off, this build over it: `applied` = `dark` with
  no tap, and Kalender's first open is night in every frame, one `wm_on_create_called`.
- **B.12** (measured), `The kid's look took` beside `Unlocked setup took`: 14 ms / 372 ms (B.8's install), 58 ms /
  578 ms (an `install -r`), 332 ms / 9300 ms and 405 ms / 14231 ms (the two starts of the loaded boot). Small beside
  the head's every time.

**Finding: the launcher's own language is gone after a reboot, and its repair is refused while Kalender's task lives**

- **Seen**: after B.7's reboot `cmd locale get-app-locales me.vibb.launcher.debug --user 0` read `[]` (`nb` before),
  and Home, the PIN lock ("Wrong code", "Emergency call", "Parent code"), kid Settings and Kalender ("Wednesday",
  "Happening now") were in English, the system's language.
- **Seen**: `LauncherLocales.applyIfSafe` (Home's resume) asked for `nb` again and the system refused it, five times
  in two launcher processes: `W LauncherLocales: Couldn't set the launcher language`, `IllegalStateException: Can't
  change activity type once set: {... mActivityType=home ...} activityType=standard`, thrown in the system server
  (`WindowConfiguration.setActivityType` under `ConfigurationContainer.applyAppSpecificConfig` and
  `PackageConfigurationUpdaterImpl.updateConfig`, from `ILocaleManager.setApplicationLocales`). The last time Home was
  in front and Kalender's task, opened after Home, was in Recents; the four before were around the PIN lock and
  Kalender's open after the boot.
- **Seen**: a change of look from kid Settings (which removes Kalender's task) went through with no `PageLook` error,
  and the Home resume after it set the language: `I LauncherLocales: Launcher language: nb`, `[nb]`, Home created again
  in Norwegian. So the phone stayed in the system's language from the reboot until the first Home resume with no task
  of the launcher's own above Home's; with the kiosk on the kid has no way to close Kalender's task.
- **Inferred, not seen**: Android keeps an app's night mode and its languages in one per-package record
  (`PackageConfigPersister`), and by AOSP's source as remembered a record that has a night mode is written to disk
  without its languages - then every phone on this build loses the launcher's language at every reboot. The refusal
  looks like the system's own scratch configuration for the process keeping the activity type of the last such call
  (Home's, from the unlocked start's `setApplicationNightMode`) and refusing the next call when the process's newest
  activity is of another type. Main asks for the language once per change and never sets a night mode, so it meets
  neither. The control for the first half - another app's language set from the shell, a reboot, both read right
  after it - was the reboot the emulator died in. Whether `setApplicationNightMode` can be refused the same way (a
  process whose first activity is the PIN lock or Kalender, then a change from kid Settings) was B.9's to show.
- **Read in source, 2026-11-11** (AOSP `frameworks/base`, tag `android-16.0.0_r1`; the design's §5.3, "The cause",
  has the files and methods; no emulator): the first half of the line above holds and is no race - with a night mode
  set, the record on disk loses the languages at the next boot's first call. The refusal is as guessed, with one
  correction: the scratch configuration keeps the type of the last call that **got through**, whichever it was, and
  what it is compared with is the activity the process follows - the one added to it last. "Main meets neither" is
  wrong in its second half: main never loses the language at a reboot, but it has the refusal too, narrowly - a second
  change of language in one process life while the followed activity's type is not the first change's (the control on
  main, above).
- A phone whose system language is the launcher's shows none of it. Not fixed in the pass; fixed on 2026-11-11 in the
  mechanism (the design's §5.3, "Decision") and **not seen running**: B.13-B.15 are its steps.

**Not run** (the emulator was gone)

- B.7's `install -r` half: it ran once from a wrong state (the pass's helpers find Home's tiles by their Norwegian
  names, which the finding had turned English), so only its cost line counts. B.9, B.10, B.11, the waiting change by
  a real tap.
- C on this build: both looks of the three screens, the pickers at 1.0 and 1.3 with today's unselected number, the
  dialogs, the "+" disc, the bars with the keyboard up, and 3-button navigation (the code QA's M1 - not seen). Main's
  before pictures were taken; on main (seen) the pickers' "AVBRYT"/"OK" are almost invisible in both system modes,
  "Varighet"'s choices almost invisible with the system light, the 3-button bar has dark grey buttons with the system
  light and white ones with it dark and no scrim band to see, the day view's key line runs under the "+" disc, and the
  neighbouring days' rows show a few pixels at both edges.
- D on this build (main's pictures were taken; the link-blocked notice had to be opened by its component, since
  `jelly36` refuses to suspend the Play Store), the pinned screens on a change of look, the unlock with the PIN lock
  showing on a system-light phone; E; F.
- B.13-B.16 and the control on main (written after the run, with the language fix of 2026-11-11): never run.

### The second run of 2026-11-11 (`jelly36`): B.13-B.16, the control, B.7-B.11, C-F

`kid-look-1` at 9ca55e8e (the debug APK from one gated `assembleDebug`, the emulator down), put with `adb install -r`
over 163aceb5 on the first run's AVD as that run left it (a copy; `jelly36` itself was put back from the copy taken
before the first run and verified, 22 files, SHA-256 identical). Main's debug APK (809bbcd0) for the control. The test
server was the binary main's tree had already built (0.73.0, no cargo). System language en-US, the launcher's `nb`
from the policy, the kiosk on, gesture navigation unless said, the phone's own PIN lock unless said, `-timezone
Europe/Oslo` (the times below are the phone's). **A "reboot" here is `adb shell reboot -p`, the memory gate read with
the emulator down (8.1-8.2 GB each time) and a cold start** (`-no-snapshot-load`): seven of them (r1-r4, a1, a2, t1),
the emulator never killed. `cmd locale set-app-locales` was **not run at all** in this pass
(`com.google.android.deskclock` still had `[nb]` from the first run); `get-app-locales` only reads. Every boot on this
host has ANRs in SystemUI, the phone app and others and a "System UI isn't responding" dialog over the lock (Android's
own, in English, the system's language); on r1 `system_server` died once more and the launcher started twice. Every
line is **seen** or **measured** on `jelly36` unless it says **read** (AOSP `android-16.0.0_r1`) or **inferred**.

**B.13 and B.14, the language over the boots.** Before each: Lys, Kalender's and Meldinger's cards behind Home.

| Boot | The system's record after the unlock | `Launcher language: nb` | Look / language / `Unlocked setup took` (ms) | Home | The channels named in the unlocked setup |
|---|---|---|---|---|---|
| r1, first runtime | `[]` right after the boot | 04:24:10.649 | 272 / 52 / 9642 | created once | not read |
| r1, second runtime | not read | 04:25:19.794 | 290 / 304 / 20393 | created once | Norwegian |
| r2 | **without** the languages | 04:39:06.127 | 37 / 53 / 2662 | **relaunched** (`wm_relaunch_resume_activity` 04:39:06.126; created 04:39:10.206 and 04:39:14.118) | **English** |
| r3 | with them (`[nb]` before the process started) | 04:49:14.291 | 38 / 62 / 4943 | created once | Norwegian |
| r4 | **without** | 05:19:58.671 | 127 / **1679** / 13828 | created once, after the call | **English** |
| a1 (Android's lock; the process alive before the unlock) | **without** | 05:35:28.841; 05:35:48.502 in the process after the ANR below | 677 / 839 / 11455; 39 / 59 / 2232 | created once, by the second process | Norwegian (made by the second process) |
| a2 (the same) | with them (`[nb]` two seconds after the code) | 05:43:30.152 | **2648** / 168 / 4739 | created once | **English** |
| t1 (the T1 control's boot) | **without** | 07:00:37.554 | 1011 / 1099 / 3326 | **relaunched** (`wm_relaunch_resume_activity` 07:00:37.547; created 07:00:41.895, destroyed 07:00:44.530, created 07:00:45.430) | **English** |

- After every boot `get-app-locales` read `[nb]` from the launcher's call on, with no `Couldn't set the launcher
  language`; the lock's glance ("Ons. 11. nov.", "Nødsamtale", "Lommelykt"), the keypad ("Skriv inn koden din",
  "Foreldrekode"), Home, kid Settings, the phone book, Kalender ("Onsdag", "Pågår nå") and Meldinger were Norwegian.
  **The first run's finding is gone.**
- **No launcher frame in English was seen** at any boot (the emulator console's screenshots at about two a second -
  the PIN lock is a secure window, `screenrecord` shows it black: 289-725 frames a boot). On r2 the screen was black
  behind Android's ANR dialog from 04:39:10 to 04:39:21, so Home's first instance - created before the relaunch - was
  not pictured; on t1 no frame between the dark ground and the Norwegian lock showed one either. On a2 Home's first
  visible frame behind the dialog was Norwegian; on a1 Home's first frame was not pictured (the cover and the dialog).
- **Home's relaunch** (the design's named cost) came on two of the four boots whose record had no languages (r2, t1),
  and on neither of the two with them.
- **Not every second boot**: r2 without, r3 with, r4 without is the alternation; a1 (without, right after r4) and t1
  are boots after changes of look and language in between. Why a1 had none was not found (the stored record cannot be
  read without root); no boot showed the launcher in the phone's language, whichever record it had.
- **The restored cards are gone when the kiosk starts**: Kalender's and Meldinger's tasks of before the reboot are in
  Recents from the user's unlock (r3: both there at the language call, which was not refused) and gone at the first
  read with `mLockTaskModeState=LOCKED` (r3 04:49:20, r4 05:20:15, a2 05:43:43, t1 07:00:45). So on a kiosk phone no
  page is opened "from its restored card": every open after a boot is a cold one (why: not read in source). The cold
  opens in Lys (recordings after r1 and r3): Home's last frame, the system's cross-fade, the paper (F3F1ED) starting
  window, no dark frame, one `wm_on_create_called`. Meldinger's cold open is night throughout, as step 1 means it.
- **`expedite_activity_launch_on_cold_start`**: not on this image - `aflags` needs root on a user build, and the
  flag is not among the 29 of `com.android.server.am` in the image's `aconfig_flags.pb` files. Its state is unread.
- **The costs** (measured, L2's line): the language call 52-304 ms on six starts and 839-1679 ms on three (r4, a1,
  t1), the look's call up to 2648 ms (a2), both on the main thread of a loaded boot. On a1 the launcher itself got an
  ANR ("executing service KidVpnService, waited 20166ms", 05:35:40.575, cpu pressure 69 %) and was killed; its next
  process did the setup in 2232 ms and everything after was right. One sighting, on this host's loaded boot; the first
  run's two loaded starts (9300 and 14231 ms, without the language call) had none.

**The application's own texts (the code QA's L3): seen, by two routes.** On r2, r4 and t1 - a record without the
languages - and on a2 - a process alive before the unlock, with a record that had them - the channels the unlocked
setup names from the application's context were in English in the app's notification settings (`dumpsys
notification`: "App installs and updates", "Calls", "Calls (call screen open)", "Find My Device background
connection", "Play install mode", "Missed calls", "Find My Device ring", "Content filter", "Opened with the parent
code"), while the three `SmsNotifier` makes before each post were Norwegian ("Meldinger", "Meldinger (stille)",
"Reaksjoner"). On r1's second runtime, r3 and a1's second process all twelve were Norwegian. The ongoing notification
of the listener service has the title "Vibb One" and no text; an ongoing call's heads-up was "Pågående samtale" with
Android's own "Hang Up". **Fixed after the pass, not seen running**: `Application.onConfigurationChanged` names the
channels again when the process's languages differ from the ones they were named in (the app's own languages reach a
process as a configuration change; `ACTION_LOCALE_CHANGED` is the phone's language). To see on the next pass: a boot
whose record has no languages, then `I Application: Notification channels named again: nb` and Norwegian names.

**B.15, a parent's change** (one process, r3's, then the ones named).

- Both cards alive, Home in front, English saved in the PWA: nothing until Home was left and resumed (the phone book,
  Back); then **set at once** (`Launcher language: en` 04:56:14.541, no warning, Home created again, both cards
  alive, each page in English from its card).
- Norwegian saved with Kalender in front: nothing on Kalender. **Home straight from Kalender: one warning**
  (04:56:54.826), both tasks closed, `Launcher language: nb` 04:56:54.834 **in the same resume**. From a Meldinger
  thread: one warning 04:57:42.660, `en` 04:57:42.663. So the second ask of the resume got through although the page
  was only paused: the code QA's M1 outcome ("refused twice, then set late") did not come here, and the late ask was
  not needed. Home's `wm_on_resume_called` to the page's `wm_destroy_activity`: 356 ms and 344 ms.
- The page opened again and straight Home, three times with the language set: nothing asked, nothing closed.
- **The kiosk around a repair**: before, `mLockTaskModeState=LOCKED` with Home's task #18, Kalender's #25 and
  Meldinger's #26 in `mLockTaskModeTasks`; after, `LOCKED` with #18 alone. After each repair only `HomeActivity` was in
  the launcher's process (`dumpsys activity processes`).
- **A standard-type activity that stays** (M2). A plain `am start -a android.intent.action.DIAL` from the shell puts
  the phone book in Home's task (type home): no lingering one. With `-f 0x18000000` (new task, multiple) it gets a task
  of its own, type standard - that stood in for the `tel:` link in another app, which was not done. Then a change of
  language was refused at three rounds of Kalender and Home (the ask and the late ask each time: 05:02:57.868 and
  05:02:59.871, 05:03:06.588 and 05:03:08.591, 05:03:15.329 and 05:03:17.331), **Kalender's card closed at the first
  only**, the process holding Home, the phone book's task and Kalender. The language did not land while that task
  lived. With the task removed (`am stack remove`, the run's own): Kalender then the Home gesture was **still refused**
  (the close is not made twice for one language, so Kalender stayed the followed activity); Kalender left with Back:
  refused at the resume (05:04:00.999) and **set by the late ask** two seconds later (`en` 05:04:03.003) - the late ask
  seen working, and the M2 rule's cost seen: after a close in vain the change waits for a Back out of the page, the
  PIN lock or a process start.
- The PIN lock up when the sync lands: nothing while locked; at the unlock one warning (05:04:55.108), Kalender's card
  closed, `nb` 05:04:55.113 in the same resume. During an incoming call: nothing for ten seconds and no lifecycle
  event; at Home after the caller hung up one warning (05:06:08.309), the card closed, `en` 05:06:08.335.
- Killed with Kalender in front (as its own uid): the new process created Home first, then the PIN lock; a change
  saved after the unlock was set at once at the phone book and Back. Nothing stayed refused on this route.
- No screen was seen in a mix of the two languages. Meldinger's tile and header read "Beskjeder" in both - the test
  database's own display name for the app, no missing string.

**B.16, a change of look refused: seen (the review's N1), on a natural route twice.**

- Route 1 (Kalender in front, killed, unlock, the other look): **not refused**, twice (the new process creates Home
  first on this image).
- A constructed start (the kill and a new-task `DIAL` in one shell line, so the phone book's standard-type task is
  the process's first activity): the tap on Mørk logged `E PageLook: The app's night mode wasn't set` with `Can't
  change activity type once set: {... mActivityType=standard ...} activityType=home`; `kid_look.xml` had `dark`/`dark`;
  Kalender's cold open showed **the paper starting window for about 440 ms** before the page in night. `adb install -r`
  (a process start) put it right: night in every frame.
- **Route 2, natural** (r4: the process started at boot for `KidVpnService`, no activity at the unlocked start's two
  calls; English saved in the PWA while the PIN lock was up; unlock): the language was set at once at that resume
  (05:24:09.763, the PIN lock finishing and not yet destroyed), and then the tap on Mørk in kid Settings was
  **refused** (05:24:50.734, the same exception); `dark`/`dark` stored; Kalender's cold open showed the paper starting
  window for about 330 ms, then night. A tap back on Lys was refused too. **Again on a2** (Android's lock screen,
  another boot): the language set at the unlock 05:45:39.242, the tap on Mørk refused 05:45:52.324.
- In r4's process the model's two predictions were then tried and held: a language change at a plain Home resume (the
  phone book and Back) refused, late ask included; the same change at Home straight from Kalender set at once.
- **Read and inferred**: the process's scratch configuration takes the type of the first call that passes with an
  activity followed; here that was the language call at the unlock, under the PIN lock (standard), and every later
  call under Home or kid Settings (home) is refused for that process's life. The call that fixes the wrong type is the
  first ask of a Home resume right after the PIN lock, a page or a call - the one this build makes at once.

**The control on main** (809bbcd0 with `adb install -r -d` over this build; the system's record still held this
build's night mode): the first change of the process's life was set at the phone book and Back (06:00:03.466). With
Kalender's card alive, the second change was **refused twice** at Home straight from Kalender (06:00:27.990,
06:00:36.093, `Can't change activity type once set`), and then set at the phone book and Back with the card still in
Recents (06:00:43.204). So the refusal is older than step 1, as read; the steps' "at every Home resume" was too wide
(corrected above).

**T1, the control for the reboot's cause** (boot t1): before the power-off both the launcher and
`com.google.android.deskclock` read `[nb]`. After the boot the launcher read `[]` until its own call
(07:00:37.554), and the clock app - which has no night mode and which nobody sets - read `[nb]` at its first read. One
boot; it agrees with the source reading (a record with a night mode loses its languages on disk).

**B.7's `install -r` half, B.9, B.10, B.11: passed.**

- B.7: `light`/`light` after the install, the look's call 12 ms, the language's 11 ms, the setup 178 ms; Kalender's
  cold open paper from its first light frame, one `wm_on_create_called`.
- B.9: killed with Kalender's card behind Home; the tap on Mørk: no error, `dark`/`dark`, the card removed
  (`wm_destroy_activity ... finish-and-remove-task`), `LOCKED` before and after, the next open night in every frame.
- B.10 (`light`/`light` written by hand, kill): no change ran; Kalender's cold open in Lys, one create.
- B.11 (`applied` = `dark`, `choice` = `light` written by hand with Kalender's card behind, kill): Home's first resume
  ran the change (the card destroyed 56 ms after Home's create, before the PIN lock came up), `light`/`light`, still
  `LOCKED`; the next open in Lys. That is the stored half of the first review's L4; **the death between the change's
  first write and the tasks' removal was not constructed** (nothing to stop the process there). **L5** (Home's resume
  closing a task just opened while a change waits) was **not constructed** either: two fast one-line tries put the tap
  into the outgoing Kalender window, and B.11's waiting change is finished before the PIN lock is up.
- B.12's lines on these starts: 2-47 ms for each call, 126-829 ms for the setup.

**C, Kalender in both looks** (sets of pictures compared pixel by pixel below the status bar).

- **Mørk is main's picture** with the system dark and light, on the day view, an event's page, the kid's own event,
  the delete dialog, the editor, both pickers and Varighet - but for the time-dependent rows and digits, a keyboard
  glyph, and one improvement: Varighet's choices are readable in Mørk on a system-light phone (almost invisible on
  main). The pickers' "AVBRYT"/"OK" in Mørk are as faint as on main: unchanged by step 1.
- **Lys** reads well on every one of them; the pressed row's ripple is visible; today's number while another day is
  chosen is peach on white, faint but legible; the delete dialog is a white panel with dark text and accent buttons.
  The system keyboard follows the system's theme under either look.
- **3-button navigation, the code QA's M1**: in Mørk the bottom of each screen is main's picture in both system
  modes (no scrim band on either build on this image). **Finding**: the three buttons follow the **system's** dark
  theme, not the page - Lys with the system light: dark grey buttons on the paper, clear, the keyboard up too; **Lys
  with the system dark: white buttons on the paper, nearly invisible**. SystemUI's `LightBarController` under that
  page: `mHasLightNavigationBar=true` (the page asks for dark buttons), `mNavigationLight=false`,
  `mForceDarkForScrim=true` - SystemUI's own override, inferred from its dump. A real phone's bar is unknown. Nothing
  built.
- The key under the day list: part-way down the list its last word runs under the "+" disc in Lys as on main; at the
  list's end it is clear of the disc in both looks. The neighbouring days' rows peek at both edges in both looks.
  Neither is step 1's.

**D, the screens that are not pages.**

- System dark on and off, Mørk: this build is main's picture on Home, the phone book, the contact card, Meldinger's
  list and a thread, kid Settings' lists (Wi-Fi, Bluetooth, sound), the drawer, the parent PIN dialog, the link-blocked
  notice, the PIN lock's glance and keypad, the emergency menu, the time-rule screen, an incoming and an ongoing call -
  but for clock digits, the debug "Utseende" row and Bluetooth's state text. Lys against Mørk on this build: the
  same, but for the row and its preview.
- With Lys applied and the system's dark theme flipped twice under each of them (the glance included): no
  `wm_relaunch_activity` and no new create anywhere, the incoming and the ongoing call included.
- A change of look during an ongoing call (kid Settings under the call's heads-up, Mørk then Lys): both applied, no
  lifecycle event on the call screen.
- The phone book's and Meldinger's cold opens in Lys on a system-light phone: night (090A11) in every frame.

**E.** TalkBack itself was **not run**: switched on under the kiosk it brought Android's "App is not available"
notice (`BlockedAppActivity`), and it was switched off again. The nodes instead (`uiautomator dump`): the heading
"Utseende", a group "Utseende" with "Mørk" and "Lys" as checkable choices, the applied one checked, the caption; the
preview strip and its two texts are not in the tree. Font 1.3: the segments 48 dp with whole words, the strip 56 dp
with both bubbles inside, the caption on three lines; Kalender's screens, dialogs and pickers in Lys fine.

**F.** The image has no `accessibility_force_invert_color_enabled` setting and the flag's state is unread. With the
key written by hand and the system dark, a cold Kalender in Lys was the same picture as without it; the key was
deleted again. Android's own switch was not looked for in its Settings; nothing is judged. The release build's half
was not run (no release APK).

**Not run**: TalkBack itself; Android's "make more apps dark" switch; L4's death between the write and the removal;
L5; the phone book through a `tel:` link in another app (a new-task `DIAL` from the shell instead); the control on
main on a wiped AVD; a release build; the channels' fix; anything on a phone. Pictures of the run are in the session's `scratchpad/kid-look-1/shots/`.

### The run of 2026-11-11 on `kid-look-2` (`jelly36`): every open path in both looks, B.3-B.6, B.17-B.20

`kid-look-2` at 1ae78e97, revision 3's mechanism (the debug APK from one gated `assembleDebug
-PwarningsAsErrors=true`, the emulator down; 0.23.6-debug, code 116, SHA-256 `0ae6662e...df32a69`, the installed file
the same), provisioned as Device Owner on `jelly36` put back from the clean copy taken before `kid-look-1`'s first run
(22 files, SHA-256 identical, so no app night mode in the system's record; `dumpsys uimode` named none). The test
server was the binary the earlier runs used (no cargo). System language en-US, the launcher's `nb` from the policy,
the kiosk on unless said, gesture navigation, the launcher's own PIN lock, `-timezone Europe/Oslo` (the times below
are the phone's). §6w's calendars with made-up names only. **A "reboot" is `adb shell reboot -p`, the memory gate read
with the emulator down and a cold start**: 8046 MB at the first start, 7769 and 7758 MB at the two reboots; the
emulator was never killed and went down by `reboot -p` at the end. `logcat -s PageLook` and the main and events logs
were kept for the whole pass (11:38:05-13:14:49). Every boot on this host has ANRs in SystemUI and Google's apps and a
"System UI isn't responding" dialog; none is the launcher's. Every line is **seen** or **measured** on `jelly36` unless
it says **read** or **inferred**.

**The measure.** 68 recordings with the device's own `screenrecord`, every frame stepped and sorted by the share of
the page area that is the night ground (`0C0C14`) or the paper ground (`F6F2EF`): NF/LF a flat night/paper frame
(97 % or more - a starting window), N/L mostly night/paper, X neither (a day view with its cards, or the overview
shrinking), B black. Home itself is N: 78 % night, at most 1.3 % paper (its labels). An open in Mørk is clean with no
L or LF frame and paper never above Home's 1.3 %; an open in Lys is clean with no NF or N frame after the first X, L or
LF frame (the N frames before it are Home with the press; the X frames of a tile open are the system's cross-fade out
of Home, night falling) and night at 0.1 % or less once the paper ground is up. In the table:
the film's frames / the frames in the second after the first changed one, then the kinds that matter. "Cold" made
the task, "warm" brought it forward.

**No frame of the other look on any path, the starting window included.** No page was relaunched (0
`wm_relaunch` lines for a `.day.` class in the whole events log), every cold open had one `wm_on_create_called` for
the look's class, and the starting window's theme was the look's every time (`Checking theme of starting window`:
`KidPageNightTheme` in Mørk, `KidPageLightTheme` in Lys, also for the snapshot windows of the warm opens).

| Path | Mørk (largest paper share) | Lys (night once the paper ground is up) |
|---|---|---|
| Home's tile, cold, the phone's theme the other way | 90/19, N 29 NF 37, 1.2 %; again 51/14, 1.3 % | 67/15, N 6 X 1 L 23 LF 13, no NF; again 63/13, no NF |
| Home's tile, warm, the same | 43/17, 1.2 %; 43/25, 1.3 % | 40/19; 40/16; no NF |
| Home's tile, cold, the phone's theme the look's own | 53/16 and 65/24, NF 8-10, 1.3 % | 66/23 and 71/19, N 13-15 (Home) then X, L 4-5, LF 10-11, no NF, 0.1 % |
| The PIN lock's glance, the reminder row and the code, cold | 85/32, 0.2 % | 86/29, 0.1 %; again 79/21 |
| The same, warm | 79/33, 0.8 % | 71/34, no NF |
| The time-rule screen's Kalender row, cold | 66/27, 0.8 % | 51/27, no NF |
| The same, warm | 45/32, 0.3 % | 54/28, no NF |
| Day view to an event | 69/29, 0.4 % | 70/31, 0.1 % |
| The event to its edit page ("Endre") | 84/32, 0.7 % | 90/29, 0.0 % |
| Back to the event | 56/27, 0.1 % | 53/6, 0.0 % |
| Back to the day view | 18/13, 0.1 % | 19/12, 0.1 % |
| The "+" (a new event) | 80/31, 0.5 % | 65/28, 0.1 % |
| Back from it | 45/30, 0.1 % | 45/10, 0.1 % |
| Home and the tile again, the event's page open | 89/18, 1.3 % | 91/20, no NF |
| The Recents card, the process alive, the kiosk on (a tap on the card) | 47/17, X 1 N 32, 0.1 % | 51/23, X 4 L 35, 0.0 % |
| After a process kill, the kiosk on: **no card**, `am task focus` | 86/31, N 32 NF 20, 0.2 %, `EventActivity` created | 50/29, X 6 L 22 LF 16, no NF, `LightEventActivity` created |
| After a process kill, the kiosk on, Home's tile | 57/29, 1.3 % | 69/31, no NF |
| After a process kill, the kiosk off: **the card's own tap** | 80/29, X 3 (the overview) N 38 NF 31, 0.2 % | 73/39, X 2 L 30 LF 31, 0.0 % in every frame |
| After a reboot: **no task left**, Home's tile | 48/19, N 18 NF 8 X 9, 1.2 %, one create, `DayActivity` | 47/26, N 24 (Home) X 9 LF 9 L 1, no NF, one create, `LightDayActivity` |
| The first open after a change of look | 49/14, 1.2 % | 57/13, no NF |
| The first open after a change of language | 62/12, 1.2 % (`[nb]`) | 70/14, no NF (`[en]`) |
| The first open after a forced other look (B.17-B.19, five) | 43/27 and 41/27, 1.2-1.3 % | 66/33, 44/24 and 60/29, no NF |

"The glance's row, the reminder row" is one row on this build: the PIN lock's glance opens Kalender from its reminder
row only; the other lock-side way in is the time-rule screen's Kalender row (the rule's `calendar_allowed` set in the
test database for it). The PIN lock is a secure window and records black, so the glance's films show the page's
window coming in over black (37-55 black frames, then the night ground rising in Mørk and the paper rising in Lys,
the other ground's share as in the table); what the lock itself drew at the tap is not in them. The films of the two
unlocks after the reboots: 26-27 black frames for the same reason, then Home; night at most 84.1 %, paper at most
1.3 %, Home created once, no relaunch line.

**After a reboot there is no card**: before each power-off Kalender's task (an event's page over the day view) was in
`dumpsys activity recents`; after the boot no `.day` task was there, before the unlock or after it, in either look
(the second run above saw the restored cards go when the kiosk starts). So every open after a boot is a cold one from
Home or the lock. `get-app-locales` read `[]` at `sys.boot_completed`, with no launcher process yet, and `[nb]` at the
first read with the launcher in front; `Launcher language: nb` once per boot (12:53:07.040, 13:01:25.166), no warning,
the lock's texts Norwegian, `LOCKED` after both. Whether the stored record had kept the languages was not separated
from the launcher's own ask.

**B.3-B.6, a change of look with a page task alive: passed with Home as the kiosk's first task.** Three changes (to
Lys from the day view's task; to Mørk and to Lys with an event's page in the task): the recents count 1 to 0 within
0.3-0.4 s, both activities destroyed (`finish-and-remove-task`), kid Settings in front in every frame (films of
39-47 frames, all N), `LOCKED` before with Home's and Kalender's tasks and after with Home's alone, no "App is not
available", the next open the day view in the new look. B.6 in both looks: 0 destroy or finish events, 0 `PageLook`
lines, the count stays 1.

**Finding 1: under the kiosk, right after a launcher process kill with Kalender's task alive, a change of look drops
kid Settings to Home.** The kill removes Home's task and the new process gets a new one, so Kalender's task is first
in `mLockTaskModeTasks` - the lock task's root. Removing the root makes the system clear every locked task:
`wm_destroy_activity [.., HomeActivity, finish-imm:clear-task-all]` and `wm_finish_activity [..,
KidSettingsActivity, clear-task-all]` 1-64 ms after the page task's `finish-and-remove-task`, then a new Home task.
Seen three times (12:17:11.706, 12:20:06.009, and recorded at 12:21:38.335: kid Settings gone and Home in front about
1.3 s after the tap; 65 frames, N 62 X 1, none of a page). The look was stored right each time, `LOCKED` after with
the new Home task alone, no crash, and no frame of the other look. B.3's "kid Settings stays in front" fails in this
state only. The same clearing came in both forced runs under the kiosk below (Home created again). It is what
revision 3's removal of a task adds to the older state of finding 2.

**Finding 2: in the same state Back does not leave Kalender.** With Kalender's task the lock task's root, the Back key
(three times) and the page's own back button (once) on the day view did nothing: `I ActivityTaskManager: Not finishing
task in lock task mode` (12:48:32.313, 12:49:00.712, 12:49:04.856, 12:49:14.336). The Home key works and the tile
brings the same task back; the overview shows no card for it (six tries in both looks). With Home as the root, Back on
the day view finishes the task as it should. The state ends when the task is removed (a change of look, with finding
1's effect) or at a reboot. **Inferred** older than design 78: main's manifest already gives the day view the task of
its own (`taskAffinity` `.day`); main was not run for it. **Both findings: cause and fix in §6ai** (Home's record
removed at the kill; reproduced on main's tip, gone with `stateNotNeeded`).

**A change of language with an event's page in front** (English, then Norwegian, saved in the PWA): nothing on the
page for 6 s; at the Home key two `W LauncherLocales: Couldn't set the launcher language` (12:27:59.732 and .748;
12:29:28.365 and .375, `Can't change activity type once set`), the page's task closed, and `Launcher language: en`
12:28:01.753, `nb` 12:29:30.383 from the late ask - step 15's "refused twice, then set late". Home's
`wm_on_resume_called` to the page's `wm_destroy_activity`: 587 ms and 455 ms, the late ask 2.0 s after the resume.
Home was relaunched once each time (`wm_relaunch_resume_activity` 12:28:01.751, 12:29:30.380): 5 black and 4 flat
night frames, then 3 black and 1 (films of 54 and 53 frames) - Home's own, night, no paper frame.

**B.17-B.19, a page of the other look's class: the check closes it every time.** The file written by hand and the
process killed as in the steps. In all five: one starting window of the task's own class for about a second (the old
look's - the step's "one night starting window", and a light one where the task was Lys's), the `E PageLook` line,
nothing of the page drawn, the crash buffer empty, the recents count 0, no `W PageLook`, Home in front, and the next
open from Home's tile in the stored look from its first frame with one create (the table's last row).

| Run | Kiosk | The route | The line | The film |
|---|---|---|---|---|
| Mørk's task, `light` stored, the day view the root | on | **no card after the kill**: `am task focus` | `DayActivity was created in the light look: its task is closed` 12:31:52.506 | 90/30, a night window, paper 1.3 % |
| Lys's task, `dark` stored, an event's page over the day view | on | **no card**: `am task focus` | `LightEventActivity was created in the dark look: its task is closed` 12:33:49.346 | 96/40, LF 33 (the light window, about 0.9 s), then Home; no day view under it |
| Mørk's task, `light` stored, the root (B.17) | off | **the card's own tap** | `DayActivity was created in the light look` 12:38:24.729 | 64/27, X 3 (the overview) N 29 NF 24, paper 1.2 % |
| Lys's task, `dark` stored, an event's page | off | the card's own tap | `LightEventActivity was created in the dark look` 12:40:12.766 | 64/30, X 4 L 12 LF 24, then Home |
| Mørk's task, `light` stored, an event's page (B.18) | off | the card's own tap | `EventActivity was created in the light look` 12:41:58.220 | 57/36, X 3 N 25 NF 19, paper 1.2 % |

- B.17's card: with the kiosk on the overview has no card for Kalender after the kill (the task is in `dumpsys
  activity recents`), so the card's own tap was run with the kiosk off, where the card shows - with the task's old
  picture on it - and the tap is a real `input tap` on it.
- B.18: both records gone each time (`LightDayActivity`/`DayActivity` destroyed `finish-and-remove-task`), no day view
  under the closing event page in any frame.
- B.19: `LOCKED` before and after in both kiosk runs, Kalender's task out of the list, no "App is not available". But
  "Home's task still in `mLockTaskModeTasks`" holds only as a **new** Home task (finding 1's clearing: Home finished
  `clear-task-all` 12:31:52.520 and 12:33:49.369, created again, `LOCKED` with the new task alone).
- The kiosk off and on for these runs was `kiosk_desired` in the test database: off in 5 s (the system finished
  Kalender's task, `clear-task-all`); on again after 25 s - the first `startLockTask` failed with an event's page in
  front (`W HomeActivity: Lock task change failed - retried at the next resume`) and held at Home's next resume.

**B.20: no refused change.** Over the whole kept log: `E PageLook` 5 lines, the five forced ones above; `The look
stays` 0; `The kid's look wasn't read` 0; `W PageLook` 0. `Can't change activity type once set` 4, the two language
changes' warnings. No crash and no ANR of the launcher (`am_crash` 0; 20 `ANR in`, none of `me.vibb`); the one `FATAL
EXCEPTION` is the shell's own `uiautomator dump` timing out at 11:41:27, during the install's load. B.12's lines on
the 16 process starts: the language 4-30 ms and the setup 146-547 ms after the kills, 8 and 1812 ms at the install,
306 and 4765 ms after the first reboot, 10 and 547 ms after the second.

**Not run**: the kid Settings row jumping back (B.20's refused change cannot be forced by hand); a Recents card after
a reboot (no page task survives a boot) and the card's tap after a kill under the kiosk (no card shows; `am task
focus` instead); finding 2 on main; A's status-bar icon frame counts and its theme and Battery Saver flips under an
open page; B.7's reboot within seconds of the tap, B.8, B.10, B.11 and B.13-B.15 as written (a reboot in each look and
one language change each way were run instead); C-F; a release build; anything on a phone. The pictures taken for the
eye (Kalender's day view, an event and the edit page and kid Settings' "Utseende" in both looks, Home once) are in the
session's `scratchpad/kid-look-2/shots/`; the pages' times are in the 12-hour form there ("8:30 a.m.": the emulator's
en-US system, inferred), which is not the look's.

## 6ai. Home stays the lock task's root after a process kill (kiosk-root, 2026-11-11)

§6ah's two findings under the kiosk, fixed by `android:stateNotNeeded` on `HomeActivity`
(`docs/notes/launcher/kiosk-escapes-and-updates.md`, "Home stays the lock-task root after a process death").

**Setup**: `jelly36` as §6ah left it (the debug launcher as Device Owner, enrolled, the kiosk on, the launcher's own PIN
lock, gesture navigation), the test server §6ah used; no `adb root` (a `user` build): the kill is the launcher's own uid.
Kalender is the tile at (388, 312), Meldinger (240, 312), kid Settings (240, 616), its Mørk/Lys at (145|335, 546), a
page's own back button (58, 84). Each check:

```sh
adb install -r app-debug.apk                       # the build under test, then unlock
adb shell input keyevent 3; adb shell input tap 388 312   # Kalender (or 240 312, Meldinger)
adb shell input keyevent 3                          # Home in front, the page's task stopped behind it
adb shell dumpsys activity activities | grep -E "mLockTaskModeState|mLockTaskModeTasks" -A3
adb shell "run-as me.vibb.launcher.debug sh -c 'kill -9 \$(pidof me.vibb.launcher.debug)'"
# the PIN lock comes back: unlock, Home; the lock task's order again
adb shell input tap 388 312; adb shell input keyevent 4   # the page from its tile, Back (or tap 58 84)
adb logcat -d | grep "Not finishing task in lock task mode"
adb logcat -b events -d | grep -E "wm_(finish|destroy|restart|create)_activity|wm_task_(created|removed)"
# the change of look: after the kill, Home, kid Settings (240 616), tap the other look, recorded with screenrecord
```

**Before, on main's tip (610eec36)**: both findings reproduced. The kill with Home in front:
`wm_finish_activity [0,137220399,103,..HomeActivity,proc died without state saved]` 13:50:16.460, `wm_task_created 106`
and a new Home record in it, the old Home task 103 removed 3 s later; the lock task's order went from `#0 #103 home,
#1 #105 .day` to `#0 #105 .day, #1 #106 home`. Kalender from the tile, then the Back key and the page's own button:
`Not finishing task in lock task mode` 13:50:45.937 and 13:50:49.008, the day view still in front. Kid Settings, the
tap on Mørk: `finish-and-remove-task` of the day view, then `wm_destroy_activity [.., HomeActivity,
finish-imm:clear-task-all]` and `wm_finish_activity [.., KidSettingsActivity, clear-task-all]` at 13:50:57.716, a new
Home task 108 at once, Home in front at the read 3 s after the tap; the look stored, `LOCKED` with task 108 alone.

**After, the fix (e490362f)**: `dumpsys activity activities` shows `stateNotNeeded=true` on Home's record. Eight kills
with Home in front: no `proc died without state saved` (the three in the whole log are main's install, the kill on
main above and the fix's install - a package replace removes every record), `wm_restart_activity` on the **same**
Home record in the same task (110 throughout), no new Home task, and the order before and after every kill `#0 #110
home, #1 <the page's task>`, `LOCKED`.

| Check (the kill with Home in front first, unless "plain") | Result |
|---|---|
| Kalender (Mørk), the Back key | Home in front, the day view `app-request` finished, task 112 removed, no refusal |
| Kalender, the page's back button | the same (task 114) |
| Kalender alive, kid Settings, Mørk to Lys | kid Settings in front throughout (film: 41 frames, all kid Settings' night ground; paper at most 6.0 %, its own Lys preview), the day view's task `finish-and-remove-task`, `LOCKED` with task 110 alone |
| Kalender (Lys) alive, Lys to Mørk | the same (40 frames), `LightDayActivity` removed |
| Meldinger, the Back key / the page's back button | Home in front, `MessagesActivity` finished, its task removed, no refusal |
| Meldinger alive, Mørk to Lys and back | kid Settings in front (43 and 34 frames, all kid Settings); Meldinger's task stays (this build closes only Kalender's at a change of look) |
| plain: Kalender, the Back key; Meldinger, the Back key | Home, the task removed |
| plain: Kalender alive, Mørk to Lys and back | kid Settings in front (43 and 40 frames), the day view finished (`finishIfPossible`), task removed |

No `Not finishing task in lock task mode` after the fix's install, `LOCKED` at every read, no crash of the launcher.
One ANR of the launcher at the cold boot (13:47:42, the build before both installs, in the boot's wave of ANRs in
SystemUI, Phone, GMS and the keyboard - §6ah's "every boot on this host"). The logs and films are in the session's
`scratchpad/kiosk-root/steps/` and `rec/`.

**Not run**: the `.call` task (no call placed - the same rule covers it: the cause is Home's record, not the page);
a crash of the launcher (no hook to force one; AOSP takes the same `handleAppDied` path after the crash's finish of
Home is blocked); a release build; a phone.

## 6aj. The kid's look, step 2: Meldinger, the phone book and the lists (design 78)

**Steps, written with the build on 2026-11-11 (branch `kid-look-3`); not run yet.** Step 2 puts Meldinger's three
screens, the phone book and kid Settings' Wi-Fi, Bluetooth and sound lists on the page tokens, keeps a thread's unsent
text, and shows the "Utseende" row in every build (`docs/notes/launcher/ui-polish.md`, "The kid's look: Mørk or Lys";
design 78 §8, "Step 2, 2026-11-11").

Setup: **`jelly36`** (§3), as §6ah's setup (the debug launcher of `kid-look-3` as Device Owner, enrolled, the kiosk on,
the phone in Norwegian), with SMS "Styrt av Vibb" and §6v's fake MMS server for pictures; contacts Mamma, Pappa (both
"Kan få melding fra" and "Bilder i meldinger" on), Emma (a group with Mamma and Pappa), Jack without "Kan få melding
fra", 112 in the phone book; a missed call from Pappa; an open contact request (§6x's form); two paired Bluetooth
devices; a few Wi-Fi networks. §6ah's command block applies, with:

```
adb logcat -b events -d | grep -E "wm_on_create_called|wm_relaunch_activity|wm_destroy_activity" | grep -E "messages|calls|quickcontrols|kidsettings"
adb shell dumpsys activity recents | grep -c "\.messages"             # Meldinger's task: 1, or 0 after a change
adb shell dumpsys window windows | grep -E "Splash Screen|APPLICATION_STARTING"
adb shell run-as me.vibb.launcher.debug cat shared_prefs/sms_drafts.xml # the kept texts
```

### A. Every screen in both looks

Each in Lys beside `docs/design/78-mockups.html` and in Mørk beside main's build of the same screen (Mørk must be
main's picture; anything that differs is a finding), at font scale 1.0 and 1.3, the system's dark theme on and off:

- [ ] Meldinger's list: the header and its back disc, the search field (focused: its peach ring), a row with the
  avatar, name, last line, time and the unread dot, the empty search's state, "Ny melding" and its contact list, the
  delete confirm (long-press) with "Avbryt" and "Slett".
- [ ] A thread: received and own bubbles (Mamma; Emma's group with the sender's name over each received bubble;
  "Ukjent avsender"), a picture's place before the picture opens and after, "Prøv igjen" on a picture that failed, the
  delivery line ("Sendt", "Levert", "Ikke sendt - trykk ...") and the day separator, a selected bubble of each side
  (its ring), the reaction chip, the reaction panel and the emoji picker, the "Ny melding" mark, the compose field
  (its hairline in Lys) with the keyboard up, "+" and the picture choice, send. The read-only line (Jack's thread).
- [ ] A received picture (`PictureActivity`): night in both looks, from its first frame.
- [ ] The phone book: the header, the search, the "+" disc, a contact row with its badge, Ring and Melding discs, the
  emergency number's red Ring, the missed-call line, the open request row (its peach edge) and an answer row, the
  request form (fields, the photo place, errors, "Send", the sent card).
- [ ] Over the phone book in Lys: the contact card, "Bilde av …", the emergency menu and the emergency Ring's confirm
  are **night**, every text, ripple and button as in Mørk; the sheets reach the screen's bottom with the dim behind
  (the window token and dim with `nightOver`'s context). The other two confirms (a `tel:` link) are the page's dialog.
- [ ] Kid Settings' Wi-Fi list (and its password dialog: a white panel in Lys), the Bluetooth list, the ringtone and the
  message-sound pickers (the card, the radio rows, the captions).
- [ ] The status bar's icons and the gesture pill on each, and with the keyboard up. 3-button navigation in Lys with the
  system's dark theme on: note the buttons (design §12.9 point 4; known white on the paper).

### B. Starting windows and the change

1. Record (§6k's frame-stepping) Home's Meldinger tile -> the list, cold and warm, in each look and each system theme:
   no frame of the other look's ground; the events log has one `wm_on_create_called` for the look's class
   (`MessagesActivity` in Mørk, `LightMessagesActivity` in Lys), no relaunch line.
2. The same for the phone book (Home's tile) and the three lists: `dumpsys window windows` shows **no** `Splash Screen`
   for them (home-type, R9), and no frame of the other look's ground.
3. The phone book's doors from outside (design §12.9 point 1), with the look unlike the system's theme: a `tel:` link in
   another allowed app, and the missed-call notification's tap. Each: `dumpsys window windows` without a `Splash
   Screen` for the phone book, and no frame of the other look's ground. A splash on one is a finding (that door would
   need the look-less entry).
4. The message notification (a message from Mamma with Meldinger closed), posted in Mørk, the look changed to Lys,
   then tapped - and the other way round: the thread over the list in the **stored** look's classes, no frame of the
   other look, no black or empty frame, no Home frame between (the entry has its own task: `taskAffinity=""`), the
   entry not in Recents. Tapped with Kalender in front too.
5. A change of look in kid Settings with Meldinger's task behind (a thread open on the list): `.messages` 0 in Recents
   right after, `wm_destroy_activity` for both; the next open makes one task with the new look's classes alone; no
   `E PageLook`. With the kiosk on (§6ah B.3's checks).
6. **L5** (a waiting change at a Home resume while a page's start is in flight) is not constructed here: with kid
   Settings in front no page task is started, so no change waits. The rule is in `PageLookTest`; note any
   `E PageLook` over the whole pass.

### C. The kept text

1. Type "Hei Mamma" in Mamma's thread (do not send), go Home, change the look, open Meldinger and Mamma's thread: the
   text is in the field. `sms_drafts.xml` has one entry for the conversation.
2. The same across a process kill (`am kill` is refused for the home app; `kill -9` on the launcher's pid as §6ai) and
   a reboot: the text is back.
3. Send it: the field clears, the entry leaves `sms_drafts.xml`. Type and empty the field: no entry.
4. Type in Pappa's thread, then delete the conversation from the list: the entry goes. Type in Mamma's thread, then
   turn off "Kan få melding fra" for Mamma in the PWA: after the sync the thread shows its line instead of the bar and
   the entry is gone (also when the list loads without opening the thread). Set SMS to "Av": every entry goes.

### D. The row in a release build

From CI's release APK on an emulator of its own (never a local build on a phone): kid Settings shows "Utseende" with
Mørk / Lys, the preview and the caption; a tap on Lys changes Meldinger, Kalender, the phone book and the lists, and
nothing else.

### What `jelly36` can't show

The Jelly Star's build (step 0's recording there is still owed before release), a real SIM's MMS, an Android 14
phone's bars under a Lys page, TalkBack where the kiosk blocks it.

## 6ak. Mobilnett: Bare Vibb, the roaming lock and the mobile-network settings lock (designs 94/95) (2026-11-13)

**Steps, written with the build on 2026-11-13 (branch `mobilnett`); not run yet.** Design 95 revision 3: the VPN's
three shapes (filter on Wi-Fi, the sink for Bare Vibb on cellular, the pass shape standing ready when the filter is
off), one owner of always-on and lockdown, design 94's `DISALLOW_DATA_ROAMING` with its relock, and
`DISALLOW_CONFIG_MOBILE_NETWORKS`. The PWA's page is Device › Mobilnett (`/devices/{id}/mobile`).

Setup: **`jelly36`** (§3), the debug launcher of `mobilnett` as Device Owner, enrolled to a local server of the same
branch, the kiosk on. Wi-Fi on at the start. For the roaming steps (D) the stock AVD reads `roaming=false` under
`gsm data roaming` (the SIM and the network share MCC 310 and the name, `isSameNamedOperators`): use design 95 §1.1's
private image copy with a Norwegian SIM (`-sysdir`, `CIMI 242010000000000`) and its APN (the probe added one with
`addOverrideApn`; the launcher doesn't - add it through a throwaway DO-less step or Settings › APNs during Åpne for
oppsett). A second app to test with: any allowed browser, or `me.vibb.probe.other` from the d95 scratchpad, fetching
`https://1.1.1.1/cdn-cgi/trace` (an IP literal: TCP) and `https://www.google.com/generate_204` (a name: DNS).

```
adb logcat -s KidVpnService MobileNetwork AppEnforcer          # "Tunnel: sink", "Always-on: ... lockdown true (sink)"
adb shell dumpsys connectivity | grep -iE "VPN|lockdown|always"  # the VPN network, its routes; lockdown
adb shell settings get secure always_on_vpn_lockdown             # 1 only while the sink holds
adb shell dumpsys user | grep -E "no_config_vpn|no_data_roaming|no_config_mobile_networks"
adb shell run-as me.vibb.launcher.debug cat shared_prefs/*.xml | grep -E "vpn_inputs|vpn_shape"
```

### A. The filter still works on Wi-Fi (Mobildata: På)

1. Wi-Fi on, Mobildata "På". `Tunnel: filter`; lockdown off; a blocked domain in the other app's browser is blocked as
   before; an allowed one loads.
2. The launcher is outside the VPN now in every shape: `adb shell dumpsys connectivity` lists the VPN's UID ranges
   without the launcher's uid; a sync (`adb shell am broadcast` of the debug sync, or the PWA's nudge) works, and the
   server's name resolves through the network's resolver (the server's log shows the request).
3. **Split-horizon check** (design 95 §2.2): if the server's name only resolves through the parent's own DNS server
   (design 36), note whether the sync still works; report it either way.

### B. The sink on cellular, and the switch both ways

1. PWA: Mobilnett › Mobildata › **Bare Vibb**. The page lands on `#mobile-data`, never at the top; the phone's state
   line shows nothing new while on Wi-Fi.
2. `adb shell svc wifi disable`. Within a second: `Tunnel: sink`, then `Always-on: ... lockdown true (sink)`
   (the tunnel first, then lockdown). The other app: the IP literal fails at once (`ConnectException`, ~1 ms), the
   name fails at once (`UnknownHostException`); nothing hangs for seconds.
3. The launcher works: a PWA nudge (change a harmless setting) reaches the phone within seconds; Finn telefonen
   answers (GPS only: the emulator's fix is fake anyway). The summary in the PWA shows "Mobildata: bare Vibb".
4. **SSE and a sync survive the switch:** with the stream open (`adb logcat -s CommandListener`), switch Wi-Fi ->
   cellular -> Wi-Fi twice; the stream is never reopened because of the switch (no "stream closed" at the switch),
   and a sync started a moment before a switch completes.
5. `adb shell svc wifi enable`: lockdown off first (`Always-on: ... lockdown false (filter)`), then `Tunnel: filter`;
   the other app loads again.
6. **Lockdown is never off while sinking:** during step 2-4, run a few syncs (`adb shell am broadcast ...` or wait for
   the backstop) and a launcher process restart (`adb shell am crash me.vibb.launcher.debug` is refused for a DO; use
   `adb shell am kill` while it is in the background, or a reboot): `always_on_vpn_lockdown` stays `1` the whole
   time on cellular, and every `Always-on:` line on cellular says `lockdown true`.
7. Filter off (Nettfilter off in the PWA) with Bare Vibb on: on Wi-Fi `Tunnel: pass` (no DNS filtering - a blocked
   domain loads), on cellular `Tunnel: sink`. Check the pass shape doesn't break name resolution for other apps on
   Wi-Fi (design 95's pass shape has no DNS server: Android must use the network's; **report if names fail**).
8. Settings › Network › VPN: the VPN's settings are disabled by the admin while any shape runs.

### C. The pause lifts it

1. Bare Vibb on, cellular, sink up. Start "Åpne for oppsett" (PWA or the phone's parent PIN): the sink goes
   (`Tunnel: filter` or the VPN stops when the filter is off), lockdown off, other apps get cellular, the VPN settings
   unlock. The Mobilnett page says "Opphevet mens telefonen er åpnet for oppsett".
2. End the pause: the sink comes back at once on cellular.

### D. The roaming lock (design 94)

1. Default "Av (låst)": `no_data_roaming` set; Settings › SIM › Roaming greyed out "controlled by admin";
   `gsm data roaming` + `gsm voice roaming`: no data comes up. The PWA's page: "Av og låst på telefonen", and the
   summary's header ends with "Roamer (US)" (or the country the image reads).
2. The lingering session: start a pause, turn roaming on in Settings, let data come up on the roaming network, end the
   pause. `no_data_roaming` is set again, `settings get global data_roaming` reads `0`, data stays up (Android's gap,
   §2.4.1) - and now **the sink holds it** (`Tunnel: sink`, reason `roaming` in the report's `mobile_data.sink`): the
   other app fails at once, the launcher still syncs. The PWA shows "Bare Vibb bruker roamingdata fra før låsen".
   `gsm data home` then `roaming` (a re-evaluation) tears the session down; the sink goes with it.
3. "Telefonens valg": the restriction is cleared and roaming is **not** turned on by us
   (`settings get global data_roaming` unchanged). The page says "Telefonens valg: av".
4. Single-SIM emulator: the relock (§2.4.2) isn't seen here; `adb logcat -s MobileNetwork` shows no "relocking" line.

### E. The mobile-network settings lock

1. Default on: `no_config_mobile_networks` set. Settings › Network & internet › SIMs is disabled "by the admin"; the
   Quick Settings Internet tile's dialog has no mobile-data toggle.
2. Start "Åpne for oppsett": both are back; turn **Mobildata off** in Settings. End the pause: the lock is back, data
   stays off. The PWA's summary shows "Mobildata er slått av på telefonen" and the Mobilnett row "Mobildata av"
   within a sync (on Wi-Fi). On cellular nothing reaches the server (true zero) until Wi-Fi.
3. Switch "Lås mobilnett-innstillingene" off in the PWA: the restriction goes at the next sync; on again: back.

### F. The update line

1. With a launcher from `main` (before this round) reporting: the Mobilnett page's choices are disabled with "Virker
   etter at Vibb One er oppdatert"; nothing is enforced on the phone.

### The run of 2026-11-13 (`jelly36`, branch `mobilnett` at 1d0d4a9c, after the code QA fixes)

Setup: `jelly36` wiped (a copy kept and put back afterwards), the debug launcher as Device Owner over adb, enrolled to
a local server of the same commit, the PWA driven with form posts as the signed-in admin. The other app was
`me.vibb.probe.other` (design 95's probe, let out with Allow these apps and ticked). Phase 1 on the stock image;
phase 2 (D, M2) with a private `-sysdir` whose `iccprofile_for_sim0.xml` reads `CIMI 242010000000000` and `EF_AD` MNC
length 2 (the AVD's own `modem_simulator/` copy is overwritten from the sysdir at every boot - edit the sysdir's), and
an APN for 24201 added from the shell (`content insert --uri content://telephony/carriers ...`, allowed to the shell
user; no device-owner override needed).

| Check | Result |
|---|---|
| A.1 filter on Wi-Fi | **Passed.** `Tunnel: filter`, lockdown 0; a device-blocked `example.org` fails `UnknownHostException` in 7 ms, `generate_204` loads |
| A.2 launcher outside the VPN | **Passed.** The VPN's `Uids: <{0-10216, 10218-...}>`, the launcher is 10217; syncs and reports go on |
| A.3 split-horizon | Not run (no custom DNS server on the test server) |
| B.2 the sink on cellular | **Passed.** `svc wifi disable` -> `Tunnel: sink` in 385 ms, lockdown on 19 ms later; the other app: IP literal `ConnectException` in 1 ms, a name `UnknownHostException` in 4 ms. Bytes in the gap not measured (QA 4) |
| B.3 the launcher works on the sink | **Passed.** A PWA save reached the phone and its report was stored within 1 s; the summary shows "Mobildata: bare Vibb" |
| B.4 SSE across shape switches | **Passed.** Four filter <-> sink switches on cellular (Bare Vibb off/on): the same stream thread, no reconnect, the server saw no new open. Note: `apply()` writes the sink's lockdown 4-30 ms before the service's tunnel (L2, fail closed) |
| B.5 back to Wi-Fi | **Passed.** Lockdown off first, then `Tunnel: filter`; the other app loads |
| B.6 lockdown never off while sinking / QA 2 (M1) | **Passed.** 20 Wi-Fi <-> cellular switches: every cellular read `lockdown=1 sink`, every Wi-Fi read `lockdown=0 filter`; no "Failed to establish VPN" (the 20 "read failed" lines are the old tunnels' loops ending); the launcher's threads 58 -> 57. A process kill while sinking: lockdown stayed 1 every second through the restart, `vpn_shape` sink, the other app refused |
| B.7 / QA 5 the pass shape's DNS | **Passed.** Filter off + Bare Vibb: `Tunnel: pass`, the VPN has `DnsAddresses: [ ]`, and the other app resolves names (`generate_204` 204, `example.org` 200 - unfiltered, as the filter is off). Wi-Fi off: sink at once, lockdown on |
| B.8 VPN settings locked | **Passed.** Settings › VPN: "Blocked by work policy" |
| QA 6 (L1) hardening "Blokker VPN-innstillinger" off, sink up | **Passed - Android protects it anyway.** Without `no_config_vpn` the VPN page opens, but "Always-on VPN" and "Block connections without VPN" read "Controlled by admin" and a tap answers "Blocked by work policy": a user can't change a device owner's always-on or lockdown on Android 16 |
| C.1 / C.2 the pause | **Passed.** Åpne for oppsett from the PWA: `Always-on: none, lockdown false (none)` (QA 8: the `NONE` write succeeds, no "Couldn't set always-on"), the other app gets cellular, `no_data_roaming` and `no_config_mobile_networks` lifted, the page says "Opphevet mens telefonen er åpnet for oppsett" / "Låst opp ...". Ending it: lockdown and the sink back within 1 s. (`no_config_vpn` stays: it was the hardening switch's, which only the server lifts) |
| QA 1 (H1) reboot with no network | **Passed.** Bare Vibb, Wi-Fi off, `svc data disable`, reboot: from the first read after boot `lockdown=1`, `vpn_shape=sink`, `Tunnel: sink`. `svc data enable`: the other app's first five requests (one a second from the moment data came on) were all refused in 1-4 ms. Airplane mode + a process kill + airplane off: the same, 6 of 6 refused |
| D.1 roaming locked | **Passed.** `gsm data roaming`: data goes down and stays down, `data_roaming` 0; the report `roaming: true, country: "us", network: "311740"`; the summary's header ends "Roaming (US)" in orange |
| D.2 the lingering session | **Passed.** In a pause `data_roaming 1`, roaming data up; ending the pause: `data_roaming` 0 again, the roaming network stays up (Android's gap) and the sink holds it with Bare Vibb off (`mobile_data.sink: "roaming"`, `data_active: true`): the other app refused, the launcher reports. The page: "Bare Vibb bruker roamingdata fra før låsen". `gsm data home` -> `roaming` tears the session down |
| QA 3 (M2) | **Passed.** Filter off, Bare Vibb off, the lock on, the lingering session up: Wi-Fi on -> `Tunnel: pass` (the cellular session stays up in the background); Wi-Fi off -> `Tunnel: sink`, lockdown 1, the other app refused on the lingering network |
| D.3 Telefonens valg | **Passed.** The restriction goes, `data_roaming` stays 0 (nothing turned on), the page says "Telefonens valg: av" |
| QA 7 (L6) | **Passed.** Telefonens valg, then Av and the page read at once: "Ikke på plass på telefonen ennå..." (`pending`), never "Låst opp mens ..."; after the sync "Av og låst på telefonen" |
| E.1 the settings lock | **Passed.** Network & internet: "SIMs - Controlled by admin"; the Internet panel has no mobile-data toggle (`mobile_toggle` absent) |
| E.2 data off, true zero | **Passed.** During the pause the toggle is back; "Turn off mobile data"; the pause ended: the lock is back, `mobile_data` 0, on Wi-Fi off "Active default network: none" and the other app gets nothing. The report `enabled: false`; the summary "Mobildata er slått av på telefonen" and the row "Only Vibb · Mobile data off" |
| E.3 the switch | **Passed.** Off: `no_config_mobile_networks` gone at the sync; on: back |
| F the update line | Not run (no older launcher installed; the page tests cover it) |

**Found, open (not caused by the shapes, but felt more with them):** after the network the SSE stream ran on went
away (cellular data switched off, or the roaming session torn down), the phone kept the dead stream until its 5-minute
read timeout: twice the PWA's nudges were not heard for ~5 minutes although Wi-Fi was up and the server had a stream
"open". Reports went on at syncs. Hypothesis: on the emulator the cellular interface keeps its address when data goes
off, so netd destroys no sockets; on a phone the address goes and the socket with it - **[needs device test]**. Either
way the stream should reconnect on a change of the default network (design 95 §3.5's ping interval, 95-T1's
`ServerClients`). Once (D.1) the sink also held ~55 s after Wi-Fi came back before the watcher saw Wi-Fi as best -
fail closed, but worth a look with the stream fix.

### What `jelly36` can't show

The Jelly Star's dual-SIM relock (design 94 §2.4.2), VoLTE/MMS/Wi-Fi calling under lockdown and a real locate indoors
with the sink (design 95 §2.4): [needs device test].

## 7. Reporting back

For anything odd, paste into the chat:

```sh
adb logcat -d -t 2000 | grep -iE "kidslauncher|vibb|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|CommandListener|PlayRuntime|Backstop|AppEnforcer|EmergencyDialer|LockTask|PinLock|UpdateFence|SelfUpdate|NotificationRule|CameraLock" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.
