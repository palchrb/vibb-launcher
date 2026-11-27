# Testing on the Android emulator (Debian VM)

For a Pixel 7 / Android 16 AVD resized to the Jelly Star screen. Uses the **debug**
build (package `me.vibb.launcher.debug`, tsnet stub) and a local server. Never put
a debug APK on the real phone.

The recipe is §1-§6; each §6x after it is one feature's checks. The results of run gates are in
`docs/testing/emulator-runs-archive.md`: such a section keeps its heading here, with a line saying so, so every §
number still finds its place. Rows not run or owed, here or in the archive, are listed once under "Owed rows" (after
§6r).

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
user's `jellystar2`, 2026-10-23). (This premise needs a second look: the Jelly Star has capacitive keys on the bezel and
no navigation bar in the app's window - `jellykeys36` below.) **On your
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

**`jellykeys36`: the Jelly Star's window, keys and no navigation bar** (the Jelly Star's layout, 2026-11-20). The real
Jelly Star's Back/Home/Recents are capacitive keys on the bezel below the panel, so Vibb's windows get no bottom
`navigationBars` and no `tappableElement` inset: the window's bottom edge is the panel's. `jelly36` has a 24 dp gesture
inset or a 48 dp 3-button bar, so check bottom-edge layout (Home's last row, the glance's Nødsamtale, anything at the
bottom) on this one too. Make it as a copy of `jelly36`'s `config.ini` with `hw.mainKeys = yes` - **never edit
`jelly36` itself** - and boot it fresh:

```sh
mkdir -p ~/.android/avd/jellykeys36.avd
sed 's/^hw.mainKeys = no$/hw.mainKeys = yes/' ~/.android/avd/jelly36.avd/config.ini > ~/.android/avd/jellykeys36.avd/config.ini
sed 's/jelly36/jellykeys36/g' ~/.android/avd/jelly36.ini > ~/.android/avd/jellykeys36.ini
emulator -avd jellykeys36 -port 5560 -wipe-data -no-window -no-audio -gpu swiftshader_indirect -no-snapshot-save -no-boot-anim
# hw.mainKeys = yes alone is not enough on the Android 16 Pixel image: the Pixel launcher's taskbar hosts the bar
# (dumpsys window: mNavigationBar=Window{... Taskbar}, a 36 px gesture inset, 72 px in 3-button mode). Turn it off
# (before provisioning; Vibb is Home, so nothing else needs it) and the bar is gone at once:
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
adb shell dumpsys window | grep -E 'type=(navigationBars|tappableElement) frame'   # only the status bar's top rows
```

Then provision as in §4. With the bar gone, back is `adb shell input keyevent BACK` (the AVD's hardware keys). The
glance's log line then reads `(pad 32/32)`: 8 dp + the handle's 24 dp at the bottom (`glanceBottomRoom`), Nødsamtale's
centre 84 px over the edge as with `jelly36`'s gestures (its 24 dp inset is the handle's view, so nothing is added
there; a gesture inset lower than 24 dp gets the rest). To compare with `jelly36` on the same data, `pm enable` the Pixel launcher,
stop the emulator, set `hw.mainKeys = no` in the copy and cold boot it, then `adb reboot` once (the bar comes back only
with a fresh SystemUI); the copy is then `jelly36` with this phone's data. Remember to set it back to `yes`.

**Faster gates: snapshots** (run 2026-11-25/26). A gate whose rows don't each need a wiped phone provisions once and
loads a snapshot per row instead of `-wipe-data` and §4 again. Start the AVD as above (`-wipe-data -no-snapshot-save`;
saving by hand still works, the AVDs' `<temp>` data partition included, within that emulator session), then:

```sh
adb -s emulator-5556 emu avd snapshot save gate-do      # Device Owner, not enrolled (Koble til up): 3-5 s, ~2.4 GB
# enrol, finish the setup flow, set the PINs, then
adb -s emulator-5556 emu avd snapshot save gate-base    # enrolled with its key, policy applied
# the server's data is part of the state: copy it with each save, put it back with each load
kill <server pid>; cp -a srv/data srv-data-gate-base; <start the server again>
# a row from the saved phone:
adb -s emulator-5556 emu avd snapshot load gate-base    # 3 s; the guest clock is right at once
adb reconnect offline                                    # adb can stay "offline" after a load
adb -s emulator-5556 emu avd snapshot delete gate-base  # at the end - each one is ~2.5 GB of the tight disk
```

Measured: a load 3 s against ~3.5-4 min for a wipe, §4's provisioning, the Koble til enrolment and the setup flow (boot
32-85 s alone); the run of 2026-11-25/26 loaded six times (§6bp's rows after the control and row 13 on both images, a
second pass on 16, §6bs's start on 14), about 20-25 minutes saved, and no row needed a second wiped image. A row that rewrites the server's view of
the phone (a new key, a re-enrolment) runs last from a snapshot, or the server's data goes back with the load. Before a
save that later rows drive without adb (§6bp's cut closes adb), set what the console can't do later: 3-button navigation
(`adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton`): the console's
touches work, but its key events (`event send EV_KEY:...`) and the gesture navigation's swipes never reached
`jelly36`'s screen, while the navigation bar's Back is a touch. `event text` types, `:`, `/` and `.` included (send a URL in pieces).

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
# Design 139: the app groups' "App usage data" (GET_USAGE_STATS), a device owner can't grant it itself. On a phone the
# parent turns it on in the setup flow's step `usage_access` during a pause; adb is only the emulator's shortcut.
adb shell appops set $P GET_USAGE_STATS allow
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
Jelly Star and stock GMS phones; adb above stays the fallback. On a GMS phone the provisioning runs
through the role holder (CloudDPC) and the admin extras reach only `ADMIN_POLICY_COMPLIANCE`
(2026-11-20, `docs/notes/launcher/hardening.md`, "Provisioning/language"): in a QR test's
`adb logcat -s Provisioning`, look for `Admin policy compliance: admin extras found`, then
`applying admin extras` and `admin extras applied - enrolled` (or `enrolment failed ... - tried again
at the next sync`, then `Retry: ...` at the next sync run); a second delivery logs `skipped (already
applied)`.

**Design 132: while Vibb One holds the password quality NUMERIC (a managed phone whose Android PIN Vibb set) `adb shell locksettings clear --old <PIN>` is refused ("New credential doesn't satisfy admin policies"); every clear step below needs the phone unmanaged first (the apply then writes UNSPECIFIED) - design 132 §11, G12b.**

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
#   -> $PKG/com.kidslauncher.mdm.calls.PhoneBookEntryActivity (PhoneBookActivity before 2026-11-16, §6av;
#      also on the Jelly Star: a privileged dialer
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

**Design 132 (phase D): the own keypad lock is gone - the lock part of this section is history; Android's lock is the
only lock (design 132 §11, the gate G1-G12). The call screens below still apply.**

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
#   "storage_limit_gb": 2} - the two switches and the limit in GB (0 = none)
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
- **Spotify on a fake room** (design 54 round 54-a2, its R5): the debug feed's Spotify playlist from
  `python3 -I scripts/fake-spotify.py --print-listing 6 > debug-feed/items/6.json` (§6l A2), the speakers from
  `python3 -I scripts/fake-sonos.py --track-seconds 20`, and for the hand-over to the phone `fake-spotify.py
  --track-seconds 20`. Then R5's steps: (1) the transfer - the fake logs `SetPlayMode NORMAL`, `RemoveAllTracksFromQueue`,
  `AddURIToQueue` (2311), `Browse Q:0`, the queue uri, `Seek TRACK_NR`, `Play` (and `ConfigureSleepTimer` on a managed
  phone with a rule ahead); (2) five Nexts in 2 s are one `Seek TRACK_NR`, no add; `--script "t+30 next"` is followed
  with no done mark, the queue's own advance at 20 s is followed and marks the track done; (3) `--queue-extra` (the
  speaker's title, no bookmark), `--queue-gap` (a step skips it, a pick says "Kan ikke spilles på Sonos"),
  `--queue-reversed` (corrected at each natural boundary), `--script "t+40 queue-insert"` (the next press transfers);
  (4) repeat off ends, repeat all wraps by a jump to item 1, repeat one seeks to 0, "Til slutten" pauses before the
  boundary; (5) `--refuse-share` and `--empty-share` say "Kan ikke spilles på Sonos – spør en voksen" and the phone,
  paused, an artist says "Kan ikke spilles på Sonos"; (6) the web player <-> Stua at the same track and second, Stua ->
  Kjøkken, a start 1 s before a track's end, `am force-stop` then open (adopted, no command in the fake's log, the next
  press a jump), favourites with two Spotify hearts and a podcast (one share link per heart); (7) bedtime on Android 14
  and 17 (the debug launcher as Device Owner, a rule 3 minutes ahead without the music app among its exempt apps):
  `ConfigureSleepTimer` about 0:03:00 at the transfer, at the edge Stop, the queue uri, the clear and
  `ConfigureSleepTimer ""`; with `adb shell svc wifi disable` 30 s before it the fake's timer pauses Stua and after
  `svc wifi enable` `sonos-park-retry` parks within 5 minutes (whether a suspended package's WorkManager work runs);
  (8) context mode, once round 54-a2' is in. The fake has no socket: the `itemId` recheck, the container and the
  metadata title are the JVM tests' and the real room's.
- Real speakers stay a device check (design 26a, "Device checks"): SSDP, discovery and control with the launcher's VPN
  filter on (no `EPERM`), the NRK service, Doze.

## 6i. Vibb music with Storytel (design 26b): the fake Storytel

No real account is used on the emulator: `scripts/fake-storytel.py` on the VM is the login and the bookshelf (the
server's, §6i B), the server's session route for the phone alone (`/fake/jwt`), the asset call (the 302 the app
captures) and a CDN serving silent WAV books with Range. Its answers come from the fixtures the tests read (the login
and the bookshelf from `server/testdata/storytel/`, `/fake/jwt` from `contracts/music/storytel_token_answers.json`, the
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
cp contracts/music/music_library.json debug-feed/library.json     # entry 5 "Kokosbananas" is storytel:series:26175
python3 -I scripts/fake-storytel.py --print-listing 5 > debug-feed/items/5.json
echo '{"storytel_base": "http://10.0.2.2:8730"}' > debug-feed/config.json
R=me.vibb.music.debug/me.vibb.music.debugfeed.DebugFeedReceiver
adb push debug-feed /sdcard/Android/data/me.vibb.music.debug/files/
adb shell am broadcast -n $R        # "storytel: http://10.0.2.2:8730"
adb logcat -s VibbMusicDebugFeed WM-WorkerWrapper ExoPlayerImpl
adb shell run-as me.vibb.music.debug cat files/music/report.json     # "storytel", "downloads", "item_errors"
```

The session lives in the process's memory: `adb shell am force-stop
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

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (history since design 132; its checks are gone).

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

Every build uses the SSE stream, and About This Phone says "Online
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

## Owed rows

Every row in this file and in `docs/testing/emulator-runs-archive.md` that is not run, or owed to another image or the
phone, one line each - the one place they are tracked. A gate that runs one records it in its own section and deletes
the line here; a gate that leaves a row open adds it here. Sections that are written and not run as a whole are listed
once. Procedure sections whose runs are in `docs/testing/*-run.md` (§6c-§6t and the like) are not itemised here.

The usual reasons: **Android 17** - no API 37 image runs here (37.0 and 37.2 abort in gfxstream on any frame readback -
screencap, region sampling, task snapshots - with every `-gpu` mode and emulator 37.2.13 or the 37.3/37.4 canaries;
archive §6ar, §6as); **Jelly Star** - needs the phone (Android 16) or its SIM, radio or hardware; **QR** - no camera QR
on this VM; **unmanaged** - a managed phone's apps can't be made unmanaged from the PWA (archive §6bq row 13).
`compat34` after an `adb emu kill` hangs at boot: start it with `-wipe-data` (archive §6as).

Written, not run as a whole:
- §6g - the launcher's security wave - never run (written without an emulator; run it from a snapshot, the wipe is real).
- §6aj - the kid's look step 2 (`kid-look-3`) - never run; its Jelly Star half: step 0's recording, a real SIM's MMS.
- §6az - Innstillinger as a task of its own (design 111) - never run.
- §6bk - Apple's cell lookup (design 125b) - never run; its §9 rows (neighbours, NR, a second SIM, Apple's answer) are
  the Jelly Star's with a SIM.
- §6bz - Storytel books sealed on the phone (design 117) - never run.

Rows:
- §6: the row-count change - an untick uninstalls the app.
- §6m checks 9, 11, 16, 17 (E3, recorded under §6u): `chrome://policy` and the browser screens - Jelly Star (Chrome
  and Fennec draw nothing under swiftshader).
- §6m L2: READ_PHONE_STATE - needs WhatsApp on a phone.
- §6u steps 2-4, 9-10: the connecting line on the fake room - the fake room needs SSDP from the emulator.
- §6u step 17: a cold start after a real kill - a media foreground service survives `am kill`.
- §6v (design 68) step 0: the app-op probe's repeat - Jelly Star.
- §6v (design 68): the camera change waiting for a live call's end, and its wake - Jelly Star.
- §6v (design 68): a throwing access read shown as `unknown` - Jelly Star.
- §6v (SMS) step 3b: a carrier's MMSC and push, the platform's byte stream, HEIF/AVIF, the parse service's death -
  Jelly Star (step 0).
- §6v (SMS) design 83: the SIM's operator changing - the AVD can't change it at run time (tests stand).
- §6v (SMS) design 83: the allow's short form - the server's tests only, not seen on an emulator.
- §6v (SMS) design 79: TalkBack's custom actions on the row - not checked.
- §6v (SMS) design 79: the fling through 50 rows (§9's pocket-call check) - Jelly Star.
- §6v (SMS) design 79: an incoming call before the first unlock - the AVD had no Android lock.
- §6ab: a portrait picture's tall bubble - the fake MMSC's picture is 160 x 120.
- §6ab: the thread and the phone book with the keyboard up - `handy36`'s 136 px bar; `jelly36` or the phone.
- §6ab: the send-while-coasting miss on the build before the fix - not run (the control).
- §6ad L1: a failed engine read dropping the lock's rows - no way to make the read fail.
- §6ad: the Recents label after a reset - not run.
- §6ad: TalkBack by touch and the lock row's action label - not run.
- §6ad: the notification and the buzz as one or two, the buzz from the background, Android's vibration settings -
  Jelly Star.
- §6ad: the buzz for another app's row (Element X's) - not run.
- §6ah B.20: kid Settings' row jumping back - the refused change can't be forced by hand.
- §6ah: a Recents card after a reboot, and its tap after a kill under the kiosk - no page task survives a boot, no card.
- §6ah: finding 2 on main, and the control on main on a wiped AVD - not run.
- §6ah A: the status-bar icon frame counts, the theme and Battery Saver flips under an open page - not run.
- §6ah B.7's reboot within seconds, B.8, B.10, B.11, B.13-B.15 as written, on `kid-look-2` - not run.
- §6ah C-F on `kid-look-2` - not run (C-F ran on `kid-look-1`).
- §6ah E: TalkBack itself - the kiosk blocks it ("App is not available").
- §6ah F: Android's "make more apps dark" switch - the image has none.
- §6ah: L4's death between the write and the removal, L5 - not run.
- §6ah: the phone book through a `tel:` link in another app - not run (a shell `DIAL` stood in).
- §6ah: the channels' fix - not run.
- §6ah F: the release build's row - needs CI's release APK on its own emulator.
- §6ah: the Jelly Star's build (step 0's repeat), a finger's tap, an Android 14 phone's bars under Lys - Jelly Star.
- §6ai: the `.call` task after a process kill - no call placed.
- §6ai: a crash of the launcher - no hook to force one.
- §6ai: a release build; a phone - not run.
- §6ak A.3: split-horizon DNS - no custom DNS server on the test server.
- §6ak B.2: the bytes in the shape gap (QA 4) - not measured.
- §6ak F: the update line - no older launcher installed (the page tests cover it).
- §6ak: the dead SSE stream after its network goes, and the ~55 s sink hold - needs a device test.
- §6ak: the dual-SIM relock, VoLTE/MMS/Wi-Fi calling under lockdown, a real indoor locate with the sink - Jelly Star.
- §6al 4: Android's roaming switch after a SIM reload - the shell has no SIM power or modem restart.
- §6al D6: the per-country rule with a second country - the modem's operator is fixed (tests stand).
- §6am 2b: the power key during the route - not run.
- §6am 2c: a Settings activity started in the window gone after step 7 - a device check (design 98 §12).
- §6am 4: Wi-Fi in airplane mode, the deadline path - not run.
- §6am 6 (Bluetooth), 7 (Gboard's mic) - not run.
- §6am 8: a real kill inside the 0.3 s window - not run.
- §6am 14: "Avslutt nå" before the phone's next policy (the `adopt` rows) - not run.
- §6am addendum: Android's "Wi-Fi stays on in airplane mode" notification - the clean AVD has no `wifi_apm_state`.
- §6am addendum: a Settings-bound notification during the close - the shell can't post one.
- §6an 1: three Meldinger threads under a call - the test SMS posted no notification.
- §6an 2: the Mer tid and Musikk taps - no budget and no music session on that phone.
- §6an 4: the panel over Vibb's own PIN lock - no Child PIN then (Vibb's own lock is gone since phase D).
- §6an 7: "Vis i varsellisten igjen" - a role holder's channels are locked; needs a phone without the roles.
- §6an 10: the panel on the Jelly Star (§10 test 10) - Jelly Star.
- §6ao 7: hiding in its full form (Q100-L9) - a phone without the dialer and SMS roles.
- §6ao 8: D6 with a Child PIN and one app row, TalkBack, 3-button navigation, the screen in en - not run.
- §6ao: the swap and sorting on its SystemUI (M1-M3), the header's gear, the time line's face and heights, the
  deferred plain notice at boot, the screen's block by a child's finger - Jelly Star.
- §6ap E3: Bare Vibb's sink in the window, and the no-compiled-list case - not run (the AVD has a SIM; the code pins the
  second).
- §6ap E10: the reopen's next-day half - not run.
- §6ap E12: Cancel keeping Android's notification - not on this image (`AUTO_CANCEL`); Jelly Star's build.
- §6ap E13: a second hotspot with another SSID - one access point on the AVD.
- §6aq E8: the failed write (`failed`/`package`) - the server always sends Chrome (`LoginBrowserTest` stands).
- §6aq E9 / D-C3: `chrome://policy` with a test bundle without `chrome://*` - Jelly Star.
- §6aq D-C1, D-C2, D-C4-D-C6: real portals, "Add to Home screen", a real Feide/Google login back to its app, the
  presets' hosts, "Open in Chrome browser" - Jelly Star.
- §6aq: the Bare Vibb sink with a SIM - Jelly Star.
- §6ar on 17: History (kiosk), the pause and Flymodus on a light wallpaper - Android 17.
- §6au: the rings and the row's buttons on Android 16 (steps 2-6) - ran on 14 only (`handy36` without a ring).
- §6au: the bar under the row (the pills moved) - not seen on an emulator.
- §6au step 3: the ring in Doze - `deviceidle force-idle` stays INACTIVE on `compat34`.
- §6au - Android 17.
- §6av on 14: the update from an older build's pin - a fresh install only.
- §6ax: a long-press on a Meldinger notice saying "can't be modified" with Vibb as the dialer - Jelly Star.
- §6ax - Android 17.
- §6ay 2b: the app's own encrypted test - needs a trusted CA.
- §6ay 12: a second instance - not run.
- §6ay: Element X's connector taking our NEW_ENDPOINT - Jelly Star.
- §6ay - Android 17.
- §6ba 8: app push held abroad - needs §6ak D's private `-sysdir` with the roaming SIM.
- §6ba: Element X's push held - Jelly Star.
- §6ba - Android 17.
- §6bb: the flow's SIM page first on Android 15 - no API 35 image run.
- §6bb 9d: an app from the PWA in the hold - no catalog app on the offline rig.
- §6bb: the 6-tap QR into the hold and the wizard on the phone (design 114 §11) - Jelly Star.
- §6bc: Android 15 - no API 35 image run.
- §6bc: the `filter_down` push on a subscribed browser - the rig has no push subscriber.
- §6bc: the alarm's Doze recovery on 16 - the emulator's Handler won there.
- §6bc - Jelly Star; Android 17.
- §6bd: a real bond and Android's real pairing dialog - Jelly Star with the Jabra.
- §6bd: the arm during a pause - not run (`BtPairingPlanTest`).
- §6bd: Android 14 - not run.
- §6bd - Android 17.
- §6be 2 on 17: Home, Recents and Back in gesture and 3-button navigation - Android 17 (readback kills
  system_server).
- §6be 6 on 16: a third-party listener granted in a pause - not run.
- §6bf (k): a server whose certificate isn't in the built-in set - needs such an https server (`clock_rules.json`
  stands).
- §6bf (f): the checked time's step - the emulator's GNSS follows the guest's clock.
- §6bf - Android 17 (on a device).
- §6bg: Android's "Unknown" switch on Telecom's page - only the system may write it.
- §6bg - Android 17 (system_server restarts at `set-device-owner`).
- §6bh: the hanging handshake on 14 - the image's kernel has no `connbytes`.
- §6bi: the PWA's row from an upgraded database - a migration (`cargo test`).
- §6bi: Lyd on Silent, on 14 - no way to Silent from the shell.
- §6bi: a time rule's silence, on 16 - not run.
- §6bi: Vibrer on Normal, on 14 - not run.
- §6bi: the sound from the lock and its loudness, a custom sound on storage - Jelly Star.
- §6bi - Android 17.
- §6bj: the install over a pre-L1 build - not run.
- §6bj: a key made in the SINK shape with an empty pool - no roaming image, and the pool can't be emptied.
- §6bj: findings 1-3's fixes (`hw-key-gate-fixes`) on the emulator - unit-tested only.
- §6bl 8 on 16: the SOS gesture after a plug-in - the image's SOS app crashes at launch.
- §6bl 5e', D2, D3 on 14 - no Android face on that phone in the final round.
- §6bl: a real plug-in's own wake on USB and Unihertz's `ChargingDialog` - Jelly Star.
- §6bm: the confirm with a passkey - no browser with a passkey on the VM.
- §6bm: the no-key phone's own side - the attestation-key pool can't be emptied.
- §6bm: process death while waiting on a QR enrolment - QR.
- §6bm X: a phone enrolled over 0.93.0's data - no 0.93.0 data.
- §6bm: an enrolled phone from before this build - needs an L1 launcher and an older server.
- §6bp 1: the setup QR with `tailscale_auth_key` - QR.
- §6bp 2: the tailnet join - the debug build's tsnet is a stub.
- §6bp 14 on 16: the subscription's half - needs root to name the calendar by a URL.
- §6bq 7-12 on 16: the provisioning baseline - no root on `jelly36`, `aosp36` already enrolled.
- §6bq 11 on 14: a PIN set before the enrolment - it would spend the image's escrow token.
- §6bq 13: the baseline lifted when unmanaged - unmanaged.
- §6bq: the baseline through a real QR - QR.
- §6br a3: a saved copy with no app list on an unmanaged phone - unmanaged (`PolicyGateTest`).
- §6br - Android 17.
- §6bs j: the cell clause (`mismatch clause=cell`) - no fake Apple answer (§6bk); Jelly Star.
- §6bs: real room levels and neighbours, the HAL in deep Doze, the real scan throttle, a real motion sensor's delay -
  Jelly Star (§9).
- §6bt G10: VoIP over the locked glance, and VoIP with Android not locked - no VoIP app on the image.
- §6bt G8: the crash guard's three crashes - `am crash` is refused for the Device Owner.
- §6bt: the re-front's and the RELOCK backstop's timings - not run.
- §6bt: the kiosk with a live call - not run.
- §6bt G12b: the `ownedGone` path (Android's PIN removed) - unmanaged.
- §6bt J1: Identity Check on, `set_kid_pin` and the kid's own PIN change - Jelly Star.
- §6bv: §6bp row 10's `dumpsys device_policy`, row 11's phone-side key book, 12b's `Enrolment restored` line - adb is
  closed by the cut.
- §6bv: R1 and R3 on `aosp36`, R2 on `jelly36` - each ran on the other Android 16 image.
- §6bv - Android 17.
- §6bw d, e on `0a00aa512` - not run.
- §6bw k: the cell veto - no placed cell 5 km off (no fake Apple answer, §6bk).
- §6bw o on 14: the measured 15-minute off gap - not measured (§6by finding 1).
- §6bw p on 14: a coarse fix that isn't surely outside decides nothing (passed on 16 2026-11-27, §6cb; 14 waits for a
  run - the cell needs three trusted occasions 30 min apart in real time).
- §6bw o, p and the device row (the redacted BSSID's background read) - Jelly Star.
- §6bw - Android 17.
- §6bx: the relearning on the phone - Jelly Star (§6by).
- §6cd/§6ce 13 on 14 and 16: the kill rule's 2 s fallback (design 139 revision 6, branch `app-groups-gatefix`) - re-run
  owed (Z's process gone first, `kill -9` and `am crash`); on 14 also the control of design 139 §8 row 13: X moves to
  a second activity inside itself, then split screen with another app within 2 s - X keeps counting.
- §6cd/§6ce 18 on 14 and 16: the 30 s overlap (revision 6) - re-run owed; pause starts and a reboot, the debug
  `late event` line now means an event older than the overlap.
- §6cd/§6ce 6 on 14 and 16: the setup hold's last stretch (revision 6, counted from the hold's end) - re-run owed.
- §6cd/§6ce 15: moot, not owed - it can't occur on a Vibb phone: the maximum time to lock is the screen timeout
  (`lock/KeyguardCover.kt:39`), so the keyguard shows at every timeout; the fold's rule stays unit-tested.

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

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

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
5. **Spotify on the room** (design 54 round a2, its R5 steps 1-8 below §6h): a Spotify tile with Stua chosen shows the
   same line, and `VibbHandover` logs the share-link steps (`SetPlayMode NORMAL answered`, `the ShareLink request
   sent/answered`, `the start item's page read`, `Seek TRACK_NR answered`, `the speaker's sleep timer set or
   cleared`) from `SonosTransport.playShare`.

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

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6y. Calendar links: a link pasted in the PWA, the phone's own calendar of it (design 72 step 1)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

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
serve `contracts/core/weather.json`'s `answer` / `answer_words` from a throwaway endpoint as the phone's
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
PBKDF2-HMAC-SHA256, 210,000 rounds, 4 digits - the own lock, no Android credential; design 132: that lock is gone). The emulator was started under
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

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6ab. A thread follows its newest message, the phone book on the night ground (2026-11-10)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6ac. A thread's "Ny melding" mark (2026-11-10)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6ad. Meldinger as an app: its Apps row, the lock level and the buzz (design 82) (2026-11-10)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6ae. A message's time under the last bubble of its run (2026-11-10)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6af. The buzz for a second message, and the pause that hung on "App is not available" (2026-11-10)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6ag. A failed picture's retry, and the compose bar with a picture over the keyboard (2026-11-10)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6ah. The kid's look: Mørk or Lys - step 1's pass (design 78)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6ai. Home stays the lock task's root after a process kill (kiosk-root, 2026-11-11)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

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

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6al. Mobilnett revision 4: "I utlandet", the roaming notice, the kid's switch (design 97) (2026-11-14)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6am. Flymodus through the Digital assistant role, the rule screen's sheet, Foreldrekode (design 98) (2026-11-14)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6an. Snarveier, the kid's quick panel in the shade (design 99) (2026-11-15)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6ao. One Vibb notification, and the request for more time answered (design 100) (2026-11-15)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6ap. Wi-Fi sign-in: the window, the parent code, the reopen (design 102 Part A)

Owed: design 102 §11's E1-E7 and E10-E13 (E8-E9 are Part B, §6aq). Setup: **`jelly36`** restored clean (the sums, as
§6an), the branch's debug launcher as Device Owner with a kiosk, a server of the branch with a parent code set on the
phone, the phone enrolled; mobile data off. The fake portal on the VM: `python3 -I scripts/fake-portal.py --mode portal
--port 8105` (`--selftest` first); point Android's probe at it with `settings put global captive_portal_http_url
http://10-0-2-2.nip.io:8105/generate_204` and the same for `captive_portal_https_url` and `captive_portal_fallback_url`,
`captive_portal_use_https 0` (adb shortcuts for the rig only; the kid's ways in need none). **Always through a hostname**
(`nip.io`, the other site `sslip.io`), never `10.0.2.2`: the window's own lookup on the bound network is part of the
check. Wi-Fi off and on (`svc wifi`) makes NetworkMonitor probe again; `curl http://10-0-2-2.nip.io:8105/reset` forgets
an Accept. Logs: `adb logcat -s PortalWindow PortalRuntime ActivityTaskManager ConnectivityService NetworkMonitor`.

| Check | What to see |
|---|---|
| E1 | The notification ("Sign in to Wi-Fi network", folded in the Silent bundle) under the kiosk opens Vibb's entry (`cmp=…/com.kidslauncher.mdm.portal.CaptivePortalEntry`, no violation): "Sjekker nettet …", the keypad, then the window in `:portal` (`ps -A | grep portal`). Accept closes it within ~2 s; the next report's `captive_portal.sessions` has `validated`, `approved: "code"` |
| E2 | `resolveActivity` names the entry; a second ordinary app's `startActivity` of the entry gets "Permission Denial … requires android.permission.NETWORK_STACK" (not the shell) |
| E3 | The first page waits for the filter's lists (code QA M1); a phone with no compiled list refuses every page. Vibb's real filter on, and Bare Vibb's sink on a SIM-less AVD: the portal loads by hostname in the window; a listed host (add the other site to Blocked domains) is "Denne siden er sperret" and counted |
| E4 | `--mode soft` and `--mode hotspot`: the window closes as `passes` within 5 s (Vibb's check). `--mode walled`: does **not** close early - needs `cp.cloudflare.com` unreachable from the emulator (block it in the VM's resolver for the run); without that rig the rule rests on `login_rules.json`'s `passes` rows |
| E5 | `--mode meta` and `--mode script` reach the login page; a tap on "Another site" and on `target=_blank` is refused ("Denne lenken går ut av innloggingssiden"); on the other site, `/away` (a leaf's redirect onward) is refused; `--mode iframe`: the frame shows our blocked page and the session's `blocked_frames` names it (a `wifi_signin_wide` event in the PWA); `--mode redirector`: the tapped `/out?to=` 302 to the other site is refused; `--mode formpost`: the POST's 302 to the other site is allowed and a tap back there works (a portal site). Log whether `hasGesture()` rides a tapped link's and a POST's redirect hops (R7). `--mode leafpost` (code QA): the login page refreshes itself to the other site's `/partner` (a leaf); its POSTs that 302 and 307 to example.com must not leave - record what the window does with each (a 307 may reach neither hook) |
| E6 | Advanced's "Krev foreldrekode" off: openings without the keypad, a fifth and more too (no daily count since 2026-11-18); with it on, "Gi én innlogging til nå" in the PWA lets one through after the sync, each tap one more |
| E7 | The binderless start from the Wi-Fi page's "Logg på" and Home's line: the window loads Android's probe URL and the portal's redirect; note how often `reportNetworkConnectivity` re-probes (the rate limit) |
| E10 | After a `validated` sign-in the Wi-Fi page offers "Åpne påloggingssiden" on that network and not on another; it opens the recorded page, a tap to the other site is refused, it stays open on a working network, asks the code (code on); the row is gone the next day (set the date) and after a Wi-Fi with no sign-in |
| E11 | Cleartext allowed in both configs: Settings' server address `http://example.org` is still refused (the vectors on the device), and the sync never connects to it |
| E12 | The keypad: a wrong code counts in the shared lockout (Settings' gate shows the same tries left), a right one opens this opening only; a second sign-in asks again; no parent code on the server: only a grant opens; the requirement off on Avansert opens without the keypad; Cancel keeps Android's notification; the PWA shows the `wifi_signin` PIN events |
| E13 | After a reboot the reopen's row is gone; on a second AVD hotspot with another SSID it doesn't show |

### Run (2026-11-15)

Setup: **`jelly36`** restored clean from `~/kid-look-1-avd-pre-start` (22 of 22 sums, the same file list), started under
`flock ~/build.lock` and `flock -o ~/emulator.lock` without `-wipe-data`, the branch's debug launcher (a5d7e521a, then
ecb5f5cfa and e20a3fa8f) as Device Owner (§4's adb lines), a server of the branch on design 100's data (migration 0150; the phone
renamed to a neutral name), enrolled with a typed code; parent code a 6-digit test value set in the PWA; parents
"Pappa" and "Mamma". Mobile data off. The fake portal as above, always by hostname. The emulator died once mid-run (no error in its log) and was
started again on the same state for the fixes' builds. Afterwards `jelly36` restored again (22 of 22 sums, the same file
list).

**The rig's https.** The emulator's Wi-Fi has real internet, so Vibb's https check (Google's and Cloudflare's 204 on
the bound network) passes and every sign-in is refused as "Nettet virker allerede". The emulator ran with
`-http-proxy http://127.0.0.1:3128` and a scratchpad proxy (not in the repo) playing the captive network: until the fake
portal's Accept it refuses every port-443 CONNECT and answers every port-80 request with a 302 to the login page, as a
real portal does (`captive`); `walled` refuses only Cloudflare's addresses; `hold` refuses 443 even after Accept (to keep
a window open after a form post); `open` passes everything. Connections to 10.0.2.2 are always passed. The window's
screenshots are the console's (`adb emu screenrecord screenshot`); the keypad is a secure window. Logs as above, with
`adb logcat -b events | grep wm_finish_activity` for the window's end.

| Check | Result |
|---|---|
| E1 | **Passed.** Android's "Sign in to Wi-Fi network · AndroidWifi" (in the Silent bundle, expanded first) under the kiosk: `START … act=android.net.conn.CAPTIVE_PORTAL … cmp=…/com.kidslauncher.mdm.portal.CaptivePortalEntry … from uid 1000`, no violation; the keypad ("Foreldrekode for Wi-Fi-innlogging"); after the code the window in `me.vibb.launcher.debug:portal`, on `10-0-2-2.nip.io` by hostname with Vibb's filter on. Accept: NetworkMonitor validated at .841, the window finished at 07.875 (~1.2 s after the tap), `:portal` died. The report: `outcome: validated`, `approved: code`, `start: system`, `handler: vibb` |
| E2 | **Passed.** `cmd package resolve-activity -a android.net.conn.CAPTIVE_PORTAL` names the entry after the enrolment (the emulator half of D-C1). A second ordinary app (a scratchpad test app, let out through "Allow these apps" and ticked) starting the entry: `Permission Denial: starting Intent { cmp=…/CaptivePortalEntry } from … uid=10220 requires android.permission.NETWORK_STACK`, its `resolveActivity` naming the entry |
| E3 | **Passed** for Vibb's real filter: the portal loads by hostname in the window with the filter VPN up; `sslip.io` added under Blocked domains: a tap to the other site is "Denne siden er sperret", no request reaches it, `blocked_hosts: ["10.0.2.2.sslip.io"]`. Not run: Bare Vibb's sink (this AVD has a SIM), the no-compiled-list case (`LISTS_FAILED`, pinned by the code) |
| E4 | **Passed.** `--mode soft` and `--mode hotspot` with https open: the entry says "Nettet virker allerede" and asks no code (§3.2 step 2: the check runs before the code, so no window opens). The window's own close: opened with https held, then let through - closed as `passes` 1.5 s later. `--mode walled` (Cloudflare refused): Google's 204 passes every 5 s, Cloudflare's is refused, the window stays open; its "Log in with Google" link is a tap to another site and refused; Accept validates |
| E5 | **Passed after a fix.** `meta` and `script` reach the login page (through the notification: their move is in the probe's answer). "Another site" and `target=_blank`: "Denne lenken går ut av innloggingssiden", no request to the other site. `tel:` opens Vibb's own phone book (`DIAL … pkg=me.vibb.launcher.debug`); its back arrow returns to the window. `iframe`: our page in the frame, `blocked_frames: ["10.0.2.2.sslip.io"]`, a `wifi_signin_wide` event (`why: frames`). `redirector`: the tapped `/out?to=` 302 refused. `formpost`: the POST's 302 to the other site allowed (`/welcome`); there `/away` (302 to example.com) refused; back on the portal a tap to the other site allowed (a portal site now). **`leafpost`:** with the window settled first (a tap on Terms, then Back, so the refresh makes `/partner` a leaf), its POST answered **302** to example.com is refused; its POST answered **307** loaded **example.com in the window** - neither hook sees a POST's 307 hop. **Fixed** (ecb5f5cfa): a main-frame load no hook judged (a 307/308 hop, a history move) is judged at its commit in `onPageStarted` - the filter's lists, and on another site as a redirect hop of the chain in flight; refused, it stops and the window steps back. Checked again: the 307 is refused, the window back on `/partner` with the line. The POST itself still reaches the third site (no page shown). Without the settling tap the refresh to `/partner` makes it the first site (the page moved itself before the kid acted, §3.3), and its POSTs may leave by design |
| E5 R7 (measured) | The scratchpad probe app (a plain WebView logging both hooks), before Vibb was installed: a tapped link to `/go` - SOUL `gesture=true`, then its 302 hop SOUL `redirect=true gesture=false`; a form POST - SIR `method=POST`, then its 302 hop SOUL `redirect=true gesture=false`; a script's auto-POST the same; a form POST answered **307** - SIR for the POST only, **no SOUL and no SIR for the target**, which loaded. So `hasGesture()` does not ride redirect hops (the window's chain tracking is what holds) and a POST's 307 needs the commit check above. Also: a meta refresh within Chromium's ~5 s user activation after a tap reads `hasGesture() == true` and is judged as a tap (stricter) |
| E6 | **Passed.** Avansert's "Krev foreldrekode" off (`POST …/logins` `section=wifi_signin`, 303 to `/devices/1/advanced#wifi-signin`): four code-free openings, the fifth "Spør en voksen om Wi-Fi-innlogging"; `wifi_signin_budget` raised once; "Gi én innlogging til nå" (303 to `#logins-wifi`) let one more through after the sync (`grant_used: 1`), the next refused again |
| E7 | **Passed.** Home's line "Wi-Fi-en vil at du logger på" and the Wi-Fi page's "Logg på" (`Tilkoblet – må logges på`) open the entry; the window loads `http://connectivitycheck.gstatic.com/generate_204` and follows the portal's 302 to the login page. **The re-probe:** during continuous page loads NetworkMonitor logged "Forcing reevaluation for UID 10219" no closer than ~4 s apart (42.28, 46.27; 48.93, 56.93, 02.63), each a full probe of ~350-470 ms; the window's own limit is 3 s. **Found:** a binderless sign-in that closed as `passes` left Android's Wi-Fi `CAPTIVE_PORTAL` (no `CaptivePortal` to dismiss, the last page's re-probe skipped) - Home's line and "Logg på" stayed until NetworkMonitor's own recheck. **Fixed** (ecb5f5cfa): the `passes` close asks one re-probe; checked: the window finished at 59.519, "Forcing reevaluation" at 59.516 |
| E10 | **Passed.** After a sign-in that worked the Wi-Fi page offers "Åpne påloggingssiden · Logg av eller se hvor lenge du har igjen" - also after a Wi-Fi off/on (the SSID match). It asks the code, opens the recorded login page, refuses a tap to the other site, allows Terms, and stays open on the validated network (> 14 s); `start: revisit`. With the code off it opens without the keypad and counts in the budget (openings 1-4 above were reopens). The next-day half not run |
| E11 | **Passed.** Settings › Serveradresse `http://example.org`: "Telefonen bruker bare en serveradresse som begynner med https:// …", the address unchanged; no connection to example.org through the proxy |
| E12 | **Passed, but for Cancel.** A wrong code: "Feil kode. Prøv igjen.", a `wrong` PIN event; the lockout is the shared one (two wrong at the Wi-Fi keypad, one at Settings' gate: the events' `failures` 1, 2, 3). A right code opens this opening only; every sign-in asks again. With the budget spent and the code required, a right code opens (D17). The parent code cleared in the PWA: the keypad disabled with "Ingen foreldrekode er satt – spør en voksen", Avansert's row "No parent code is set – sign-in works only with «Allow one more sign-in»"; a grant opens (`approved: grant`, `parent_code_set: false`). The requirement off opens without the keypad. The PWA's Activity: "Parent PIN used on …'s phone · Wi-Fi sign-in". **Cancel does not keep Android's notification on this image**: the notification is `AUTO_CANCEL` and NetworkMonitor re-probes after `reportCaptivePortalDismissed()` (still a portal) but doesn't post it again - Android's own behaviour for any handler. Home's line and the Wi-Fi page's row stay, so the kid still has a way in |
| E13 | **Passed** for the reboot: after a restart on a working network the Wi-Fi page has no reopen row and `no_backup/portal/revisit.json` is gone. A second hotspot with another SSID not run (one access point on the AVD) |

**Also found:** the Sign-in page's recent list wrapped a time as "02:4 / 7" beside a long sub-line; fixed (ecb5f5cfa,
`row-value-keep`). On this enrolment the launcher held no location permission (not design 102's): the Wi-Fi page found
no networks and the session's `network` was null; granted with `pm grant` for the rest of the run, after which the
SSID shows in the window's title and the reopen matched after a reconnect.

## 6aq. Chrome only for logins (design 102 Part B)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6ar. Settings links in the shade, Flymodus's window, the panel's header (fix round `launcher-fix-1`, 2026-11-16)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6as. Android 14-17: Settings off the lists, the media button's hold, lint (compat-fix-1, 2026-11-16)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6at. A HOME before the first unlock without an Android PIN (compat-fix-1, QA H1, 2026-11-16)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6au. The clock during a rule (design 105, 2026-11-16)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6av. The phone book as a task of its own (branch `phonebook-task`, 2026-11-16)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6aw. Two badges and Siste (design 106, branch `a5-b2`, 2026-11-16)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6ax. Blocked notification channels (design 107) (2026-11-16)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6ay. App push: Vibb One as the UnifiedPush distributor (design 108 R1-G, 2026-11-17)

Design 108 §12.3's gate, lean: the rows the build and the code QA flagged first. **`compat34`** (API 34) started with
`-wipe-data -dns-server 8.8.8.8`; **`jelly36`** (API 36) restored clean from `~/kid-look-1-avd-pre-start` (22 of 22 sums,
the same file list), started without `-wipe-data`, and restored again afterwards (22 of 22). One at a time, under
`flock ~/build.lock` and `flock -o ~/emulator.lock`. **Android 17 not run**: no API 37 image boots on this VM (37.2's
graphics stack kills system_server on a readback, §6as) - owed.

**The rig.** The `up` branch's debug server with `BIND_ADDR=0.0.0.0:3108` and `DEVICE_BIND_ADDR=0.0.0.0:3109` (the push
routes live on the phone listener alone), §6ax's data (device 1, migrated to 0152), the public origin `https://up.test`
saved on `/settings/push` (no name resolves: the apps never connect to it, every push goes from the VM straight to
`127.0.0.1:3109` with the endpoint's host as `Host`). The branch's debug launcher as Device Owner (§4's lines),
`POST_NOTIFICATIONS` granted, the server URL `http://10.0.2.2:3109`, enrolled with a typed code; kiosk LOCKED.
`scripts/fake-homeserver.py` posts the pushes (`notify` Synapse-shaped `event_id_only` JSON, `up` Web-Push-like bytes,
`--burst`).

**The test app** is a throwaway probe written for the gate (Java, built with aapt2/javac/d8 like §6ap's, debug-signed,
`me.vibb.probe.up`; the UP example app's GitHub releases stop at 1.5.4 of 2023, an AND_2 connector that shares no
identity, and newer ones are on Codeberg only): the connector side of AND_3 written from the spec, not the connector
library - REGISTER and UNREGISTER with `BroadcastOptions.setShareIdentityEnabled(true)` and the connector's AND_2
extras (`features`, `application`, `pi`), MESSAGE_ACK with a plain `sendBroadcast` (as the connector does), an exported
`RAISE_TO_FOREGROUND` service (switchable, and with an optional slow `onBind`), a `dataSync` foreground service started
from MESSAGE (switchable), a Link activity asking `unifiedpush://link` for a result, and a second "distributor"
(`OtherLink`, the same link filter) so the preferred activity has a competitor. Commands by `am broadcast -f 32 -n
me.vibb.probe.up/.Cmd --es cmd ...`; its log tag `UpProbe`. Let out with Allow these apps and ticked under Apps (§3b).
The launcher's debug build logs each MESSAGE send and each pull's acks by key prefix (`UpRuntime`).

| Row | `compat34` (14) | `jelly36` (16) |
|---|---|---|
| 1 register | **Passed.** `queryBroadcastReceivers` lists `me.vibb.launcher.debug`; REGISTER with the shared identity and the AND_2 extras -> `NEW_ENDPOINT` 27 ms later (`https://up.test/up/<27>`) | **Passed.** The same, 19 ms |
| 15 on by default | **Passed.** Device 1 (made before migration 0152) with the origin saved: `UpReceiver enabled`, `UpLinkActivity enabled` at the enrolment's first syncs | **Passed**, the same |
| 2a deliver | **Passed.** The gateway POST `200 {"rejected":[]}`, MESSAGE (the minimised Matrix JSON, 259 bytes) about 70 ms after it; the queue empty after the ack pull (6 s later) | **Passed**, the same |
| 3 the raise | **Passed.** Screen off (the launcher at `FOREGROUND_SERVICE`): the probe goes `CACHED_EMPTY` -> **`FOREGROUND_SERVICE` for 5.0 s** (bind to unbind) -> `CACHED_EMPTY` (`cmd activity get-uid-state` every 0.25 s); its FGS start from the MESSAGE receiver **allowed** (`FGS running`). The control, the raise service disabled: the message sent at once without the raise (`has no raise service`), and the same FGS start **refused** (`ForegroundServiceStartNotAllowedException`). Screen on (Home on top) the bind gives `BOUND_TOP` | **Passed**, the same: `FOREGROUND_SERVICE` for 5.0 s, FGS allowed with the raise, refused without |
| 10 no loss in the raise | **Passed.** (a) Raise service disabled: delivered without the raise, acked after the send. (b) `onBind` slowed to 3 s, three pushes, `am force-stop` of the probe 1 s into the binding: the three `MESSAGE sent` lines come with the lost binding and arrive in the restarted process (three MESSAGE lines, a new pid); the trailing pull in between acked `[]` (nothing sent yet, nothing re-broadcast); the three keys acked 6 s after their sends; the queue empty | **Passed**, the same |
| 9 the Link Activity | **Passed.** In the LOCKED kiosk, with `OtherLink` installed as a second handler: `link result=-1 distributor=me.vibb.launcher.debug`, no chooser (`ResolverActivity`/`ChooserActivity` never in the log), no Recents entry. UP off: the activity disabled, the link resolves past it to `OtherLink` (`cmd package resolve-activity`), no chooser. 14 has no policy engine to dump the PPA from; the win over a second handler is the proof | **Passed**, the same. `dumpsys device_policy` lists the PPA (`IntentFilterPolicyKey persistentPreferredActivity` -> `UpLinkActivity`) while UP is on; **while the activity is disabled the engine doesn't list it** (6 keys -> 5, before any resolve) and it is back with the enabling: on again, the first read (about 250 ms after `UpLinkActivity enabled`) lists it and the link resolves to ours - no window seen |
| 6 UP off / remove | **Passed.** The PWA's switch off: `UNREGISTERED` to the app, both components in `disabledComponents`, `queryBroadcastReceivers` empty; on again: enabled within the save's sync, Vibb One listed, a new REGISTER answered. Uninstall: `PACKAGE_FULLY_REMOVED: me.vibb.probe.up's registrations go` (`UpPackageReceiver`), the server's rows gone, `/up/` 410, the gateway `rejected` | **Passed**, the same |
| 13 Stop | **Passed.** PWA Stop: `UNREGISTERED` for each token; the app's own REGISTER refused before the store, `REGISTRATION_FAILED(ACTION_REQUIRED)`, no server row; the PWA lists it under Stopped; Allow again: the next REGISTER gets an endpoint | **Passed**, the same |
| 4 stopped, Doze, standby | **Passed.** Force-stopped, screen off, `dumpsys deviceidle force-idle` (deep IDLE) and standby bucket 45 (restricted): delivered 140 ms after the pull | **Passed**, 100 ms |
| H1 (code QA): no broadcast held | **Passed.** The phone port answered by a listener that accepts and never replies; a REGISTER, then an SMS 1 s later (SMS managed, the launcher the SMS app): the REGISTER's broadcast **finished 2 ms after dispatch** while its server call ran 10 s (OkHttp's read timeout, then `REGISTRATION_FAILED(NETWORK)`); SMS_DELIVER to `SmsDeliverReceiver` dispatched 0 ms after enqueue and finished in 7 ms (`dumpsys activity broadcasts history`) | **Passed**: REGISTER finished +4 ms, SMS_DELIVER +1 ms / +19 ms |
| 7 offline (part) | **Passed.** The server down (refused connect): `REGISTRATION_FAILED(NETWORK)` at once; the server back, the same token's REGISTER retried the kept pair and got its endpoint | - |
| 14 origin change | **Passed.** `/settings/push` to `https://push.test`: `NEW_ENDPOINT` with the new origin within the save's sync; a gateway POST naming the old origin still queued (`rejected: []`, delivered), one naming `evil.test` rejected | - |
| 8 limits | **Passed** (server side): 4097 bytes 413; a never-seen secret 410; a tombstoned one 410; a burst to one endpoint 429 from about the 30th (the registration's sub-bucket); 32 never-seen secrets from one client: 410 ×29 then 429; during that ban a resolving secret still 201 | - |
| 11 trailing edge | **Passed.** Two `/up/` pushes 100 ms apart: two `up` events, both delivered within 0.3 s | - |

**Row 5, a time rule (the user's question, run afterwards on the same rig, both versions).** The probe now posts a
chat-like notification for each MESSAGE (channel "Messages", importance HIGH: sound and heads-up) from its foreground
service, or from the receiver when the FGS start is refused (as Element X falls back). A baseline push with no rule
alerts (`notification_alert` with sound, a heads-up). Then the PWA's "on now" for a rule (`POST /devices/1/rules-now
mode=on`), the probe `suspended=true`, LockActivity on top; three pushes 1.5 s apart; then the rule ended
(`/lock-now/<id>/end`).

| Row 5 | `compat34` (14) | `jelly36` (16) |
|---|---|---|
| During the rule, the raise on | every MESSAGE delivered; the raise binds; the suspended app's **FGS start is allowed**; it posts all three. NMS: every record of the package `mHidden==true`; **no `notification_alert`** (no sound, no vibration; `mSoundNotificationKey=null`); SystemUI's collection lists each with `filter=IsSuspendedFilter` - not in the shade, no heads-up, the FGS's own notification filtered with the package's | the same; SystemUI: `PEEK suppressed: app is suspended` for the first, `notification isSilent` for the next |
| The rule ends | all three appear in the shade (`section=Alerting`), **no sound or vibration** (no `notification_alert`), but **a heads-up for each** (`HeadsUpManager: show notification`, about 5.7 s, newest first) | all three appear in the shade (`section=Alerting`), **silently: no sound, no vibration, no heads-up** (the peek was decided as suppressed at post time and not asked again) |
| The raise off (the alternative) | MESSAGE still delivered; the **FGS start refused** (`ForegroundServiceStartNotAllowedException`) and the receiver posts instead; the same hiding during the rule and the same end (shade + a heads-up each, no sound) | the same: FGS refused, the receiver posts, hidden during, silent in the shade after |

So while a rule makes the app not allowed, the phone neither alerts nor shows its notifications, and when the rule
lifts the piled-up ones are in the shade - on 16 silently, on 14 with a silent heads-up each. The raise changes only
whether the app may start its foreground service while suspended (it may with the raise, not without), not what the
kid sees. No product code was changed for this row.

**Not run:** row 2b (the app's own encrypted test needs a trusted CA), 12 (a second instance), and Android 17. **The connector's acceptance of our NEW_ENDPOINT** (sent
without a shared identity): read in the connector's source - `MessagingReceiver.onReceive` looks the token up and never
checks the sender - and seen on the probe (`sender=null`); the real connector is Element X's, owed on the Jelly Star.
No crash of the launcher in either run (`logcat -b crash`).

## 6az. Innstillinger as a task of its own (design 111, branch `kid-settings-task`) - not run yet

`docs/notes/launcher/kid-settings.md`, "Innstillinger as a task of its own"; the design's §4. Setup as §6av: **`jelly36`**
restored clean, the kiosk LOCKED and enrolled; rules `Lekser` (calls off) and `Kveld`; a rule turned on now with the
PWA's `POST /devices/1/rules-now` and ended with `POST /devices/1/lock-now/<id>/end`; transitions recorded with the
guest's `screenrecord` and cut into frames (`ffmpeg -vf fps=20`). **`compat34`** started with `-wipe-data -dns-server
8.8.8.8`. The parent's mask with Wi-Fi, Bluetooth, sound and Snarveier (1 + 2 + 8 + 128) unless a row says otherwise.

| # | Check | How | Expected | API |
|---|---|---|---|---|
| 1 | Own task | Home's Innstillinger tile, then Home and the swipe left; `dumpsys activity activities`, `dumpsys activity lock-task` | `Task{... type=standard A=...launcher.debug.kidsettings}`, `KidSettingsActivity` on top, the same task from both ways in; Home's task first in the lock-task list | 36, 34 |
| 2 | Open and close | The tile, then the Home key, recorded; the same for Meldinger | **Record the transition type seen** (task or wallpaper-intra) and the first frame. Expected: Meldinger's open and close task animation; the first frame plain night ground (`kid_ground`), the same colour as the screen - no wallpaper anywhere on Kid Settings | 36, 34 |
| 3 | Recents and Back | The square key; a tap on the card. Wi-Fi page, Home, the tile. A picker on top, then the card. The Wi-Fi sign-in entry on top (a captive network, §6ap), then the card. Back twice | A card "Innstillinger" with the Vibb icon, the night ground in its thumbnail; a tap brings back the same instance (`dumpsys` hash); the Wi-Fi page comes back on top; the picker and the entry come back with the card; Back twice ends on Home and the card stays | 36, 34 |
| 4 | Location and the mask | Wi-Fi page, then Home: `settings get secure location_mode`; back on the page; a connect followed at once by Home; the PWA takes the Wi-Fi bit away, then the card | Off after Home, on again on the page (when the parent had it off); no "couldn't connect" toast after the connect and Home; without the bit Kid Settings shows no Wi-Fi row, the page is gone, and `location_mode` never turned on (logcat: no scan) | 36, 34 |
| 5 | A rule, and Flymodus | Wi-Fi page, Home, `Lekser` on, then the card; the rule ended, then the card. Flymodus on and off from Kid Settings (the assistant role held) | Under the rule the time-rule screen, the Wi-Fi page finished, no scan started (logcat); after it Kid Settings. Flymodus: back on Kid Settings after each | 36, 34 (rule only) |
| 6 | Process death | Lys with the phone in dark theme; Bluetooth page, Home, `adb shell am kill` / `kill -9` of the process, then the card | **Record** the splash (the sub-page's `KidPageTheme` ground in the phone's mode - the accepted wrong-look flash, design 111 §2.3; no icon: every theme inherits `launcherBaseTheme`'s `splash_icon_none`, so design 111's "with the Vibb icon" is not expected), then the Bluetooth page restored in Lys. Back twice ends on Home; `dumpsys activity lock-task` shows Home first | 36, 34 |
| 7 | Language and look | The parent switches nb to en with the card in Recents, then Home (logcat: whether the first ask was refused); in Kid Settings Lys with Meldinger, Dagen and the phone book open behind | The switch goes through. **Record whether the first ask was refused**: only then is Kid Settings' task closed (the next card "Settings"); a first ask that got through leaves the task and its card "Innstillinger" until the screen is shown again (code QA L5). Lys closes Meldinger's, Dagen's and the phone book's tasks; Kid Settings' stays | 36, 34 |

Rows 2, 3 and 6 on the 17 image are optional (design 111 §3).

## 6ba. App push held while the apps have no network (design 115, 2026-11-18)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bb. The phone's setup: the hold and the guided flow (design 114, branch `design-114-build`)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bc. Failures made visible: "filter on, tunnel down", the Integration row, the backup check (design 116, branch `c2-build`)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bd. The Bluetooth pairing window (design 122, branch `bt-pairing-window`, 2026-11-20)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6be. Android Auto, the other Home apps, the notification listeners (branch `notif-readers`) - run 2026-11-24

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 7. Reporting back

For anything odd, paste into the chat:

```sh
adb logcat -d -t 2000 | grep -iE "kidslauncher|vibb|Telecom|InCall|CallScreen|AndroidRuntime|SyncRunner|CommandListener|PlayRuntime|Backstop|AppEnforcer|EmergencyDialer|LockTask|PinLock|UpdateFence|SelfUpdate|NotificationRule|CameraLock" > log.txt
```

plus what you did and what you saw (a screenshot helps: `adb exec-out screencap -p > s.png`).
Restore the "enrolled" snapshot between experiments.

## 6bf. Time rules keep their own clock (branch `trusted-time`, 2026-11-24) - run 2026-11-24

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bg. Android's blocked numbers cleared while managed (branch `blocked-numbers`, 2026-11-23)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bh. The web filter's resolver: Quad9 first, Cloudflare as the fallback (branch `dns-upstreams`) - run 2026-11-24

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bi. Lyd: a notification sound on the locked phone (design 130, branch `lock-sound`) - run 2026-11-24

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bj. The hardware-bound phone key, L1 (design 129, branch `hw-key-l1`) - run 2026-11-24

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bk. Apple's cell lookup and the coarse fix (design 125b, branch `cell-lookup`) - written, not run

Design 125b (`docs/design/125b-apple-cell-lookup.md` §9): the wiring only, on **Android 14 and 16** with the user's OK.
A server from the branch with **`WIFI_LOOKUP_PROXY` pointing at a closed port** (`socks5h://127.0.0.1:9`), so no
emulator cell - the modem simulator's fixed LTE cell on a real PLMN (310260, or 311740 on the Norwegian-SIM image,
§6al) - ever reaches Apple; the debug launcher, Regularly, the phone's "Oppslag hos Apple" on, Google's location
accuracy off (`settings get secure location_mode`, the `network` provider off), Location on. Read the cell first:
`adb shell dumpsys telephony.registry | grep -i -m3 cellinfo`. Logs: `adb logcat -s WifiPlaces ZoneWatch`.

| # | Step | Expect |
|---|---|---|
| 1 | Location on, a heartbeat `FIX` | `WifiPlaces: cells read=1 usable=1 registered=1 nr=0 stale=0` |
| 2 | Location off (`cmd location set-location-enabled false`), a parent `locate` | no `cells read=` line with a usable cell (an empty list: `DENIED_SOFT`) |
| 3 | Wi-Fi and Wi-Fi scanning off, a learned miss | `apple cells asked=1 status=503 step=Wait` - the cells-only ask with no scan view (QA H1); the server's 503 (the call failed at the proxy) came at the fixed answer time, 14 s after the request (`curl -w %{time_total}` on the same body with the phone's token: 14.0-14.5; code QA M2) |
| 4 | The next attempt within 5 min | no cell ask (the cell wait); a Wi-Fi ask still goes when a scan view is there (Wi-Fi back on): `apple asked=` - the Wi-Fi wait untouched |
| 5 | The switch off in the PWA, a sync | no ask; the waits gone (`run-as ... cat shared_prefs/wifi_places.xml`: no `apple_cell_wait_until_ms`) |
| 6 | The switch on, `settings put global airplane_mode_on 1` + broadcast | `cells read=0`; no ask |
| 7 | Google's location accuracy on (the `network` provider on) | none of this runs: no `cells read=` line (design 124 D9's balanced path) |
| 8 | `curl` the route with `{"cells": ["lte:001:01:4711:7"], "bssids": []}` | 400; `{"cells": ["lte:001:01:0:7"]}` 400; the 31st request in an hour 429 `limit` at once |

Not on the emulator (§9): neighbours, NR, a second SIM, a move between cells, Apple's answer and its accuracy, a zone
20 km away, a `vibb_apple_cell` fix - the Jelly Star with a SIM (`docs/testing/new-phone-model.md` §2).

## 6bl. The glance back after a plug-in (PLAN (4i), branch `glance-back`) - run 2026-11-24

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bm. Enrolment requires a key: design 129b §10.3 with `emulator_keys` (branch `gate-129b`) - run 2026-11-24

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bn. A Wi-Fi fix carries what confirmed it (design 131, branch `wifi-confirm`) - run 2026-11-25 (branch `gate-131`)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6bo. Posisjon also covers the location stored in photos (branch `media-location-delete-lines`) - run 2026-11-25

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6bp. The Tailscale key sealed; Koble til after lost settings (branch `launcher-edges`) - run 2026-11-25

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bq. User certificates, the provisioning baseline, storage full (branch `storage-certs-baseline`) - run 2026-11-25

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6br. The collected gate before launcher rc.4 (branch `gate-release-043`) - run 2026-11-25

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bs. After a small move: inconclusive scans, the shadow fingerprint, a mismatch ends still (design 134, branch `still-print`) - run 2026-11-25/26

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bu. The glance's handle room on Android 14, the boot cover's one ask (branch `glance-bootcover`) - run 2026-11-26 (§6bv)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6bv. The gate before launcher rc.5 (branch `gate-rc5`) - run 2026-11-26

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bw. A zone's Wi-Fi: the parent's confirmation, the learned link bound to its router (design 138, branch `wifi-link-confirm`) - run 2026-11-26 (branch `gate-138`)

`docs/design/138-confirmed-zone-networks.md`: on **Android 14 and 16**. The setup of §6bn: the debug launcher,
Regularly, a zone Home (150 m), Google's location accuracy off, Location on, the made-up scan and connection through
`PlacesDebugReceiver` (`--es connect "<bssid>,<ssid>,psk|sae|eap|open,<networkId>,<MHz>[,<dBm>[,<mld>]]"` - the
seventh field, the AP's multi-link address, is design 138's), the satellite feed for teaching, a second local day by
the time zone. Home's router `00:00:5e:00:53:01` "Net-A" (its guest name on the same prefix `00:00:5e:00:53:05`), three
neighbours on their own prefixes (`00:00:5e:00:61:01`, `00:00:5e:00:62:01`, `00:00:5e:00:63:01`), taught first as
§6bn says. Logs: `adb logcat -s WifiPlaces ZoneWatch PlacesDebug`. The real background read (the Jelly's redacted
BSSID and the callback) is a device row: the emulator's own network is open.

| # | Step | Expect |
|---|---|---|
| a | Nothing confirmed or learned for Home (`--ez clear true`, then the neighbours taught), GPS silent, home's scan, `connect "00:00:5e:00:53:01,Net-A,psk,7,5180"`; a sync | `zone wifi report yes netid=known`; the report's `device_status.zone_watch_json` has `wifi` with `Net-A`; the zone's page shows `Net-A` with Bekreft; with `open` - `zone wifi report no reason=security`, no `wifi` in the report (the page keeps the Bekreft row of the last report with `wifi` for its hour, D4) |
| b | Tap Bekreft; Locate | the policy's zone has `networks` with the id, name and BSSID; `zone link confirmed rest=fix`, `wifi fix check=link step=USE`; the report's fix `check: "link"`; Locate no "Wi-Fi estimate"; "At Home" on the row only when the fix is surely inside the zone (the server's `zones::side`, unchanged) |
| c | The finding's path: reinstall the same debug APK over itself (a new process, `MOVED`), then the heartbeat's own `FIX` (the process start's try when a beat is due; else the clock 31 min on as root and a PWA save as the nudge) | `attempt path=TOGETHER`, `check=link`, `Location heartbeat: FIX result=fresh`; no back-off |
| d | `connect` `00:00:5e:00:53:02` (the same router's other band); then `00:00:5e:00:58:01`, `Net-A` (another router) | the first `check=link` at once; the second `zone link none` (and with only it heard, `router fix unconfirmed`), the page's Bekreft row and no orange line; Bekreft - `check=link` |
| e | `connect` another link of a router confirmed with a multi-link address (seventh field the same) | `check=link` at once |
| f | The travel router: `Net-A` (a) connected, another place's scan (three neighbours taught 3 km away) | `zone link none rest=fix`, `check=none`; with only the router heard (its two names and an unknown router) - no Wi-Fi fix (no three usable results placed), `router fix continuity` (the last fix was the other place's: the gate), no `link` fix |
| g | Then the satellite feed 3 km away (8 satellites) twice, 90 s apart, `Net-A` connected | one feed fix alone strikes nothing; after the second `zone link struck by=confirmed`; the next report's `zone_watch.wifi_struck` has the id; the page "Sett et annet sted"; the security log "Wi-Fi seen elsewhere" (the zone's name only); the policy drops the row; back home `check=none`; Bekreft again - a new id, `check=link` |
| h | The learned link: no confirmation, GPS silent, home's scan and `Net-B` psk (another router); Locate 3 times 30 min apart over 2 local days, every made-up level moved by a dB between the Locates (the same levels twice read as a stale cache, D9's guard: `scan usable=.. fallback`, inconclusive, no occasion), the phone brought home by GPS first when its last satellite fix was elsewhere (the jump gate counts elapsed time, which the clock steps don't move: `step=REFUSE`) | `link taught by=wifi occasions=1..3 days=1..2`; then `zone link learned rest=fix`, `check=link`; a scan of only `Net-B`, its guest name and unknown routers - no occasion (`occasions` unchanged) |
| i | Move the zone 200 m (radius 150); then confirm again and move it 50 m | the first clears the rows ("Wi-Fi removed" in the `zone_changed` line); the second keeps them |
| j | Revision 3, the single-router fix: the last reported fix inside Home; `Net-A` confirmed and connected (`connect "00:00:5e:00:53:01,Net-A,psk,7,5180"`), only it heard (its two names, in the made-up scan at -45 dBm), GPS silent; Locate | `router fix fix at=own acc=N` (`sqrt((range/2)^2 + 25^2)`: 55 with the rows of this setup; `at=zone acc=153` for a confirmed router without rows), `wifi fix check=link step=USE`; the report's fix `vibb_wifi` with `check: "link"` at the router's position; without the router in the scan `acc` from the -85 dBm bound (158 here); no `link taught` line |
| k | The same with §6bk's fake Apple cell answer placing the registered cell 5 km away | `router fix cell`; no Wi-Fi fix (`check=none` or GPS) |
| l | The same after (g)'s strike (the row blocked, the policy without it) | `router fix unconfirmed`; no `link` fix |
| m | The continuity gate: a satellite fix 3 km away reported (the cached fix outside Home), then back on `Net-A` with only it heard | `router fix continuity`; no `link` fix until GPS or an ordinary checked fix brings it home |
| n | After a restart (reinstall over itself) with the last reported fix at home, the scan inconclusive (`--ei scan_age_s 600 --ez refuse_scan true`: with fresh results the cache answers), the heartbeat's `FIX` once due (as c) | `router fix fix`, not withheld; `Location heartbeat: FIX result=fresh` |
| o | `adb shell svc power reboot` at home with the last fix at home (off seconds; `adb reboot` records no shutdown, §6p, so the gap is unknown and the gate holds nothing); then `adb shell svc power shutdown`, 15 min, a cold boot; GPS silent before the unlock (the shell's `cmd location providers add-test-provider gps`, else the emulator's own GNSS answers) | the first `router fix fix`; the second `router fix continuity` until another fix brings it home |
| p | A cell-only fix reported last (GPS silent, one unknown AP heard, not connected, Locate: `vibb_cell` - a cell fix needs a placed cell; `--ez gps_off true` lets the emulator's own GNSS answer), then back on `Net-A` alone | (the verification QA's H1) a coarse fix that isn't surely outside says nothing, so the older `link` fix decides: `router fix fix`; a cell fix surely outside the zone - `router fix continuity` |

The cell veto is a unit row (`ZoneLinksTest`): the emulator's cell has no placed row unless §6bk's fake Apple cell
answer is loaded - if it is, place it 5 km away and expect (b) to give `check=none`.

**Device row** (the Jelly Star, Android 16, the owner's check): at home, screen off, an hour after the install - the
report's `wifi` present (`connection read by callback yes` when the first read was redacted, `zone wifi report yes
netid=known|none`), the scan fresh (`scan answer n/m ask=answered`), the parent's Bekreft, then `FIX result=fresh` at
the next heartbeat.

### The run of 2026-11-26 (branch `gate-138`, 16:10-18:30 UTC)

On **Android 14 and 16** with the owner's OK (17 is a device row). Android 14 on `compat34` (Google APIs, the
`network` test provider off - added as the shell user before `adb root`, again after every boot), Android 16 on
**`aosp36`** (AOSP) - not `jelly36`: the rows step the clock as root, and no row needed a screenshot. Each image kept
its phone of §6bv's rerun (Telefon 1, enrolled with its key, Android's PIN 1357), each on its own debug server from
this tree (`--features emulator_keys`, a copy of its data, §6bv's relays `3150` and `3160`); the debug launcher of the
branch installed over it with `adb install -r` and `adb shell sync`. Setup: the location policy Off and then Regularly
10 min (Off clears the places), a zone Home (59.9139, 10.7522, 150 m); home's made-up scan `Net-A`
`00:00:5e:00:53:01` (5180 MHz, -45), its guest name `Net-A-gjest` `00:00:5e:00:53:05`, three neighbours
`00:00:5e:00:61:01`/`62:01`/`63:01`, taught by the satellite feed (8 satellites, 10 m) with no made-up connection on
two local days (the clock 31 min on as root, the zone to `Pacific/Kiritimati`): `scan usable=5 trusted=5 networks=4
acc=65`. That state was saved as a snapshot `base138` with each server's data (§3, "Faster gates").

**Builds.** First `wifi-link-confirm` at `5a2c30131` (the gate's merge `5f6b45a44`): rows a-e, j, m, n. The
verification QA's `0a00aa512` (the gate's witness, the off gap kept apart) came during the run (merge `2b4e418e1`):
both images went back to `base138`, the new build was installed over it, and a, b, j, m, n, o, the STILL cap try and
f-i, l, p ran on it. d and e ran on `5a2c30131` only (the change touched the gate, the off gap and the router fix's
place in the keep, not the match).

**GPS silent** is the satellite feed's test provider with no fix (`--es gps "59.9139,10.7522,10,8,0"`); `--ez gps_off
true` removes it, and the emulator's own GNSS then answers at its `geo fix` place. After a boot the provider is added
before the unlock as the shell user (`cmd location providers add-test-provider gps`; on `aosp36` once `appops set
com.android.shell android:mock_location allow`). Locate stands in for the heartbeat except where a row says beat (the
clock on as root, a PWA save as the nudge). Two things of the debug hooks: the made-up scan repeated with the same
levels reads as a stale cache (D9's guard: `scan usable=.. fallback`, inconclusive, no B occasion) - row h moved every
level by a dB between Locates; and a feed fix 3 km away is followed by `step=REFUSE` at home until GPS brings the phone
back, since the jump gate counts elapsed time, which the clock steps don't move. Logs in the session's
`scratchpad/g138/logs/` (not committed).

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) | Build |
|---|---|---|---|
| a | **Pass.** `zone wifi report yes netid=known`; `zone_watch_json` `"wifi":{"bssid":"00:00:5e:00:53:01","ssid":"Net-A"}`; the page's `#zone-wifi`: `Net-A` Bekreft (the form posts `bssid=00:00:5e:00:53:01`), no orange line. `open`: `zone wifi report no reason=security`, no `wifi` in the report; the page keeps the row of the earlier report (D4, the row's text corrected) | **Pass.** The same | both |
| b | **Pass.** `zone_networks` `1 / Net-A / 00:00:5e:00:53:01 / mld NULL`, the page `?wifi=saved#zone-wifi` with `Fjern...`; Locate: `zone link confirmed rest=fix`, `wifi fix check=link step=USE`, the location row `vibb_wifi` 65 m `fix_check=link`; Locate "Funnet innenfor 65 m", "Her nå: Home", no "Wi-Fi-anslag". Locate ran GPS its 60 s (65 m is over §6bn's 60 m stop) | **Pass.** The same lines and location row (the Locate page's text not read: with two phones it is drawn by script) | both |
| c | **Pass.** After `adb install -r`: `zone link confirmed rest=fix`, `wifi fix check=link step=USE`, `attempt path=TOGETHER result=vibb_wifi`, `Location heartbeat: FIX result=fresh moving=false`; no back-off | **Pass.** The same | `5a2c30131` |
| d | **Pass.** `53:02` (Net-A, 2437): `zone link confirmed rest=fix`, `check=link`. `00:00:5e:00:58:01` Net-A with home's scan: `zone link none rest=fix`, `check=none step=USE`; alone: `router fix unconfirmed`; the page: Net-A (confirmed, Fjern) and a second Net-A row with Bekreft (`bssid=00:00:5e:00:58:01`), no orange line; Bekreft: row 2, `check=link`. Fjern on it removed both rows of the name (D2) | **Pass.** The same | `5a2c30131` |
| e | **Pass.** Confirmed `00:00:5e:00:59:01` with `mld 00:00:5e:00:59:10` (the report's `wifi` carried `mld`, stored from it); `00:00:5e:00:5a:01` (another prefix) with the same MLD: `zone link confirmed`, `check=link`; without the MLD: `zone link none`, `router fix unconfirmed` | **Pass.** The same | `5a2c30131` |
| f | **Pass** (the second half's text corrected). Another place 3 km north (`Borte1-3`, taught there on two local days), Net-A connected and heard: `scan usable=5 trusted=5 networks=3`, `zone link none rest=fix`, `check=gnss`/`none`, `router fix continuity`. Only Net-A's two names and an unknown router: no Wi-Fi fix (`trusted=2 networks=0`), `router fix continuity` - the gate, the last fix being there | **Pass.** The same | `0a00aa512` |
| g | **Pass, with a note.** One feed (25 fixes in 25 s) strikes nothing; the strike came at the next far satellite teach (`taught aps=4 new=0 moved=1`, `zone link struck by=confirmed`) 5 min after the first teach - the first try's first feed was not taught passively (no `passive teach` line at it), so its second feed was the first teach. The report's `wifi_struck` was taken: `zone_networks.blocked_at` set; the page "Sett et annet sted" in orange with its (i) ("Telefonen var på Net-A langt utenfor sonen, ..."); the security log `zone_wifi_blocked` with `{"zone":"Home"}` only; then `router fix unconfirmed`. Home's scan: `zone link none`, `check=none`. Bekreft again: a new id (3), `check=link` | **Pass.** The feed twice 90 s apart: `taught aps=3` at each, `zone link struck by=confirmed` at the second; the rest as 14 (home's scan after it: `check=none step=REFUSE`, the jump from the far fix); Bekreft again: id 3, `check=link` | `0a00aa512` |
| h | **Pass.** Net-B (`00:00:5e:00:64:01`, psk, never confirmed) with home's scan, GPS silent: `link taught by=wifi occasions=1 days=1`, `occasions=2 days=2` (the zone to `America/Adak`; one taught by the heartbeat's `LEARNED_FIRST` try), `occasions=3 days=2`; the next Locate `zone link learned rest=fix`, `wifi fix check=link step=USE`. Only Net-B's two names and an unknown router: no Wi-Fi fix, no `link taught` line | **Pass.** The same (`occasions=1 days=1`, `2 days=2`, `3 days=2`, then `zone link learned`, `check=link`; Net-B's names alone: none) | `0a00aa512` |
| i | **Pass.** Moved 200 m north: both rows gone; `zone_changed` with "moved" and "Wi-Fi removed" (`security.detail.zone.wifi_removed`). Back, Net-A confirmed again (id 4); moved 50 m: kept (`zone_changed` "moved" alone); radius 150 -> 200 and back with a 50 m move: kept | **Pass.** The same | `0a00aa512` |
| j | **Pass** (the numbers in the row corrected). Only Net-A's two names at -45, GPS silent: `router fix fix at=own acc=55`, `zone link confirmed rest=unknown`, `wifi fix check=link step=USE`; Locate stopped at 16 s; the row `vibb_wifi` 55.9 m `link` at the router's own position; no `link taught`. Without the router in the scan (the guest name alone): `at=own acc=158`. A confirmed router with no rows (`00:00:5e:00:5b:01`, the cap try below): `at=zone acc=153` | **Pass.** The same (`acc=55`, `acc=158`, `at=zone acc=153`) | both |
| k | **Not run.** No placed cell 5 km away: §6bk has no fake Apple answer, and an own cell row moves with each teach (`compat34`'s own row for the modem's cell came out 1.35 km north, 1948 m, from the fixes at home and 3 km north). The cell veto stays `ZoneLinksTest`'s | **Not run** (no cell row on `aosp36`) | - |
| l | **Pass.** After (g)'s strike: `router fix unconfirmed`, no `link` fix | **Pass.** The same | `0a00aa512` |
| m | **Pass.** The feed 3 km north with no made-up connection (the location row `gps` 59.9409), then Net-A alone: `router fix continuity`, `attempt path=LOCATE result=none` | **Pass** on `0a00aa512`; on `5a2c30131` **failed once of two**: after (j)'s router fixes (the last 158 m) and a reported satellite fix 3 km north, Net-A alone gave `router fix fix at=own acc=55` and a `link` fix at home (finding 2); the second try `continuity` | both |
| n | **Pass.** Home by the feed, then `adb install -r`, only Net-A's names heard 600 s ago with every scan refused: the process start's try wasn't due; the clock 31 min on and a nudge: `scan answer 0/2 ask=refused refused`, `router fix fix at=own acc=158`, `check=link step=USE`, `attempt path=TOGETHER result=vibb_wifi`, `Location heartbeat: FIX result=fresh`; not withheld | **Pass.** The same | both |
| o | **Fail** (finding 1). `svc power reboot` at home (the last fix a router fix at home; off 6 s: `phone_off` 17:01:17, `phone_on` 17:01:23): after the unlock, Net-A alone, GPS silent: `router fix continuity`. `power_events.xml` holds no `boot_off_gap_v1`. With `adb reboot` (no shutdown recorded, the gap unknown): `continuity` too, as designed. The 15-min case: `svc power shutdown` (`phone_off` 18:05:36), a cold boot 15.5 min later (`phone_on` 18:21:07), the same: `router fix continuity` - as the row expects, though with finding 1 it can't tell the two cases apart | **Fail**, the same (`off_at_ms`/`off_boot_count` 16/`on_boot_count` 17 kept, no `boot_off_gap_v1`; `router fix continuity`). The 15-min case (`phone_off` 18:05:39, `phone_on` 18:21:07): `router fix continuity`, as 14 | `0a00aa512` |
| p | **Not reached.** The cell fix came (one unknown AP heard, not connected: `cell fix acc=1948 REPORT`, `vibb_cell`), but Net-A's own rows had moved in (g) (`moved=1`), so the router fix answered `router fix moved` (D15's veto) before the gate. With `--ez gps_off true` the emulator's own GNSS answered (a `gps` fix at the `geo fix` place) | **Not reached.** No cell fix on `aosp36` (no placed cell): Wi-Fi off and GPS silent gave `result=none` | `0a00aa512` |
| STILL cap try | **Pass.** A confirmed router with no rows (`00:00:5e:00:5b:01`, the only AP heard, -50), `motion sensor` (`Motion trigger armed`), Locate: `router fix fix at=zone acc=153` at the zone's centre, `vouch link read level=scan`; the clock 245 min on, a nudge: `connected witness no reason=unsettled`, `router fix fix at=zone acc=153`, `attempt path=LEARNED_FIRST result=vibb_wifi`, `Location heartbeat: FIX result=fresh`; a new location row | **Pass.** The same | `0a00aa512` |

**Findings**

1. **Medium - the off gap across a boot is never kept, so after any restart the single-router fix answers
   `continuity` until GPS or an ordinary checked fix brings the phone home** (row o; the restart case D15 promises).
   `PowerEvents.recordBoot` writes `boot_off_gap_v1` inside its `update { }` block on the same `power_events`
   preferences, and `update()` then saves the record with `prefs.edit().clear()` and the record's keys only - the gap
   key is wiped in the same call (and the upload's own record rewrite would wipe it again). `lastOffGapMs` is then
   always `null`, and `continuityHolds` holds nothing for the boot before. Repro (both images): a router fix at home,
   `adb shell svc power reboot`, unlock, GPS silent, Net-A alone, Locate: `router fix continuity`; `adb shell cat
   /data/user_de/0/me.vibb.launcher.debug/shared_prefs/power_events.xml` has `off_at_ms`, `off_boot_count`,
   `on_boot_count` and no `boot_off_gap_v1`. `PowerEvents`' tests can't see it (the gap is a pure function).
2. **Low, `5a2c30131` only - the gate opened once after a reported far satellite fix** (row m, `aosp36`, 1 of 2): the
   last candidates were (j)'s router fixes (158 m) and the 3 km `gps` fix reported a minute later; the router fix
   answered `fix at=own`. Not seen on `0a00aa512` (m twice per image, n, f, l) - its H1/L1 (the newest candidate that
   says something, this boot's by elapsed time) is the change.

**Rows corrected** (no code): a (the page keeps the Bekreft row of the last report with `wifi`, D4), c and n (the
heartbeat's `FIX` once a beat is due; n's inconclusive scan needs `scan_age_s 600` with `refuse_scan`), f's second half
(`router fix continuity`, the gate, not `zone link confirmed rest=nothing`), h (moved levels between Locates, GPS home
first), j (`acc` by the rows' range: 55 here; 158 without the router's entry; `at=zone acc=153`), o (`svc power reboot`
/ `svc power shutdown`: `adb reboot` records no shutdown), p (H1: a coarse fix that isn't surely outside decides
nothing).

Not run: k (above); d and e on `0a00aa512`; Android 17; the device row (the Jelly Star).

**After the run (2026-11-26):** finding 1 (row o: the off gap wiped by the power record's `clear()`) is fixed on `wifi-link-confirm` (a5a9406e6: the gap in its own DE prefs file `power_boot_gap`); rows o and p are not rerun - they are owed on 14 and 16, and on the Jelly Star (a reboot at home, then a Locate with GPS silent). Run on 14 and 16 in §6by (2026-11-26/27); the Jelly Star's stays owed.

## 6bt. Phase D: Android's PIN lock is the only lock (design 132 §11, D7) - run 2026-11-26 (branch `gate-phase-d`)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bx. Zones shared by the family, picked per phone (design 135, branch `family-zones`) - run 2026-11-26/27 (§6by)

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (a run whose rows are all recorded).

## 6by. The night gate after the post-release chain (branch `gate-night-1126`) - run 2026-11-26/27

Moved to `docs/testing/emulator-runs-archive.md` on 2026-11-27 (its open rows are under "Owed rows").

## 6bz. Storytel books sealed on the phone (design 117, branch `music-117-seal`) - written, not run

`docs/design/117-storytel-sealed-books.md` §14 (the design named this §6ba, which another section had taken).
**Music app only**; it waits for the owner's OK ("Emulator: ask first"). On **API 34 and API 37** (the Keystore varies
by version; the images of 2026-11-16), the debug music build, unmanaged on the debug feed as in §6i part A, the fake
with two books: `python3 -I scripts/fake-storytel.py --books 111:20,222:5` (entry 5, `--print-listing 5`, `storytel_base`
in `config.json`). Logs: `adb logcat -s VibbMusicDebugFeed BookKeys Sync WM-WorkerWrapper ExoPlayerImpl`; the fake
logs every Range. The spans are pulled with `adb exec-out run-as me.vibb.music.debug tar c no_backup/media > media.tar`
and checked on the VM with a few lines of Python: for each `*.exo` file the share of zero bytes (the fake serves silent
WAV, mostly zeros; AES output has about 0.4 %).

| # | Step | Expect |
|---|---|---|
| 1 | **Every span sealed** (QA M5(c)): download both books; pull every span | `BookKeys` logs `seal level 1` once (TEE; 2 is StrongBox, 0 software); every `*.exo` file, the last of each book included, has a zero-byte share under 1 %; none starts with `RIFF`; `exoplayer_internal.db` holds a 78-byte `vibb_seal` per book; `report.json` `audio_bytes` equals the plain sizes as before (the sealed copy is the same length) |
| 2 | Play 111, seek to the middle and to the last minute, lock the screen, wait out 10 minutes of background play | it plays from each seek with no gap or noise; the position and bookmark move as before; no `Playback failed` line |
| 3 | `--drop-at 40` (a fresh install's data cleared first): the first transfer of 111 is cut; the next job resumes it | the fake logs `206 Range bytes=<N>-` with a fresh token, never a 200 from 0; the book completes and step 1's check passes on all its spans |
| 4 | **The update from before**: install `main`'s debug music build from before 117, download both books (plain: step 1's check fails on them), then install this build over it and sync | `Sync` logs `Sealed books: dropped 2 plain, 0 lost`; both rows go, both books download again sealed (the fake logs two new mints and 200s from 0), step 1 passes; the bookmark of 111 stays where it was |
| 5 | **A lost key**: with both books on the phone and 111's bookmark at 5 min, `adb shell am broadcast -n me.vibb.music.debug/me.vibb.music.debugfeed.DebugSealReceiver` (deletes the current `music_books_<id>` and ends the process), open the app and tap 111 | "Ikke lastet ned" at once (no `ExoPlayerImpl` retries at 1-3 s); a sync runs: `Sealed books: dropped 0 plain, 2 lost`; the job downloads both again (two mints) under a new key id (`run-as ... cat shared_prefs/music_seal.xml`: `seal_kek_id` changed, the old canary gone after the next clean sync); 111 then plays from 5 min |
| 6 | Step 5 with "Download on mobile data" on and the emulator on cellular only (`adb shell svc wifi disable`) | the books come back over mobile data (the parent's rule, §16 ASK 1); with the switch off they wait for Wi-Fi and the report's `waiting` says `wifi` |

On the **Jelly Star** after the release: `seal level` logged once (TEE expected), and a 10-hour book played and sought.

## 6ca. The gate of 2026-11-27 (branch `gate-1127`)

Gate A, on **Android 14 only** (`compat34`, Google APIs, port 5556, `-wipe-data`) with the owner's OK for the day;
gate B ran Android 16 (§6cb, branch `gate-1127b`). `main` at `e9875c082`: the debug launcher and the debug music app
(`assembleDebug`, `-PwarningsAsErrors=true`), and a debug server from the same tree (`--features emulator_keys`,
`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` of that launcher, admin `127.0.0.1:3140`, phones `10.0.2.2:3141`). The PWA set up
by a small private script (sign-in with TOTP, the phone, its Foreldre-PIN, a typed code), not by clicks.

### Row 3: §6bz Storytel sealed books (design 117) on Android 14

The music app alone on a wiped image (not managed), the debug feed as in §6i part A, `fake-storytel.py --books
111:20,222:5` (entry 5). Spans pulled with `run-as ... tar c no_backup/media`, each `*.exo` checked for its share of
zero bytes and a `RIFF` start; `exoplayer_internal.db`'s content metadata parsed for `vibb_seal`. For row 4 the debug
music build of `98476b4f9^1` (main just before the merge of `music-117-seal`).

| # | Android 14 (`compat34`) |
|---|---|
| 1 | **Pass.** Both books downloaded (`have 2, want 2`); all 5 spans 0.39 % zero bytes, none starts with `RIFF` (the last span of each book included: 4 271 404 and 5 000 044 bytes); each book's index row holds a 78-byte `vibb_seal` (`VBSL`) beside `exo_len`, keys `sealed:7ac843e9f5d4:111`/`:222`; `report.json` `audio_bytes` 25 000 088 = the two plain sizes. `BookKeys` logs **`seal level 0`** once per process: the emulator's Keystore is software (as §6bm read the image's chain, "Software only"); the TEE's `1` is the Jelly Star's to confirm, as the section says |
| 2 | **Pass** (what the emulator can show). Played 111 from the tile, seeked to the middle (3:51) and to the last minute (6:50): playing from each, no `Playback failed` or playback exception; then from 0:06 with the screen off (`Asleep`): 5:03 after 5 minutes, through the end of 111 into 222, `STOPPED` at 222's end after ~9.5 minutes; no new `asset`/`cdn` line on the fake while playing; both positions `done`. "No gap or noise" is not audible here (`-no-audio`, silent books) |
| 3 | **Pass.** Fresh data (`pm clear`), `--drop-at 40`: both first transfers cut (`dropped at 40 %`, 8 000 017 and 2 000 017 bytes); 2 minutes later new mints (`FAKE-SIG-3`/`-4`) and `206 Range bytes=8000017-` / `bytes=2000017-`, never a 200 from 0; 7 spans, all 0.38-0.40 % zero bytes, no `RIFF`, 25 000 088 bytes in all |
| 4 | **Pass.** The pre-117 build: both books plain (every span 100 % zero bytes, the first of each book `RIFF`), 111's bookmark at 2:05; this build installed over it, the sync at the update: `Sealed books: dropped 2 plain, 0 lost`, two new mints and two `200` from 0, all 5 spans 0.39 %, no `RIFF`, rows `sealed:...`; the bookmark of 111 still 125 747 ms |
| 5 | **Pass, with the row's tap step unreachable.** 111's bookmark at 5:04; `DebugSealReceiver` ("the current key deleted; the process ends"): WorkManager restarts the process at once for its unfinished work and the sync at that start runs 0.4-2.5 s after the kill - `Sealed books: dropped 0 plain, 2 lost` - before the app could be opened, so the tile never offered a lost book (with the fake down, and again in airplane mode: the Storytel tile is gone, nothing to tap; no `ExoPlayerImpl` retry or `Playback failed` line in any try). Two mints under a new key (`seal_kek_id` `d96575da` -> `f8db7327`, later `18a181e5`), the old canary gone after the next clean sync (one `seal_canary_*` left); 111 then played from 5:06 |
| 6 | **Pass.** Wi-Fi off (`svc wifi disable`, the default network `CELLULAR`, `wifi_on 0`). The switch off (`download_mobile_data: false`): the key lost, `dropped 0 plain, 2 lost`, no mint in 40 s, `report.json` `waiting: wifi`. The switch on: two mints and two `200`s at once over mobile data, `have 2`; 111 from its bookmark (5:22) |

Not on the emulator, as the section says: the TEE's `seal level 1` and a 10-hour book on the Jelly Star.

### Row 1: the glance after repeated plug-ins on Android 14 with the merged build (PLAN 1i, `glance-replug`)

"Row 5 again" (§6bl) on `compat34` from `-wipe-data`: §4's adb lines, enrolled with a typed code on the server above,
Barne-PIN 2580 from the PWA (`AndroidLock: apply: managed=true kidPin=true secure=true face=true`), the setup flow
finished (`mLockTaskModeState=LOCKED`), the glance Android's face. The night's Android 14 face rows were the first
round's (`4b593eb73`); these are main's merged code (the budget per power change, the plug-in's wake rule;
`re-front #1 of the power change` exists only there). Logs `AndroidLock`, `PinLock`; `dumpsys window` for
`mKeyguardOccluded`; `dumpsys activity broadcasts history` for our `ACTION_POWER_CONNECTED` delivery.

**The blocked start on this image:** `am start -a android.intent.action.SET_WALLPAPER` opens the chooser
(`android/com.android.internal.app.ResolverActivity`: the image has two pickers), not `BlockedAppActivity` - a first
pass with it was set aside. The rows ran with `am start -n com.android.wallpaper.livepicker/.LiveWallpaperActivity`:
Android answers with `BlockedAppActivity` (`onActivityRestartAttempt: ...BlockedAppActivity`), as the section means.

| # | Android 14 (`compat34`), main `e9875c082` |
|---|---|
| 5b | **Pass.** Three cycles 4 s apart on one screen-on: `re-front #1 of the power change (the glance paused)` each time, ~200 ms after the pause, `mKeyguardOccluded=true` after every cycle |
| 5c | **Pass** at 0.05, 0.08 and 0.12 s. `re-front #1`, `#2` (the screen-on's cap), then the start and the plug-in: our delivery `scheduled +489ms`/`+488ms`/`+482ms` (the deferral, at 0.05 s too on 14), the pause's check met the cap, the look's `power changed` ~1.6 s after the pause and `re-front #1 of the power change (the glance stopped)`, occluded |
| 5d | **Pass.** `re-front #1`, `#2`, the third: paused, stopped, no re-front - Android's lock screen until a screen cycle, no loop |
| 5e | **Pass.** Screen off 3 s, the wake, the start, 0.6 s, the plug-in: `The lock left the front just after a screen-on: probably Emergency SOS`, `Lock yields to an exempt screen: sos`, no `power changed` after it |
| 5e' | **As designed.** `sleep 0.25`, twice: the stamp (`scheduled +500ms`) 777 and 752 ms after that single press's wake - read as the plug-in's wake: the SOS mark first, then `power changed` and `re-front #1 of the power change (the glance stopped)` 1.5 s later. The edge the note names, as on 16 |
| D2 | **Pass**, two tries. Dark 4 s, then the plug-in, `KEYCODE_WAKEUP` and the start in one line. Try 1: our delivery `+14ms`, before the pause - the budget took the loss at once (`re-front #1 of the power change (the glance stopped)`). Try 2: `+122ms`, scheduled 2 ms before the pause's log line (the receiver ran after it) - `probably Emergency SOS` / `yields ... sos`, then the look cleared the plug-in's own mark (`power changed`, `re-front #1 of the power change`), occluded ~1 s later, as 16's D2 |
| D3 | **Pass**, four tries (sleep-wake 30, 100 and 290 ms with the press 0.5 s after the plug-in's wake; 100 ms with 2 s): `probably Emergency SOS`, `Lock yields to an exempt screen: sos`, no `power changed`, no re-front after it |

Nothing failed. The Jelly Star's own wake on USB and Unihertz's `ChargingDialog` stay the owner's plug test after the
next rc.

### Row 2: design 139 step 0's probe on Android 14

Design 139 (`docs/design/139-app-groups.md` on branch `app-groups-design`) §8 row 1 / §9 step 0, on 14 only (gate B:
16). The probe is a throwaway local branch `probe-139` (`ffd5fbe2c`, plus an uncommitted deprecation `@Suppress`; not pushed): main
plus `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions"/>` and
a debug-only receiver `UsageProbeReceiver` that logs the access (`unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS)`) and every
`queryEvents(since, now)` event under `UsageProbe`. Installed over the enrolled phone of row 1 (`adb install -r`, still
Device Owner), a pause started from the PWA (`/start-pause`, the phone unlocked once: "Open until ...", the kiosk
`NONE`). Test apps: Google Clock and Chrome. Times below are the events' own (`HH:mm:ss.SSS`).

**The access.**
- `dumpsys package`: `PACKAGE_USAGE_STATS` requested, `granted=false`; `cmd appops get ... GET_USAGE_STATS`: "No
  operations" before the grant.
- `am start -a android.settings.USAGE_ACCESS_SETTINGS -d package:me.vibb.launcher.debug` opens **the app's own page**
  (`Settings$AppUsageAccessSettingsActivity`: "Vibb One (debug)", switch "Permit usage access"). Without `package:`:
  `Settings$UsageAccessSettingsActivity`, "Usage access", Vibb One listed "Not allowed". (14 names it "Usage access",
  not "App usage data".)
- The switch was enabled, not greyed (restricted settings are 15+; nothing on 14). On: `GET_USAGE_STATS: allow`, the
  probe's `access mode=0` (`MODE_ALLOWED`), and `queryEvents` returns every app's events.
- `unsafeCheckOpNoThrow` is deprecated at the launcher's compileSdk 36: with `-PwarningsAsErrors=true` the probe failed
  to compile until it had `@Suppress("DEPRECATION")` - `UsageAccess` needs the same (precedent:
  `PlatformRefusals.exemptFromSuspension`).

**The events** (the kinds §4.1 names; `CONFIGURATION_CHANGE`, `USER_INTERACTION`, standby buckets and services left out):

| Case | What `queryEvents` gave on 14 |
|---|---|
| An ordinary open and close (Clock from Home, then Home) | Home `PAUSED`, Clock `RESUMED` 46 ms later, Home `STOPPED`; Home pressed: Clock `PAUSED`, Home `RESUMED` 1 ms later, Clock `STOPPED` 0.4 s later |
| A same-class relaunch (Clock in front, `am start -n ...DeskClock -f 0x10008000`, NEW_TASK + CLEAR_TASK) | Clock `PAUSED`, Clock `RESUMED` 1 ms later (the new instance), Clock `STOPPED` 0.5 s later (the old one) - the §4.1 rule (a RESUMED after the last PAUSED: the STOPPED is ignored) holds. CLEAR_TOP or MULTIPLE_TASK onto the top instance: no event; night mode on and off: only `CONFIG` (Clock handles it without a relaunch) |
| `kill -9` of the front app, Vibb One below (`adb root`) | Clock `STOPPED` and Vibb One `RESUMED` in the same ms, **no `PAUSED`**: the STOPPED is ignored by §4.1, our RESUMED turns Clock off - correct |
| `am crash` of the front app, Vibb One below | Clock `STOPPED`, Vibb One `RESUMED` 5 ms later, no `PAUSED` - the same as kill -9, correct |
| **`kill -9` of the front app with another app's task below** (Chrome, then Clock over it) | Clock `STOPPED` and **Chrome** `RESUMED` in the same ms, no `PAUSED`. By §4.1 the STOPPED is ignored (Clock's last event was a RESUMED) and the RESUMED is not ours, so **Clock stays on** until a screen-off, keyguard or our own RESUMED |
| **`am crash` with another app below** | Clock `PAUSED`, Chrome `RESUMED` 1 ms later, Clock `STOPPED` **10.6 s later**: on-paused (counting) for those 10 s |
| Split screen (`WMShell splitscreen moveToSideStage <Clock's task> 0` beside Chrome) | Clock `RESUMED`; Chrome no event - **both RESUMED at once** (multi-resume), no `PAUSED` for the unfocused one; taps moving the focus between them: no event. Home from split: both `PAUSED`, Vibb One `RESUMED`, both `STOPPED` |
| PiP (`am start --windowingMode 2` of Chrome; kiosk off) | Chrome's activities `PAUSED` (and the one left `STOPPED`) - the visible PiP activity's last event `PAUSED`: on-paused |
| **PiP across screen off and on** | Screen off: `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, the PiP activity **`STOPPED`**, our glance `RESUMED`/`PAUSED`/`STOPPED`. Screen on: `SCREEN_INTERACTIVE`, the glance `RESUMED` - nothing for Chrome while the keyguard is up. After the unlock: `KEYGUARD_HIDDEN`, then the PiP activity **`PAUSED`** 22 ms later (the PiP window visible again) - **yes, an event marks it visible again**: a `PAUSED`, which §4.1 reads as on-paused |
| A suspended package, kiosk off (`pm suspend` of the front Clock) | Android itself finishes it (`wm_finish_activity ... suspended-package`): Clock `PAUSED`, the task below (`HomeActivity`) `RESUMED` in the same ms, Clock `STOPPED` 0.4 s later; the process lives on. The PiP Chrome suspended: finished the same way, its activity `STOPPED` (off) |

**What it settles for §4.1 and §8 row 1 (Android 14):**
- Relaunch and PiP: the fold's rows hold as written (the relaunch's late `STOPPED` is ignored; PiP comes back as a
  `PAUSED` after `KEYGUARD_HIDDEN`).
- Kill and crash **with Vibb One below** are covered by our own `RESUMED`.
- **Gap (Medium, design):** kill -9 of the front app with **another app below** leaves it "on" indefinitely (a
  `STOPPED` with no `PAUSED` since its `RESUMED`, which §4.1 ignores, and someone else's `RESUMED`). With the kiosk on
  the task below is usually Vibb One; with several pinned apps' tasks, or the kiosk off, it isn't. A narrow rule that
  fits every case seen: a `STOPPED` of a package that is **on** with no `PAUSED` since its last `RESUMED`, arriving
  with (same ms) another package's `RESUMED`, turns it off - the relaunch always has its `PAUSED` first. Not "any other
  RESUMED turns everything off": split screen keeps both RESUMED with no PAUSED.
- **Small (Low):** `am crash` with another app below counts ~10 s on-paused until the late `STOPPED`.
- Split screen on 14 gives no `PAUSED` at all (both `RESUMED`), not the on-paused state §4.1 assumed; the union still
  counts each app, so nothing changes in the fold.
- A suspended package's front activity is finished by Android with the next task resumed (Home when Vibb One is
  below), so D7's "Home comes forward" needs our start only when another app's task is below.

Gate B's §6cb has the same probe on 16 (and the restricted-settings check, 15+).

## 6cb. The gate of 2026-11-27, Android 16 (branch `gate-1127b`)

On **Android 16 only**, `jelly36` (Google Play image, `-wipe-data`, port 5560), with the owner's OK for the day,
08:30-09:57 UTC; Android 14 is gate A's §6ca (`compat34`). `main` at `e9875c082`: gate A's debug launcher
(`assembleDebug`) and its debug server 0.94.1 (`--features emulator_keys`) run as this gate's own copy on
`127.0.0.1:3200` with its own data (`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` the debug signer, the debug component). The
phone: §4's adb lines, Telefon 1 made with "Blokker USB-feilsøking" off and Foreldre-PIN 246813, Koble til with a typed
code, the setup flow skipped through; no Barne-PIN. Location Regularly 10 min, Google's `network` provider off (a test
provider, again after each boot), the satellite feed and made-up scans of `PlacesDebugReceiver` as §6bw. Logs and the
hand-made probe app in the session's `scratchpad/gb/` (not committed).

### Row 1: §6bw row p (design 138, the continuity gate after a cell fix) on Android 16

Owed since §6by (both images: no placed cell). The gate's candidates are the newest reported fix and the last
satellite fix (`WifiPlaces.routerOnlyFix`, `GateFix`), so the row needs the modem's cell **placed** (an own cell row is
trusted at `CELL_OCCASIONS` = 3, an occasion per 30 min) and the satellite fix where the row wants it - and a
satellite feed teaches the registered cell wherever it runs. Setup on this phone: Hjem (59.9139, 10.7522, 150 m) and
Borte (59.9274, 10.7522, 150 m, 1.5 km north), both on Telefon 1; the made-up router `Net-C` (`00:00:5e:00:70:01`, psk,
id 9) confirmed for Hjem, its own Wi-Fi rows left untrusted (one occasion; the router fix answers `at=zone acc=152`).
**The cell placed in real time** (`jelly36` has no root for clock steps; the modem's registered cell is NR, `cells
read=1 usable=1 registered=1 nr=1`): occasion 1 at home (08:45, a Locate with the feed: `taught aps=1 new=1`), 2 and 3
600 m north (09:16, 09:46; one unknown AP `X1` heard, not connected, the feed and a Locate: `taught aps=2`), so its row
sits between: the cell fix came out at 59.917005 (345 m north of Hjem's centre), `acc=500`. **Satellite fixes that must
not teach the cell** ran with airplane mode on and Wi-Fi back on (`cmd connectivity airplane-mode enable`, `svc wifi
enable`: the server stays reachable, `cells read=1 usable=0`). A cell-only Locate: GPS silent, one never-taught AP `U1`
heard, not connected. Logs `adb logcat -s WifiPlaces`, the fixes from the server's `device_locations`.

| Half | Result |
|---|---|
| A coarse fix that isn't surely outside says nothing | **Pass.** The satellite fix at home (airplane mode, 09:47); then the cell-only Locate: `cell fix acc=500 REPORT`, `attempt path=LOCATE result=vibb_cell`, the row `vibb_cell` 59.917005, 500 m - its centre outside Hjem (345 m > 150), not surely outside (345 - 500 < 150); a heartbeat reported the same cell fix again. Then `Net-C` alone, connected, GPS silent, Locate: `router fix fix at=zone acc=152`, `zone link confirmed rest=unknown`, `wifi fix check=link step=USE`, a `vibb_wifi` `link` fix at Hjem - the cell fix skipped, the satellite fix at home decided (H1) |
| A cell fix surely outside - `continuity` | **Pass.** `Net-C` moved to Borte (Fjern on Hjem, Bekreft on Borte - Borte's page showed the Bekreft row only once a satellite fix there was reported). The satellite feed at Borte with airplane mode on (`gps` 59.9274, 10 m, `taught=false`); the control, still in airplane mode: `Net-C` alone, Locate: `router fix fix at=zone acc=152`, a `link` fix at Borte. Airplane mode off, the cell-only Locate 70 s later: `cell fix acc=500 REPORT`, `vibb_cell` 59.917005 - 1.16 km from Borte's centre, surely outside (1160 - 500 > 150). Then `Net-C` alone, Locate: **`router fix continuity`**, and the attempt fell back to the cell fix (`result=vibb_cell`); no `link` fix |

Noted (environment, no code): with the `network` provider off, Google Play services' `fused` provider re-emitted the
last mock satellite position as a `fused` fix of 100 m a few minutes after a feed (`dumpsys location`: `last
location=Location[fused 59.927400,10.752200 hAcc=100.0 ... mock]`), and the launcher's passive feed kept and reported it
(`ZoneWatch: Passive fix kept provider=fused sig=-/-/-@100`). It came after each half's router fix, so it decided
nothing here; a later half on this image should read the newest rows with that in mind.

### Row 2: design 139 step 0's probe (§8 row 1) on Android 16

`docs/design/139-app-groups.md` (branch `app-groups-design`) §8 row 1 and §9 step 0, on `jelly36` only (Android 14 is
§6ca's). Vibb One: first `main`'s debug launcher (no `PACKAGE_USAGE_STATS`), then gate A's throwaway probe build installed over it
(`probe-139` `ffd5fbe2c`: `main` + the declaration + a debug `queryEvents` dump, `UsageProbeReceiver`; still Device Owner).
Most events were read first by a throwaway app made by hand outside the repo (`probe.usage`, "Usage probe": `aapt2`, `javac`,
`d8`, `apksigner` with the machine's debug key; never on a real phone): it declares `PACKAGE_USAGE_STATS` and
`QUERY_ALL_PACKAGES` as the launcher would, an exported receiver logs `UsageStatsManager.queryEvents(now - N s, now)`
through the public `UsageEvents.Event` getters (type, package, class, time), and its one activity supports
picture-in-picture. On the managed phone it was `adb install`ed, ticked on the phone's Apps page and let out with
"Allow these apps" (`outside_play`, §3b). Kiosk off = a parent pause (`/devices/1/start-pause`, lock task `NONE`).

| Part | Result |
|---|---|
| Vibb One on "App usage data" during a pause | **Pass** with gate A's probe build (below). In the pause, Settings › "App usage data" lists "Vibb One (debug)" "Not allowed" among the apps that declare the permission; its page has the switch live; on: `cmd appops get me.vibb.launcher.debug GET_USAGE_STATS` -> `allow`. With `main`'s build (no declaration) Vibb One is not listed, and `ACTION_USAGE_ACCESS_SETTINGS` with `package:me.vibb.launcher.debug` still opens its own page with the switch greyed (`enabled=false`, a tap does nothing) - so the declaration is what puts it there. `package:<pkg>` opens the app's own page on 16 |
| Restricted settings (ECM) on 16 | **Not guarded as installed here; the QR-provisioned, self-updated case Blocked.** The adb-installed Vibb One (`packageSource=1`, initiator `com.android.shell`) and the probe app: switches live, no "Restricted setting" dialog. Vibb One's self-update (`AppInstaller.installSilently`, `launcher/app/src/main/java/com/kidslauncher/mdm/server/AppInstaller.kt:77-82`) sets no package source (`PACKAGE_SOURCE_UNSPECIFIED`) and QR provisioning installs through Android's provisioning - neither path runs on this emulator (no QR, no signed release to self-update to), so that stays the owner's device check after the release. The probe app couldn't stand in: its own session install (source 4, `DOWNLOADED_FILE`) stops at `STATUS_PENDING_USER_ACTION` under Vibb's `no_install_unknown_sources` |
| The grant | **Pass.** The probe's page in the pause: the switch on, `cmd appops get probe.usage GET_USAGE_STATS` -> `allow`. Before it (`default`, op mode 3) `queryEvents` returned **no events and no exception** - the step's `UsageAccess` must read the app-op, an empty answer says nothing |
| Ordinary open and close | **Pass.** `PAUSED` Home (`me.vibb.launcher.debug` `HomeActivity`), `RESUMED` probe 19 ms later, `STOPPED` Home; Home key: `PAUSED` probe, `RESUMED` Home 1 ms later, `STOPPED` probe 0.4 s later. Third-party callers see Vibb's own Home and the `android` events (`SCREEN_*`, `KEYGUARD_*`, `DEVICE_*`) |
| Same-class relaunch | **Pass - the order H1 needs.** A new instance of the same class over the old one (`am start -f 0x18000000`, a new task; and `-f 0x10008000`, clear task): `PAUSED` (old), `RESUMED` (new) 8-13 ms later, then the old instance's `STOPPED` 0.6 s **after** that `RESUMED`, same package and class (`getInstanceId` hidden: not told apart). A fold that ended the span at any `STOPPED` would cut a running app; §4.1's rule (a `STOPPED` ends a span only when no `RESUMED` came after the last `PAUSED`) keeps it |
| `kill -9` of the front app | **Pass, with what it means.** `run-as probe.usage kill -9 <pid>` (the shell's own `kill` is refused on this user build; the probe is debuggable): `STOPPED` probe 0.1 s after the kill with **no `PAUSED` before it**, then `RESUMED` Home 1 ms later. Android writes the stop itself on 16 |
| `am crash` of the front app | **Pass.** The same as `kill -9`: `STOPPED` (no `PAUSED`), `RESUMED` Home 1 ms later; no crash dialog stayed up |
| Picture-in-picture across screen off and on (kiosk off) | **Pass - an event marks it visible again.** Entering PiP: `RESUMED` probe, then `RESUMED` Home and `PAUSED` probe (on-paused, the PiP window visible). Screen off: `SCREEN_NON_INTERACTIVE`, then `STOPPED` probe (cut at screen off), `KEYGUARD_SHOWN`. Screen on: `SCREEN_INTERACTIVE` and nothing for the probe while the keyguard is up; at the unlock `RESUMED` Home and **`PAUSED` probe with no `RESUMED` before it** (the app itself ran `onResume`, `onPause`), then `KEYGUARD_HIDDEN`. Twice, the same. So a `PAUSED` that arrives while the package is off means on-paused again (PiP back on screen) |
| Split screen | **Pass.** Recents (Quickstep's fallback over Vibb's Home) › the probe's menu › "Split screen" with Settings: both `RESUMED` in the same ms (multi-resume); Home key: `PAUSED` both, `RESUMED` Home, `STOPPED` both. (`am start --windowingMode 6` alone gives a multi-window task, not a split pair) |
| A visible activity suspended, kiosk off | **Noted.** `pm suspend probe.usage` with the probe in front: `PAUSED` probe and `RESUMED` Home in the same ms, `STOPPED` probe 0.3 s later - an ordinary close; the task is gone (not in `dumpsys activity`), the process stays (`pidof`). Unsuspended, nothing comes back |
| A reboot (`svc power reboot`) | **Noted.** Before: `PAUSED` Home, `DEVICE_SHUTDOWN` 20 ms later (no `STOPPED`); after: `SCREEN_INTERACTIVE`, `KEYGUARD_SHOWN`, `DEVICE_STARTUP`, then the launcher's own events |

**Vibb One's own read** (gate A's probe build, `UsageProbeReceiver`, access granted as above): an open and close, a `kill -9` and PiP across screen off and on gave the same events in the same order as the probe app's - `STOPPED` with no `PAUSED` at the kill, the lone `PAUSED` of the PiP app at the unlock. So the hand-made app's reads stand for Vibb One's.

### Summary

| Row | Result |
|---|---|
| 1. §6bw row p on Android 16 | **Pass**, both halves (`router fix fix` after a cell fix not surely outside; `router fix continuity` after one surely outside). §6bw row p on Android 14 stays §6ca's; the Jelly Star's device row stays owed |
| 2. Design 139 step 0 on Android 16 | **Pass** for the page (with the declaring build), the grant and every event case; **Blocked**: restricted settings (ECM) for a QR-provisioned, self-updated Vibb One - the owner's device check after the release |

What step 0 settles for design 139 §4.1 on 16 (the 14 half is §6ca's): a same-class relaunch's `STOPPED` comes after
the new `RESUMED` (the H1 rule is needed); a killed or crashed front app gets a `STOPPED` from Android with no `PAUSED`
(so our own `RESUMED` ending an on span is a fallback, not the only end); PiP is on-paused, cut by its `STOPPED` at
screen off, and a lone `PAUSED` at the unlock marks it visible again; split screen resumes both apps in the same ms;
suspending a visible app closes it like an ordinary close; a reboot gives `DEVICE_SHUTDOWN` after the last `PAUSED`
(no `STOPPED`) and `DEVICE_STARTUP`. Without the grant `queryEvents` returns nothing, no exception.

No product code changed. The emulator and the server were stopped at 09:57 UTC.

## 6cc. App groups with a shared daily limit (design 139 part A, branch `app-groups`) - written, not run

The rows are design 139 §8 (`docs/design/139-app-groups.md`, on `app-groups` since its build), rows 2-18 on Android 14
(`compat34`) and 16 (`jelly36`); row 1 (step 0) is §6ca/§6cb's. Setup for every row: the debug build, an allowed F-Droid
app and a second one, a group "Test" of both with today's limit 2 min (Device › Time Rules › Appgrupper › Legg til
gruppe), and "App usage data" on - the setup flow's step `usage_access` during a pause (its page with `package:`), or
`adb shell appops set $P GET_USAGE_STATS allow` as the shortcut (§4). What the build adds for the gate:
- the PWA's minutes are `time_state.groups[].used_minutes` (Device › Time Rules' row value, "40 av 60 min");
- the fold logs as `AppGroupUsage` (`adb logcat -s AppGroupUsage ScreenTimeTracker`); the debug build's `late event`
  line (row 18) is there too, and "A group closed: Home over <pkg>" at the cut (rows 2, 9);
- the closed set and the dim: `dumpsys package <pkg> | grep -i suspended`, Home's tile at 40 % alpha, a tap opens the
  sheet ("Brukt opp i dag", "Ikke i dag", "Kan ikke telle nå");
- row 10's title: `dumpsys notification --noredact | grep -A3 1002` ("Test: 3 min igjen").


## 6cd. Design 139 gate on Android 14 (branch `gate-139a`)

Gate A of design 139 §8 rows 2-18 (§6cc), on **Android 14 only** (`compat34`, Google APIs, port 5556, `-wipe-data`),
2026-11-27 13:50-14:51 UTC, with the owner's OK for the day; gate B runs the same rows on 16 (`jelly36`). `main` at
`d4ebd4886`: the debug launcher (`assembleDebug`, `-PwarningsAsErrors=true`) and a debug server from the same tree
(`--features emulator_keys`, admin `127.0.0.1:3140`, phones `10.0.2.2:3141`). The phone: §4's adb lines (with
`GET_USAGE_STATS allow`), Telefon 1 with Foreldre-PIN 246813 and Barne-PIN 2580 from the PWA, Koble til with a typed
code, the setup flow skipped through (no `usage_access` step: the grant was in place). The PWA driven by a small
private script, not by clicks.

**Test apps** (hand-made, not committed; sideloaded, so let out with "Allow these apps"): `probe.gate.a` ("Gate A")
and `probe.gate.b` ("Gate B"), targetSdk 33, the group's two apps. Each has a second activity `gate.B` with
auto-enter PiP, a test call (a CALL-category notification with a full-screen intent, "answer" a foreground service
whose notification is of the CALL category, so `VoipCalls` sees RINGING then IN_CALL), a switch for
`MODE_IN_COMMUNICATION`, and a `queryEvents` dump (A only, its own `GET_USAGE_STATS`). Chrome is the non-group app
below. Group "Test" of A and B; a group deleted and added again (ids 1-5) for a fresh limit, so several rows ran from
a new group's first fold. Minutes read from the ledger (`run-as ... shared_prefs/time_rules_state.xml`,
`group_used_ms`), the PWA's Device › Time Rules row ("2 of 17 min"). `AppGroupUsage` logged continuously from 14:08 to
the end (an `adb logcat` loop across the reboots; earlier lines were cleared by mistake). Event times below are the
phone's (`HH:mm:ss.SSS`); the phone ran 0.4-5.8 s behind the VM.

**The screen timeout:** Vibb sets Android's maximum time to lock to the screen timeout, which turns "stay awake while
charging" off; the rows ran with the PWA's screen timeout at 10 min (row 15 at 15 s).

| # | Android 14 (`compat34`) |
|---|---|
| 2 | **Pass.** Gate A from Home, limit 2 min: `A group closed: Home over probe.gate.a`, A `PAUSED` 14:02:04.168 (~3.5 s after the limit, the read window plus the timer), Home in front, A and B `suspended=true`; both tiles dimmed, a tap opens the sheet "Test" / "Used up today" / Close (the phone ran in English). The first try was cut short by the then 60 s screen timeout: 59.2 s counted, nothing during the 2 min dark |
| 3 | **Pass.** A 14:02:47.631-14:03:40.826 (53.2 s), B from 14:03:44.628: the ledger grew by exactly the two spans (124 297 -> 205 428 ms at the fold of 14:04:12). B in front, power off 14:04:44.941 (`SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`), woken at 14:05:26 and left on the PIN pad 30 s, unlocked 14:05:57.708: 217 929 ms before and after - the dark and the PIN pad counted nothing |
| 4 | **Pass.** `POST /lifts what=group-1 minutes=15`: A and B unsuspended within a second, `group_extra_minutes {1: 15}`, `applied_group_lifts [1]`, Gate A opened; Activity "Test: 15 min extra", Time Rules "2 of 17 min" |
| 5 | **Pass.** `appops set ... GET_USAGE_STATS ignore`: A and B suspended at once, the sheet "Can't count now", Device › Time Rules the orange "App usage data is off" and the row "Can't count"; `allow` again: both open within seconds, the row back to "3 of 17 min" |
| 6 | **Pass, with row 18's finding.** The setup hold (enrolment, the flow on screen, Gate A in front 13:53:28-13:55:12, 1 min 43 s): no group minutes. Group used up, "Pause all restrictions" (`/start-pause`): A and B unsuspended at once. **First try:** A in front 14:14:21.597-14:14:56.840 during the pause - **0 ms counted**: the fold that read [14:14:20.187, 14:14:35.300) got none of its 5 events (A's `RESUMED` among them), the debug re-read found them (`late event: 5 more`, row 18). **Second try** (A brought front at ~14:15:25): 64 064 -> 122 076 ms at 14:16:23 - counted. "Lås igjen" (`/end-windows`): A suspended, `A group closed: Home over probe.gate.a`, Home in front |
| 7 | **Pass.** Group used up; a school rule "Skole" 13:00-17:00 every day with Gate A as its exception: the rule screen's rows Phone book and Messages, no Gate A, A suspended. The rule deleted, Bedtime 13:00-11:00 with Gate A allowed: the same. Control: the group lifted (+15) under that bedtime: a "Gate A" row appears and A opens, B (not allowed at bedtime) stays suspended |
| 8 | **Pass.** A in front, ring (`VoIP NONE -> RINGING, pinned probe.gate.a`) and answer (`RINGING -> IN_CALL`) 1 min before the limit; at the limit B suspended, A stayed in front and `suspended=false`. Hang-up 14:10:51.226: `pinned -`, then `A group closed: Home over probe.gate.a` 14:10:51.568, A `PAUSED` 14:10:51.461. The test call sets no audio mode, so the phase fell to NONE 16 s after the answer (`IN_CALL -> NONE, pinned probe.gate.a`): the hold follows `pinnedPackage`, as D7 says. The audio mode alone: a new group of 1 min, A set `MODE_IN_COMMUNICATION` (`mAudioModeOwner` A's pid): no `VoipCalls` change, A cut at its limit (14:27:38) |
| 9 | **Pass.** Kiosk off (`kiosk_desired = 0` on the test server; the phone's lock task `NONE` after a reboot, below). Chrome, then Gate A over it, limit 3 min: at the cut A `PAUSED` 14:34:48.767, Chrome `RESUMED` 1 ms later (Android's own), Home `RESUMED` 14:34:49.270 (`A group closed: Home over probe.gate.a`) - Home in front, not Chrome. The PiP of a group app counts: row 17 |
| 10 | **Pass.** Limit 2 min, Gate A in front: the panel's title in the shade "Test: 2 min left" (en) |
| 11 | **Pass.** Gate A in front from 14:37:00.126, the launcher's process `kill -9`'d (`adb root`); the new process brought Vibb's lock (PinLockActivity, then the screen off - its own behaviour, not this design's). The ledger grew by exactly A's span from its `RESUMED` to its `STOPPED` 14:37:10.791 (10 665 ms), the dead process's seconds included |
| 12 | **Pass.** Kiosk off, Chrome below. `kill -9` of A: A `STOPPED` 14:38:19.822, Chrome `RESUMED` 1 ms later; the count stopped at once (+10 170 ms = A's `RESUMED` to its `STOPPED`, nothing in Chrome's 40 s after). `am crash` of A: A `STOPPED` 14:39:22.252 with no `PAUSED`, Chrome `RESUMED` 24 ms later; stopped at once (+10 440 ms) - not the 10.6 s on-paused of §6ca's probe |
| 13 | **Fail - the 2 s fallback is needed.** As row 12 with Chrome's process gone first (`am kill`, `pidof` empty). Three tries, A `STOPPED` (no `PAUSED`) to Chrome's cold-start `RESUMED`: **301 ms, 815 ms, 299 ms** - all over `KILL_PAIR_MS` (50). A stayed on in the fold's carry and was counted while dead: try 1 left 26 s at the next fold, and the dead A ran on through tries 2 and 3 and Home until the screen off at 14:42:41.615 (62 s more in one stretch). See the findings |
| 14 | **Pass.** `adb reboot` with Barne-PIN 2580, unlocked right after the boot, Gate A opened. Order: `SCREEN_INTERACTIVE`, BootCover `RESUMED`, `KEYGUARD_SHOWN`, BootCover `PAUSED`/`RESUMED` (all 14:31:18.239), `DEVICE_STARTUP` 14:31:18.492, `KEYGUARD_HIDDEN` 14:31:19.078, Home `RESUMED` 14:31:43.148, A `RESUMED` 14:31:43.808: A counted from its `RESUMED` (26 367 ms at the fold of 14:32:10). After the hard power-off (row 16): the same order, `DEVICE_STARTUP` 12 ms after `KEYGUARD_SHOWN`, `KEYGUARD_HIDDEN` 371 ms after it. On this `adb reboot` Android had saved its events last at 14:10:57: `DEVICE_SHUTDOWN` is stamped 14:10:57.877, 20 min before the reboot, and the 20 min of events between are gone - the boot-count reset at `from` (QA L3) handled it, nothing of it counted twice or lost from the ledger (folded live before the reboot) |
| 15 | **Pass as far as Android 14 lets it happen; the M2 sequence never occurs here.** Vibb sets Android's maximum time to lock to the screen timeout (`maximumTimeToUnlock=15000` with a 15 s timeout), so Android's lock delay is 0: twice, a wake 0.3-1.5 s after the timeout found the keyguard already shown (`KEYGUARD_SHOWN` 0.5-0.85 s after `SCREEN_NON_INTERACTIVE`, the PIN asked); the count stopped at the screen off. Without a Child PIN it can't be tried on a managed phone (`clear_kid_pin`: `not_removed`, design 132) |
| 16 | **Pass.** Gate A in front past two folds (555 815 ms at 14:46:44), `adb emu kill` at 14:47:00, off 84 s, a cold boot from the same data (`-no-snapshot-load`), unlocked, 70 s waited: 555 815 ms - the downtime and the 16 s after the last fold added nothing. No `DEVICE_SHUTDOWN` after the kill |
| 17 | **Pass.** Kiosk off. A's Main, then `gate.B` in its task and Home 0.25 s later: B entered PiP. Events: Main `PAUSED` 14:35:11.507, B `RESUMED` .510, Home `RESUMED` .838, B `PAUSED` .840, Main `STOPPED` 14:35:12.538 (after B's `PAUSED`). The carry kept A on-paused with `paused_class gate.B`, the PiP counted (+54 191 ms over 55 s), the PiP closed (`am stack remove`): B `STOPPED` 14:36:13.616 and the count stopped there (+9 553 ms, nothing after) |
| 18 | **Fail.** Three `late event` lines from 14:08 to the end: `5 more in [14:14:20.187, 14:14:35.300)` and `3 more in [14:17:14.797, 14:17:22.106)` - both at a "Pause all restrictions" start, when the launcher unhides and unsuspends every app at once; and `1 more in [14:28:46.169, 14:31:26.085)` - the window across the reboot (row 14). The first pause's 5 events were stamped 14:14:20.2-14:14:23.3 and missing from a read that ended at 14:14:35.3: stored ≥ 12 s after their stamps; the second's 3 (14:17:16.7-14:17:20.7) ≥ 3.4-7.4 s. Two of the five pause starts in the run had one. Row 6's first try is what a lost `RESUMED` does |

Not checked: the Jelly Star's restricted-settings guard (row 1, the owner's row).

### Findings

1. **Row 13: the kill rule needs its 2 s fallback** (Reproduced 3/3, Traced; Medium; small). A dead group app over an
   app whose process must cold-start gets its partner's `RESUMED` 299-815 ms after its `STOPPED`, outside
   `KILL_PAIR_MS` = 50 (`launcher/.../timerules/AppGroups.kt:19`, used by `pairOf` at `:283`), so the `STOPPED` is
   ignored and the app counts as on until its next event or the screen off - against the kid, a minute here. The
   design's own answer (§4.1, QA L2): the after-side becomes "the first `RESUMED` of another package with no event of
   X between, within 2 s"; the row then runs again on 14 and 16. Only with the kiosk off or another app's task below.
2. **Row 18: events stored long after their stamps at a pause start** (Reproduced 2 of 5 pause starts, Traced to the
   read window; Medium; small to medium). Android stored events 3-12 s after their stamps while the launcher let
   every app out at once; the fold's read window ends `SETTLE_MS` (2 s, `AppGroups.kt:15`) before now and the next
   read starts at its end (`AppGroupUsage.fold` -> `planRead`), so the events were lost for good: a lost `RESUMED`
   left Gate A uncounted for its 35 s in the pause (row 6, in the kid's favour); a lost `PAUSED`/`STOPPED` would count
   an app all session (against the kid). The debug re-read (`AppGroupUsage.kt:174`) caught all three. A longer
   `SETTLE_MS` only moves the edge; reading an overlap behind `from` again and skipping applied events by key (the
   machinery the backward clock step already has, `appliedKeys`, 10 min) would take such late events in.
3. **Seen, not traced (not this design):** after five pause starts and ends within 10 minutes (row 6 and the lag
   tries), Vibb's Home task was finished (`wm_finish_activity ... HomeActivity,clear-task-all` at a pause start), the
   stock Pixel launcher was in front, and Android's lock task stayed `LOCKED` with no task: Vibb's `stopLockTask`
   threw `SecurityException: Invalid uid, expected -1` and every start of Home or Chrome was refused as a lock task
   violation (101), with the kiosk off in the policy. The reboot of row 14 cleared it.
4. **Row 15's case is unreachable on a Vibb phone with a Child PIN** (Android 14): the lock delay is 0, so there is
   nothing to fix; gate B can say whether 16 differs.

## 6ce. Design 139 gate on Android 16 (branch `gate-139b`)

§6cc's rows (design 139 §8 rows 2-18) on **Android 16 only**, `jelly36` (Google Play image, `-wipe-data`, port 5560),
2026-11-27 13:50-15:05 UTC, with the owner's OK for the day; Android 14 is gate A's (`compat34`). `main` at `d4ebd4886`:
gate A's debug launcher and debug server 0.95.0 (`--features emulator_keys`) run as this gate's own copy (admin
`127.0.0.1:3200`, phones `10.0.2.2:3201`, own data). The phone: §4's adb lines (`appops set ... GET_USAGE_STATS allow`),
"Telefon 1" with Foreldre-PIN 246813, Koble til with a typed code, the setup flow skipped through, Barne-PIN 4826 (Android's
PIN), screen timeout 10 min (15 s for row 15), system language English (so the words below are the en copy). The group
apps are three hand-made test apps outside the repo, built like §6cb's probe (`aapt2`, `javac`, `d8`, `apksigner` with the
machine's debug key; debuggable, never on a real phone), let out with "Allow these apps": **X** `probe.usage` ("Probe X":
a PiP-capable activity, a second activity that enters PiP on Home, a fake VoIP ring/call - CALL-category full-screen
notification, a CALL-category foreground service, `MODE_IN_COMMUNICATION` with silence on a voice track - and §6cb's
`Dump` receiver), **Y** `probe.other` (the group's second app) and **Z** `probe.below` (not in a group; the app below
for rows 9, 12, 13; its `Dump` read the events). The group "Test" of X and Y was made anew per row (a new group starts
at 0), limit 1-5 min. Minutes from the ledger (`run-as ... shared_prefs/time_rules_state.xml`, `group_used_ms`, ms) and
the PWA's Time Rules row; events from `queryEvents` (`Dump`). "Kiosk off" = a parent pause (`/start-pause`, lock task
`NONE`), as §6cb. Logs and scripts in the session's `scratchpad/g139b/` (not committed).

| Row | Result on Android 16 |
|---|---|
| 2. Use the app 2 min | **Pass.** X RESUMED 14:07:42.083; at 14:09:45.393 PAUSED (the cut, 123.3 s after), `A group closed: Home over probe.usage`, Home in front, X and Y `suspended=true`, ledger 121734. Home's X and Y tiles dimmed (screenshot); a tap opens the sheet "Test" / "Used up today" / Close |
| 3. Two apps in turn, one limit; screen off and the PIN lock count nothing | **Pass.** X 60.368 s (RESUMED to STOPPED) + Y 60.287 s = ledger 120655 exactly; the PWA "2 of 5 min". Screen off 60 s, then the keyguard (PIN pad) 40 s: +0 |
| 4. +15 from the Skjermtid sheet | **Pass.** Limit lowered to 2 (used up, closed); `/lifts` `what=group-2 minutes=15`: unsuspended 1.1 s later, `group_extra_minutes {'2': 15}`, X opens |
| 5. Revoke the access, grant again | **Pass.** `appops set ... GET_USAGE_STATS ignore` with X in front: X and Y suspended within 1 s, Home in front. The PWA: "App usage data is off", the row "Can't count" (and its (i)); the kid's sheet "Can't count now". `allow` again: open 1 s later, and the gap while it was off (X 14:15:50.720-14:16:00.403) was read and counted: +9683 exactly |
| 6. Pause all restrictions; the setup hold | **Pause: Pass. Setup hold: Fail (finding F2).** Group used up (61.6 s of 60), the pause: open in 2.4 s; X 30 s in the pause counted (+30.0 s); "Lås igjen": closed again 1.1 s later, the 2.3 s up to X's STOPPED counted. The hold (a second wiped phone, "Telefon 2", its group made before enrolment): X 65 s in the hold - `group_used_ms` stayed empty, `carry` followed X (state only). But the first fold after the hold ended counted the hold's last 17.2 s (below) |
| 7. A rule and bedtime that allow X, group used up | **Pass.** Bedtime 14:00-07:00 and a rule "Skole" 14:00-15:30, each allowing X and Z: the rule screen lists "Probe Z" and no row for X; X stays `suspended=true` |
| 8. A call | **Pass.** (a) X at the limit in a fake VoIP call (`VoIP NONE -> RINGING`, answered: `RINGING -> IN_CALL, pinned probe.usage`): at the limit Y was suspended at once, X stayed in front and unsuspended; hang-up: X suspended 1 s later, `A group closed: Home over probe.usage`. (b) Y closed at once (above). (c) `MODE_IN_COMMUNICATION` alone (voice-track silence, `mAudioModeOwner ... probe.usage` still at the cut): X closed at the limit as any app. Noted: without audio, Android dropped the fake app's mode within ~16 s and VoipCalls went `IN_CALL -> NONE` while keeping `pinned probe.usage` (the call notification lived), so the hold held in (a) |
| 9. Kiosk off: the cut brings Home; PiP counts | **Pass.** Several pinned apps (kiosk on), X's task over Z's: at the cut Z resumed (14:25:08.368) and Home came 362 ms later by the cut (`Home over probe.usage`). In a pause, X in PiP over Home: on-paused in `carry`, +41.9 s in 41.9 s; the pause's end with the PiP playing: PiP gone, Home, off at its STOPPED. X over Z in a pause, then the pause's end: Home (noted below) |
| 10. The title in the last 5 min | **Pass.** Group limit 4 min, X in front: the anchor's title "Test: 4 min left" (3 min 2 s left, rounded up), then "Test: 3 min left"; after X closed the title went at the next fold. Noted below: it follows the fold's `carry` |
| 11. Kill the launcher mid-use | **Pass.** `run-as ... kill -9` of Vibb One 15 s into X; the new process locked the phone (`PinLock: Process start: LOCKED`, screen off 14:31:25.661). The first fold read the gap from Android's events: X RESUMED 14:31:03.971 to STOPPED 14:31:25.521 = +21550 exactly |
| 12. The kill rule, Z's task below X (kiosk off) | **Pass.** `kill -9`: STOPPED X 14:34:07.311 (no PAUSED), RESUMED Z 2 ms later; X off at once, +12021 = its RESUMED-to-STOPPED exactly; Z 30 s and more added nothing; the PWA "2 of 4 min". `am crash` on 16 is the same as a kill (unlike 14's 10.6 s): STOPPED X, RESUMED Z 1 ms later, +10004 exactly |
| 13. A partner that must cold-start | **Fail - the 2 s fallback is needed (finding F1).** Z's process killed first (`am kill probe.below`, `pidof` empty), then X killed: STOPPED X to RESUMED Z = **338, 309, 326 ms** (`kill -9`) and **352, 322 ms** (`am crash`, a minute apart: a second crash within a minute brings "keeps stopping", which spoils the run). Warm, row 12: 1-2 ms. All five over 50 ms, so X stayed on |
| 14. Reboot with a Child PIN | **Pass.** `svc power reboot`; before: PAUSED and STOPPED Home, DEVICE_SHUTDOWN 14:40:54.204. After (unlock at 14:43:0x): **SCREEN_INTERACTIVE, RESUMED Vibb One (`BootCoverActivity`), KEYGUARD_SHOWN at 14:43:02.899, KEYGUARD_HIDDEN .924, DEVICE_STARTUP .947** - the boot's events before the unlock are stamped at the unlock - then RESUMED Home 14:43:18.139, RESUMED X 14:43:27.223. X's minute counted: +64496 = RESUMED to the fold point exactly |
| 15. A wake within the lock delay | **Blocked - not reachable on a Vibb phone (noted below).** Timeout 15 s, X in front; at the timeout SCREEN_NON_INTERACTIVE 14:47:15.208, KEYGUARD_SHOWN .729; a wake 1.5 s later asked for the PIN. X counted up to the screen off (30.787 s exactly), nothing after |
| 16. A hard power-off | **Pass.** X past two folds (161016, 221078), `emu kill` 44.5 s after the last fold, off ~45 s, boot, unlock, two folds: still **221078**. The read held no DEVICE_SHUTDOWN (the events after Android's last save were gone, X's RESUMED too): the reset at `from`, the downtime and the 44.5 s uncounted |
| 17. PiP from a second activity (kiosk off) | **Pass.** `am start -W` (clear task, X's Main opens Second) and Home: RESUMED Main 14:56:41.948, PAUSED Main .972, RESUMED Second .976, PAUSED Second 42.269 (PiP), **STOPPED Main 42.270 - after B's PAUSED**. `carry`: `probe.usage` on-paused, class `Second`; it counted while the PiP played and stopped at Second's STOPPED 14:57:32.101 (+50.153 s = Main's RESUMED to Second's STOPPED exactly). With A's STOPPED before B's PAUSED (a slower Home): the same, +32.944 exactly |
| 18. No late event | **Pass.** 0 `late event` lines, and no other `AppGroupUsage` warning, in the whole logcat of rows 2-17 (the launcher's lifetime; only the reboots' boot windows missing) |

### Findings

**F1 (row 13) - Reproduced, traced. Medium (against the kid), fix small.** On 16 a group app killed or crashed over an
app whose process must cold-start gets that app's RESUMED 309-352 ms after its STOPPED, past `KILL_PAIR_MS` = 50
(`launcher/app/src/main/java/com/kidslauncher/mdm/timerules/AppGroups.kt:19`; the pair check `:271-283`). The STOPPED is
dropped and the dead app stays **on** until its next event or the screen off: X died at 14:36:23.8 and was still counted
at 14:37:02 (+38 s past its death), it went on counting through the next runs (group 7, limit 4 min, closed at 318 s with
X dead most of it), and a new group (8) took +3.5 s for the dead X before the reboot. As §4.1 says for this case: the
after-side becomes "the first RESUMED of another package with no event of X between, within 2 s" (the same-ms side and
the rest unchanged; the pending STOPPED's window in `carry` grows to 2 s with it); then row 13 runs again on 14 and 16.

**F2 (row 6, the setup hold) - Reproduced, traced. Low (once per phone, against the kid), fix small.**
`timerules/AppGroupUsage.kt:95` decides `count = !plan.first && !RestrictionsPause.holdActive()` once per fold for its whole
window, so the first fold after the hold ends counts the hold's last stretch since its last state-only fold: X in front,
the last hold fold at 15:03:04.503, the hold ended 15:03:21.654 (`WindowRelock: Relocked (command, [pause])`), and the
next fold counted from 15:03:04.503: 60955 = 17.556 s of hold + 43.401 s after it (X's events). At most a checkpoint (60
s) + `SETTLE_MS`, at a fresh enrolment only. Fix: a state-only fold when the hold ends (before the flag clears), or count
from the hold's end (`spanFrom = max(from, holdEnd)`).

### Noted (no code change)

- **Row 15 can't happen here.** Vibb sets the device policy's maximum time to lock to the screen timeout
  (`lock/KeyguardCover.kt:39`, `:113`; `AndroidLock: maxTimeToLock=15000`), and SystemUI's lock delay is the smaller of
  "lock after timeout" (5 s) and that maximum less the timeout - 0. So the keyguard shows at every screen-off timeout; the
  QA M2 sequence (screen off and on, the app's RESUMED, no keyguard event) needs a phone without that policy. The fold's
  rule for it stays unit-tested only.
- **Row 10's title follows the fold's `carry`.** A group app opened from Home is in `carry` only at the next fold (the fold
  at Home's pause reads up to now - 2 s), so the title came up to a checkpoint (~60 s) after X opened, and went a fold
  after X closed. The timer is armed from the same fold, so the cut is unaffected.
- **Row 9, the pause's end with X over Z:** the group code's cut didn't fire (X wasn't in `carry` yet, as above), Z
  showed 1.5 s, and Home came from the relock's own `bringAfterUnlock` (`server/WindowRelock.kt:700`). Z is an allowed
  app; nothing is wrong, but the cut alone wouldn't cover this.
- Environment: the adb-installed test apps' own GET_USAGE_STATS app-op was back at `default` after a while (set again
  before each `Dump`); the first boot's emulator was killed once (memory, while gate A built) and adb went offline at
  its enrolment - a second wipe was clean.

### Summary

| Row | 16 |
|---|---|
| 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 14, 16, 17, 18 | Pass |
| 6 | Pause Pass; setup hold **Fail** (F2, Low) |
| 13 | **Fail**: the cold-start gap is 309-352 ms (5 runs), the 2 s fallback is needed (F1, Medium) |
| 15 | Blocked: not reachable with Vibb's maximum time to lock |

No product code changed. The emulator and the server were stopped at 15:05 UTC.
