# Evening run 2026-10-25: launcher small A, music r13, design 38's last position

`jelly36` wiped, main 04b56192's debug launcher and debug music app, the test server on `http://10.0.2.2:3160` (main's
0.52.0 build; phone 1 "Testtelefon", android lock mode, Child PIN 2468, Everyone's daily limit 2 h, no rules; the
gradient wallpaper). Recordings with `adb shell screenrecord` (frames only while something changes), analysed as
luma/diff per frame (`scratchpad/r11/rec.sh`); the lock's windows are secure, so every lock observation is a UI dump.

| Check | Result |
|---|---|
| Provisioning storm with the trail window (`launcher-small-a` item 4) | **0 ANRs**, but **43** "apps loaded" lines in two minutes (31 in the apply-hold run). The log explains why the window missed: 17 loads at 20:08:27-46 come BEFORE the device owner is set (`set-device-owner` 20:08:46 after one refused attempt - the system reports package changes for every app around the DO set), and the loads 20:09:07-20:10:36 follow callbacks about packages the enforcer passes (20:09:02-08) did NOT change. The trail window only folds callbacks about packages a pass touched, as designed. Each load is 4-60 ms with the icons cached. Follow-up in PLAN's queue: a provisioning window in which every package callback counts as trail. |
| Home ↔ phone book (item 1, Q-1) | **PASS**: one frame each way (frames 33 and 39 of `homepb`), no intermediate frame, no black, no step in the ground; both screens draw the same copy. (Gradient wallpaper here; the photo case is the Jelly Star check.) |
| Clock → Home button (item 1) | **PASS**: the close animation over 12 frames, then nothing for the rest of the 14 s - no late step 0.5 s after (the hand-over is gone). |
| Music splash (`music-r13`) | The rings on the night ground and "vibb music" at the foot show from the first frame of the app's window; **no plain dark frame** between the splash and the content. But with an **empty library** the splash holds the full 3 s cap (splash drawn at 7.06 s, the empty state fades in at 10.06 s), and the first-ever start held ≥3.75 s: the content-ready signal never fires on an empty library (§6u step 1's "empty library" case). Fix requested from the implementer (`music-r13-fix`: the empty state satisfies the hold at once). |
| Music app allowed on a phone | Lesson: a sideloaded debug music app is a relock block ("installed from elsewhere") - it reaches Home only after the parent's "Allow these apps" on the phone's page (`/devices/{id}/allow-blocked`, field `blocks=<pkg>:<signer>`), not through the Apps toggle alone. |
| Design 38 §6p: low battery, one fix per episode (`last-position`) | **PASS**: `adb emu power ac off` + `capacity 10` → `LowBatteryFix: Battery low: a fix under 2 min old, reported` (the Update Location fix 1 min earlier was reused - Q-7); `capacity 9`, ac on, ac off at the same low level → `Battery low: no fix (taken true, …)` - the episode guard. |
| Design 38 §6p: the shutdown's last position | `adb shell svc power shutdown` with a fix 5 min old: the inbox shows **"Testtelefon was turned off 21:34 · battery 100 %, charging · Last position from 21:34: shown on Locate"** within 45 s. Locate's "Last position before the phone went off" line and the device page's "Off since" are absent while the off's push is held (the 5-minute restart merge, Q-5's rule) - re-checked after the hold: see below. |

Not run here: §6u 2-4 (the connecting line against `fake-sonos`), the photo wallpaper, 3-button navigation, the Jelly Star.
