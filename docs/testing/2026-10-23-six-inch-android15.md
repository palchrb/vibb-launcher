# A 6-inch phone on Android 15 - look-and-fit and lock-flow check, 2026-10-23

Main at `636f09c7`, branch `six-inch-check`. Two questions: (1) do the apps look and fit right on a normal 6-inch
phone (about 411x914 dp), and (2) does design 59's lock flow (Android's keyguard as the gate, the glance as its
showWhenLocked face, the boot cover at BFU) behave on Android 15. Earlier runs used the Jelly Star's 320x569 dp and
handy36's 240x427 dp, both Android 16. Screenshots are in `screens/six-inch-android15/` (540 px wide).

## Setup

- AVD `six35`: `pixel_7` profile, `system-images;android-35;google_apis;x86_64` (r9, no Play Store), emulator
  37.2.12, headless (`-no-window -gpu swiftshader_indirect -memory 2048`). `wm size` 1080x2400, `wm density` 420 =
  **411x914 dp**. Gesture navigation. Android's language set to **nb-NO** (root `setprop persist.sys.locale` before
  provisioning), so the strings are the Norwegian ones the kid sees; time zone UTC.
- Debug launcher (0.23.6-debug) and debug music app of this commit; local server 0.40.1 (fresh data dir, debug admin
  component). PWA over HTTP with the scratch `pwa_setup.py` recipe (its hardening regex bug left every restriction
  off; that doesn't matter for this check): Parent PIN, Child PIN `1357`, calls managed with four contacts (Mamma,
  Pappa, Bestemor Ingrid, Kristoffer Andreassen-Haugland). Provisioned per `emulator.md` §4 (`dpm set-device-owner`,
  third try: the fresh image briefly had an account), enrolled with the code in Vibb One's Settings.
- Wallpaper: the default (Vibb night); the image has no built-in photo wallpaper.
- Glance rows: Messages on the lock with full text; three SMS from the console (`sms send`) gave three rows. The
  rows show the sender's number (Messages has no contacts on this image).
- Music: `scripts/fake-audio.py --mb 2 --feed 3`, an RSS entry ticked for the phone, the music app let out with
  "Allow these apps".
- Long Home list: 11 more apps allowed (19 tiles).
- Lock: the Lock row to Android's lock (`PIN command: pin set (rev 1)`, `mode=ANDROID`). Our own keypad was
  captured before the switch (own mode).
- Screenshots through the emulator console (`screenrecord screenshot`, it captures FLAG_SECURE); recordings with
  `emu screenrecord` at 24 fps. The console repeats frames, so frame counts are approximate (±1 frame, 42 ms).
- **The emulator was OOM-killed twice** (09:50 and 10:13): another agent started `handy36` on the same VM, and two
  emulators (about 4 GB each) don't fit in 12 GB. The first kill was a crash boot, which led to finding F1. After the
  second kill I didn't restart: a third emulator start could have taken down the other agent's emulator too.

## Look and fit

| Screen | Font 1.0 | Font 1.3 | Screenshot |
|---|---|---|---|
| Home: 4 contacts, 9 tiles (flashlight, settings), 3 per row | **fits**: plenty of room, the lower ~40 % is empty | not captured (the long list covers 1.3) | `home-1.0` |
| Home: long list, 19 tiles, scrolls with a fade at the bottom | **fits** | **cramped**: the contact labels "Bestemor" and "Kristoffer" touch, so they read as one word (F3) | `home-long-1.0`, `home-long-1.3` |
| Glance, 0 rows | **fits** | not captured (emulator killed) | `glance-0rows-1.0` |
| Glance, 3 rows | **fits**: each row's text is one line, cut off with "…", and ~45 % of the screen is empty (F4) | **fits** | `glance-3rows-1.0`, `-1.3` |
| Glance, 3 rows + music tile playing | **fits** | **fits** | `glance-3rows-music-1.0`, `-1.3` |
| Glance with a running timer | not done (no Fossify Clock with the pinned signer on the image) | - | - |
| Android's PIN pad from "Lås opp" | **fits** (Android's own, light) | **fits** | `pad-android-1.0`, `-1.3` |
| Our keypad (own mode) | **fits** | **fits** (the digits keep their size; the labels grow) | `keypad-own-1.0`, `-1.3` |
| Kid Settings | **fits** | **fits** | `kidsettings-1.0`, `-1.3` |
| Phone book | **fits** | **fits** | `phonebook-1.3` |
| Time-rule screen (Leggetid, two rows) | **fits** | **fits** | `timerule-1.0`, `-1.3` |
| Blocked / Play-link screen | not reachable: no Play Store on this image, and `market://` resolves to nothing | - | - |
| Music library (one entry) | **fits**, but small for the screen: the cover is capped at 180 dp, with ~350 dp empty around it (F5) | **fits** | `music-library-1.0` |
| Music player, playing | **fits** | **fits** | `music-playing-1.3` |
| Music, the compact player on the library | **fits** | **fits** | `music-compact-1.3` |
| Boot cover (the mark) | **fits** | - | `bfu-graceful-boot-cover` |

No screen was broken or oversized at 411x914 dp. Nothing scales up for the bigger screen either: the layouts are the
small-screen ones with more empty space.

## Android 15 behaviour

### At a wake: frames before the glance

`wakerec.sh`: screen off for 4 s, recording, power key, 3.8 s. Four wakes (`wake-frames.png` shows wake 2):

| Wake | Lit frames before the glance | What they show |
|---|---|---|
| 1 (the host was short of memory; the emulator was OOM-killed a minute later) | 21 (~0.9 s) | black with the status bar only |
| 2 | 21 (~0.9 s) | 1 frame with the status bar and the gesture handle from before the sleep (Home's), then black with the status bar |
| 3 | 6 (~0.25 s) | black with the status bar |
| 4 | 4 (~0.17 s) | black with the status bar |

None of the frames showed SystemUI's clock, the lock wallpaper or any Home content. On Android 16 (rc2 run, check
10) the first lit frame was the night ground with the mark; on 15 it is plain black for 4-21 frames. The glance's
first frame was always the full page.

### The pad's success

"Lås opp" -> Android's pad -> PIN + Enter (`pad-success-frames.png`): the pad with its dots, then **~0.33 s (8 frames)
of an empty light-grey screen** (the light pad fading out; nothing behind it shows), then the glance page as it
was (dimmed for ~0.3 s, then normal) for ~1.3 s, then Home slides in (one split frame) and stays. There was no
"Skriv inn koden din" and no keypad chrome. The log matches 16:
`Android's PIN pad: succeeded` -> `LOCKED -> UNLOCKED` -> `Home brought to the front: the lock left` (30 ms) ->
`USER_PRESENT`. **Clean**, apart from the light-grey frames, which the rc2 run on 16 didn't describe.

### Recents and the bottom-edge swipe over the glance (kiosk on, android mode)

- **Recents key** (`KEYCODE_APP_SWITCH`): nothing on screen; the glance stays on top (`ShellRecents:
  RecentsTransitionHandler.startAnimation: no controller found`).
- **Bottom-edge swipe**, fast (150 ms) and slow with a hold (900 ms): nothing on screen; the glance stays and nothing
  is logged. Quickstep's state at that moment:
  `mSysuiStateFlags=overview_disabled|home_disabled|keygrd_occluded|allow_gesture|awake`, lock task `LOCKED` (tasks:
  our Home, the pinlock task). So on 15 the Home gesture is refused as the flags say. The keyguard is never shown,
  unlike the user's 16 recording in 59d §8.9. **Caveat:** `input swipe` is an injected touch; a real finger on 15
  is untested.

### The boot cover and BFU

| Boot | Shutdown arm | Result |
|---|---|---|
| Crash (the emulator OOM-killed at 09:50), Settings hidden | no | **stuck (F1)**: no HOME, the boot never completes; 7 min until rescued |
| `svc power reboot` | yes: `Boot cover enabled (shutdown)`, `lock-screen line: set` | **PASS**: the cover is HOME, occludes the keyguard (the mark), and asks for the pad by itself at the wake (`asks for Android's PIN pad (resumed, automatic 1 of 3)`). After the PIN: the mark until Home (`Unlocked setup took 15703 ms`, `Boot Home started 20187 ms after the unlock`, on a host loaded by two emulators); never the stock launcher or a chooser |
| `adb reboot` | no | **stuck (F1)** again, 3.5 min, until an incoming call |

**The stuck boot (crash and `adb reboot`).** `bootstate.sh` (identical both times):

```
sys.boot_completed=            (empty)
User #0: state=BOOTING
mSystemReady=true mSystemBooted=false
DispatchEnabled: false
E WindowManager: No home screen found for Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] ... } and user 0
pm query-activities -c HOME: No activities found
com.android.settings: hidden=true
```

SystemUI's BFU keyguard is on screen (the night mark wallpaper, its big clock, "Lås opp for å få alle funksjoner og
data", `bfu-unarmed-stuck-keyguard.png`), but **input is off**: taps, swipes, the power key (stays `Awake`) and
`keyevent 82` do nothing. `wm dismiss-keyguard` (shell) brings up the pad, but it takes no digits. Our process never
starts: `LOCKED_BOOT_COMPLETED` comes only after the boot completes, and that never happens, so the F3 locked-boot
start (`BootCover.atLockedBoot`) never runs. A kid would see a lock screen that does nothing, and Emergency can't
be reached either.

Ways out that were seen:
- **An incoming call** (`gsm call`, Mamma): our direct-boot-aware `InCallActivity` starts
  (`bfu-unarmed-stuck-call.png`, showing the bare number, not "Mamma"). That activity lets the boot finish
  (`sys.boot_completed=1`, `RUNNING_LOCKED`, input on), and then the F3 path runs:
  `Locked boot: HOME resolves to null/null`, `Boot cover waits: a call is live`, `Boot cover enabled (locked boot
  (android mode))`, the cover, the pad, the PIN, Home (`Boot Home started 7486 ms after the unlock`).
- (Emulator only) root `pm unhide com.android.settings`: `FallbackHome` resolves, the boot completes within
  seconds, and the same F3 path runs (`Locked boot: HOME resolves to com.android.settings/...FallbackHome`, the cover,
  the pad asked automatically, the PIN, Home 18-28 s later). Enabling the cover component as root was refused
  ("Cannot disable a protected package").

Why: before the first unlock only direct-boot-aware HOME activities resolve. On this image the stock launcher's
(NexusLauncher's) isn't one. The usual fallback is Settings' `FallbackHome`, which is direct-boot aware, but the device
owner hides `com.android.settings`. With the shutdown arm, our `BootCoverActivity` (direct-boot aware, HOME) is the
answer. Without the arm (a crash, `adb reboot`, a hard reset, a flat battery) there is none. On handy36 (16,
Play image) the rc2 run had `Locked boot: HOME resolves to` the stock launcher on unarmed boots, so it never hit
this. Design 59 F3's comment in `BootCover.kt` ("a boot without the shutdown's arm ... has it behind Android's
keyguard too") assumes `LOCKED_BOOT_COMPLETED` arrives, and it doesn't here.

### Exceptions and crashes

`logcat -s AndroidRuntime` (and our tags) over the whole run: **no crash in our packages**. One system crash:
`com.google.android.settings.intelligence` (NPE in `IndexHandlerThread`). Other notes from the log:
- `DevicePolicyEngine: Persistent preferred activity in package me.vibb.launcher.debug not found for user 0, removing
  policy for admin` - 4-5 times at every locked boot and at the cover's hand-over (`Boot cover disabled (main process
  after unlock)`). Home still came up after each unlock (it is brought up by us, and "Alltid" was picked at setup),
  so the effect wasn't visible here (F6).
- `AppEnforcer: Platform refused to hide com.android.settings` after my root unhide. That is an artifact of the
  rescue: provisioning had hidden it without complaint.
- R4 (two time-rule screens) did not recur on 15: `the time-rule screen: sent now - the queued one dropped`, one
  `LockActivity`.
- A hardware Enter on the lit glance (my script typed the PIN while the pad wasn't up) activates the focused
  "Nødsamtale" and shows the "Ringe 112?" confirm. It was dismissed and no call was placed. This happens only with a
  keyboard.

## Findings

| | Size | What | Where |
|---|---|---|---|
| F1 | **M, High** | On a phone whose stock launcher has no direct-boot-aware HOME (this Android 15 image; AOSP Launcher3-based phones generally), an **unarmed reboot (crash, `adb reboot`, hard reset, flat battery) never finishes booting**: the device owner hides Settings, so `FallbackHome` is gone and no HOME resolves. Input stays off: the PIN can't be typed, the power key and Emergency do nothing. The F3 locked-boot cover never runs. It recovers only when something starts an activity (an incoming call). A forced reboot is unarmed too, so it would hang again. Fix: a direct-boot-aware HOME has to exist without the shutdown arm. Options: keep `BootCoverActivity` enabled while managed (it hands over by itself; check the PPA order so the unlocked HOME is still `HomeActivity`), or arm it at every lock-up / CE start rather than at `ACTION_SHUTDOWN` only, or leave `com.android.settings` unhidden when `FallbackHome` is the only direct-boot-aware HOME. Check the Jelly Star's stock launcher for `directBootAware` (`pm query-activities -c HOME` in BFU) | `lock/BootCover.kt` (`init`/`takeOver` disable it, `coverAtShutdown` arms it), the hiding of Settings in `AppEnforcer` |
| F2 | S | After the pad's success on 15 there are ~8 frames of an empty light-grey screen (the light pad fading) before the glance, which then shows ~1.3 s before Home. Not ours to draw, but it is a flash on a dark face. If 59b §5's pad look ever gets a dark pad, this goes too. Note | SystemUI; design 59b §5 |
| F3 | S | Home at font 1.3: two-line contact labels of neighbouring contacts touch ("Bestemor" / "Kristoffer"), so they read as one word. The label is `wrap_content` with no horizontal padding or margin | `res/layout/item_kid_contact.xml` (`contact_name`), the row in `HomeActivity.kt:864` |
| F4 | S | The glance's rows are one line each (`maxLines="1"` for the title and text), even with 822 dp available (`Glance fit: ... available 822 dp`) and ~45 % of the screen empty. On a tall screen, two lines of text would show a short message whole | `res/layout/view_lock_glance_row.xml:77,87`, the fit in `GlancePanel` |
| F5 | S | The music library's carousel cover is capped at 180 dp (`CAROUSEL_COVER_DP`), small on a 914 dp-tall screen with ~350 dp empty around it. Optional: a larger cap when the height allows | `music/.../ui/Fit.kt:90,172` |
| F6 | S (check) | Android 15's DevicePolicyEngine logs `Persistent preferred activity ... not found ... removing policy for admin` at every locked boot and at the cover's hand-over. Check on a phone that the launcher's HOME PPA is still there after the first unlock (`dumpsys package preferred-activities`, or press Home from an app after a reboot with no "Alltid" default) | `BootCover.applyPolicy`, the PPA setup |
| F7 | Note | At a wake on 15, 4-21 frames of black (status bar only) come before the glance. There is no mark ground as on 16, and no SystemUI clock or Home content leaks. The longer counts were under memory pressure | - |

Not done: the glance with a running timer (no Fossify Clock), the blocked/Play-link screen (no Play Store on this
image), the glance with 0 rows at 1.3, the default (short) Home at 1.3, the parent pause screen, and the QR flow (adb
provisioning instead). All were cut short by the second OOM kill.
