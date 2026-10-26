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
| Design 38 §6p: the shutdown's last position | `adb shell svc power shutdown` with a fix 5 min old: the inbox shows **"Testtelefon was turned off 21:34 · battery 100 %, charging · Last position from 21:34: shown on Locate"** within 45 s. Locate's "Last position before the phone went off" line and the device page's "Off since" are absent while the off's push is held (the 5-minute restart merge, Q-5's rule) - re-checked 6 min later: Locate shows **"Last position before the phone went off"** on the newest point and the device page **"Off since 21:34 · battery was 100 %"** - PASS. |

Not run here: §6u 2-4 (the connecting line against `fake-sonos`), the photo wallpaper, 3-button navigation, the Jelly Star.

## Owed after launcher-glance-b (2026-10-25)

**Run 2026-10-26 (`docs/testing/2026-10-26-e1-run.md`):** the provisioning storm (9 loads, 0 ANRs) and the Full-text count
passed on `jelly36`; the contact-photo, Element X / WhatsApp, TalkBack, reboot-before-unlock and cold-start rows need
accounts and a device: the Jelly Star (J1).

The branch's emulator checks (`jelly36`, debug launcher; a WhatsApp or Element X account on the AVD, contacts in the
phone book with photos):

| Check | What to see |
|---|---|
| Element X DM row, a contact's learned room | The contact's photo (or initial) in the row's icon place, Element X's icon as the corner mark; a DM from a stranger keeps Element X's icon |
| Element X group, newest sender a phone-book contact | That contact's photo with the app mark; "Fotballgjengen · Pappa" in "Name only"; "Pappa: text" under "Fotballgjengen" in "Full text"; a stranger's newest message shows the app icon |
| WhatsApp DM / group, `tel:` sender in the phone book | The contact's photo; a number not in the phone book the app icon; `adb logcat` shows no number (`GlanceReader`, `GlancePanel`) |
| "Full text", three messages from Mamma | "Mamma · 3 nye" over the newest text (en "Mamma · 3 new"); the swipe clears all three |
| APP_ONLY (the app's lock-screen visibility secret) | App icon and "N nye varsler", no photo, no name |
| A missed Element Call from a contact | The red phone badge in the corner (not the app mark) |
| TalkBack on a group row | "…, Gruppe Fotballgjengen, siste fra Pappa, …" (nb) / "Group Fotballgjengen, latest from Pappa" (en) |
| Provisioning storm again (`-wipe-data`, `set-device-owner`, enrol) | A handful of "apps loaded" lines after `set-device-owner` (baseline 26 here; the 17 before it stay: no window opens before the launcher is admin); 0 ANRs; Home's list right after the first policy. The debug build's "apps loaded" line now ends "; callbacks at <elapsed>ms (<packages>), …": note the gaps, so the 5 s quiet is checked against measured gaps (code QA Q-1) |
| A group with a 40+ character name in "Name only"; a long DM name in "Full text" with 3 messages | The name is cut, "· Pappa" / "· 3 nye" stays whole after it (Q-7); a group in "Full text" shows "· 5 nye" too (Q-6) |
| A contact added to the phone book while their WhatsApp row is on the lock | The photo shows after the next message (Q-8, as documented) |
| WhatsApp's real `sender_person` URI shape, on the test account only | The photo for a phone-book number; the app icon for a `content://` or missing URI. Don't paste `dumpsys notification --noredact` output with real numbers into the docs |
| After a reboot, before the first unlock | Rows show the app icon, no photo, no crash (the DE rules' contacts have `id = 0`, CE unreadable) |
| First lock render after a cold start | The photo shows first, then the app mark once the icon job has drawn it |

## Owed after launcher-glance-c (2026-10-26)

**Run 2026-10-26 (`docs/testing/2026-10-26-e1-run.md`):** every `jelly36` row passed in both lock modes (gesture, 3-button,
alarm, font 1.3, the full tile, the long message); the ripple and the fold's look are visual (secure window: Jelly Star);
`six35` rows wait for E2.

The branch's emulator checks (debug launcher, music playing in the music app, three conversations on the lock's glance;
screenshots through the emulator console, the lock is `FLAG_SECURE`):

| Check | What to see |
|---|---|
| `jelly36` (320x569 dp), font 1.0, music + 3 rows (RC3-8), gesture navigation, no alarm, own mode and android mode (the profile clock in both) | The compact tile and all three rows in sight, no fade at the bottom; log `Glance layout: tile COMPACT, 3 rows, 0 more` |
| The same with **3-button navigation**, and again **with an alarm set** (both bars) | Every row at least partly in sight: 3-button, row 3's bottom under the 32 dp fade (model 42.6 dp of it in sight with a 48 dp status bar, ~5 dp under on `jelly36`'s own bars); with the alarm, row 3 more under the fade (model 22.4 / 46.4 dp in sight) - never a row wholly hidden |
| `jelly36`, font 1.3, music + 3 rows | The compact tile; the rows that don't fit sit under the bottom fade (a row's top or the card above it fading), the page scrolls to them |
| `jelly36`, font 1.0 and 1.3, music + 1 row | The full tile 12 dp lower than before (162.8 dp in the model): the controls closer to the tile's bottom edge, nothing cut, the play glyph not touching the edge |
| `jelly36`, any font, a long message | The row's text is still one line, cut with "…" (below 720 dp tall) |
| `six35` (411x914 dp), font 1.3, music + 3 rows with short messages (F4) | Each row's text on up to two lines (a short message whole), the title one line; the three rows fit or the rest is under the fade |
| `six35`, font 1.3, Home with 5+ contacts with two-line names (F3) | "Bestemor" and "Kristoffer" (and every neighbour pair) with a visible gap between them; at font 1.0 Home looks as before |
| `jelly36`, font 1.3, **3-button**, a time rule locking: the **rule card + music + 3 rows**; and font 1.0, 3-button, no music, **5 rows** (the fold in the gap above row 5) | Rows 2 and 3 (the first case) / row 5 (the second) wholly below the fold; screenshot the bottom edge: the card above fades into its text under the 32 dp fade - judge whether it reads as "more below" (code QA M-2); a scroll brings them up |
| `jelly36`, the full tile: **press and hold play/pause, previous, next** | The press ripple a round 28 dp disc, never cut flat at the tile's bottom edge (code QA L-2); the play glyph 15 dp from the edge |
| `six35`, font 1.3: a **two-line row next to a one-line row**, a **long nb message**, and **3-button navigation** with music + 3 two-line rows | The icons aligned at the top beside the titles in both rows (code QA N-2); the long message cut with "…" at the end of line 2; the three two-line rows fit (533.2 dp in a ~770 dp scroller) |
| `jelly36`, font 1.0 / 1.3 / 2.0: **Home's contacts row and the phone book** (the 4 dp sides apply to both) | 1.0 as before; at 1.3 "Kristoffer" a step smaller (its 88 dp line); at 2.0 "Mamma"/"Mormor" whole, smaller - never cut between letters; neighbours never touch |
