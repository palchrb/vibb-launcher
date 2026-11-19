# Emulator check: design 114, the setup hold and the guided flow (branch `design-114-build`)

`docs/testing/emulator.md` §6bb, design 114 §11 (rows 1-16) and the owed clock-correction check (code QA L9). Run the
night of 2026-11-18/19 (00:50-02:57 UTC; `compat34` 00:50-01:49, `jelly36` 01:50-02:57, one emulator at a time), the user's OK for emulator use that night.

**Build.** `design-114-build` at e3fc7cd37: the debug server (`cargo build -j 1`) and the debug launcher
(`assembleDebug -PwarningsAsErrors=true`). The gate found four bugs; the fixes are on the branch (below). Android 14's
rows ran on e3fc7cd37, row 13's re-run on a9867e049; Android 16's first pass (rows 1, 3-10, 14) on a9867e049 and
everything after the buttons/mobile-data fixes (the screenshots, rows 4, 9b, 9c, 11, 12, 13, the clock) on 1c584180e.

**Rig.** One emulator at a time under `flock -o ~/emulator.lock`, port 5556. The server with
`BIND_ADDR=0.0.0.0:3120`, `DEVICE_BIND_ADDR=0.0.0.0:3121`, a fresh `data/`, `LAUNCHER_SIGNATURE_CHECKSUM` a dummy
(43 x `A`, so Connect draws its QR form). Phone "Testtelefon" (device 1), Parent PIN 482915 (set on Connect, row 2),
calls and SMS managed, bit 64 on (the default mask), Locate off, Block USB debugging off. The debug launcher made
device owner with `dpm set-device-owner` and **nothing else granted** (no listener, no battery allowlist, no
WRITE_SETTINGS, no roles: the steps stay missing), then enrolled with a typed setup code in its Settings - the rig's
way; the 6-tap QR needs a real phone. A named emulator snapshot `pre114` (device owner set, not enrolled) gave each
later fresh enrolment (restart, the PWA's Lås igjen, the ceiling, the clock, no first policy) in seconds; the fixed
APK was installed over it with `adb install -r` (device owner kept). Versions:
- **Android 14:** `compat34` (API 34, `-wipe-data`), 480x854.
- **Android 16:** `jelly36` (API 36, 480x854 @ 240 dpi, the 36 px bar), equal to `~/kid-look-1-avd-pre-start` at the
  start (22 of 22 sums); restored from that copy afterwards (22 of 22 sums, the same file list, `jelly36.ini` equal; the `pre114` snapshot gone with it). Battery 100 % on AC.
- Android 17: not run (no emulator image).

The first sync was held back for row 9c with a small TCP proxy on 3122 in front of 3121 that answers 503 to every
request but `POST /api/devices/enroll` while a marker file exists (the phone enrolled with `http://10.0.2.2:3122`).
A second browser for row 9a: a code-less APK `me.vibb.probe.browser` with one `VIEW http/https BROWSABLE` filter
(emulator only, debug-signed). The clock: `adb shell cmd alarm set-time` (automatic time puts it back within seconds).

## Results

| # | Row | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|---|
| 2 | Connect without a Parent PIN (PWA only, before row 1) | **Pass** (the server, not version-bound): `#qr` holds only the orange "Velg en foreldre-PIN før oppsettet" / "Set a Parent PIN before setup" (`data-warning="no-parent-pin"`) linking `/devices/1/lock?back=provision#parent-pin`; no `#setup-code`, no form in `#qr`. `POST regenerate-code` makes no code (303 to `provision#qr`), `POST provision` answers 200 with the line. An easy PIN (123456) is saved and stays on `lock?back=provision&notice=parent_pin_saved_easy#parent-pin` with its warning and `back` kept; a good PIN lands on `/devices/1/provision#qr`, where `#setup-code` now shows. Clearing the PIN voids a live code (hash, kind and expiry NULL). The QR form itself waits behind the launcher check here (the offline rig's GitHub check of the release asset fails: "No QR code until the launcher check above passes") - the rig's, not the branch's. The flow never shows a Foreldre-PIN screen (rows 1, 9). | same |
| 1 | Fresh enrolment | **Pass.** `RestrictionsPause: The setup hold started (4 h at most)` at the enrolment; `mLockTaskModeState=NONE`; Home leads to the flow ("1 of 8"); the report's `setup` carries `hold_until_ms` and `hold_left_ms` (14 389 426 ms at the first report), stored as `setup_hold_until_ms`. PWA: the red "Setting up, no restrictions" row to `/devices/1/setup#open`, no `Setup` row; the sub-page row "I oppsett"; Device › Oppsett's open group "Setting up until 05:54" with Lås igjen | **Pass**, the same (`hold_left_ms` 14 383 554; "I oppsett, uten begrensninger", "I oppsett til 06:59" in nb) |
| 9 | Back and Home in the hold | **Pass.** Back does nothing; "Åpne" → Android's page → Home: the same screen; an earlier "Senere" stays skipped (notification access skipped, Background connection's page opened, Home: Background connection again, not notification access) | **Pass** (predictive back: the flow stays; Senere kept across "Åpne" and Home) |
| - | The progress line | "1 of 8" on a fresh phone = the real total of the run's screens (5 required + 3 optional parent steps); it grows when a step joins (the Wi-Fi step after the assistant: "5 of 9"). Seen: a step taken again from the end list reads "k of k" (the run's shown count, not its place) - cosmetic, not changed | the same |
| 3 | Notification access | **Pass.** The listener's detail page ("Allow notification access", Vibb One (debug) · App badges); the switch **not greyed** (`enabled=true`): no restricted-setting line needed; Allow; back: the next screen; the PWA cleared within ~7 s | **Pass**, not greyed either (adb-installed; a provisioned Vibb is installed by the system's provisioning, not a sideload session); the PWA cleared within ~8 s |
| 4 | Mobile data (`svc data disable`) | **Pass.** `svc data disable` reads as missing (the user's reason). The step opens the SIM page (T-Mobile: Use SIM, Mobile data, Roaming); Mobile data on; next | **Fail, fixed.** The SIM page has **no Mobile data switch** on 16 (Use this SIM, Mobile network, Phone number, Roaming, App data usage, Preferred network type, ...). The Internet panel has it. Fixed: from API 36 the panel first (`mobileDataActions`); the panel is a SystemUI dialog over the flow on 16 (no pause, no result after the switch), so the flow now reads the step again when it gets its focus back. Re-run on 1c584180e: "Åpne" → the Internet panel, T-Mobile on, Back: the next step at once |
| 5 | Data Saver (`cmd netpolicy set restrict-background true`) | **Pass.** Vibb One's app data page; "Unrestricted data usage" on; next | **Pass** ("Unrestricted mobile data usage") |
| 6 | Full-screen intent (`appops ... USE_FULL_SCREEN_INTENT deny`) | **Pass.** Android's page "Allow full screen notifications from this app"; allow (uid mode allow); next. After "Ferdig", screen off, `adb emu gsm call` from the contact: Vibb's InCallActivity over the lock ("Calling you…", Testkontakt) | **Pass**, the same (InCallActivity on top, screen woken) |
| 7 | Design 98's steps | **Pass.** Digital assistant: Android's assistant page, Vibb One, OK, back: next. Wi-Fi in airplane mode: airplane on, the Wi-Fi panel; Wi-Fi on; Done: "Wi-Fi stays on in airplane mode", airplane off again, next. Call filter: the caller-ID & spam request, then the call redirection request, Vibb One both times: both roles held, next | **Pass**, the same |
| 9a | Default apps (calls managed; the browser moved in the assistant window first) | **Pass.** With the assistant role dropped (adb), the assistant step's "Åpne" opened the pick window; Default apps (adb `am start` inside the window): Browser app → Probe Browser, assistant → Vibb One; back: "moved and not taken back: [browser]", the Default apps step appears ("Choose them back."), the PWA lists `default_apps`. Its "Åpne": Android's Default apps page, **usable** (`enabled=true`); Chrome back; back: the step gone, the PWA's list without it. (A dialer moved in the window is taken back by the apply at once: "moved and not taken back: []" - right, the dialer role is Vibb's while calls are managed) | **Pass**, the same |
| 8 | Skips and ticks | **Pass.** "Senere" on Background connection and Modify system settings (re-broken by adb): after "Ferdig" the PWA shows "Setup: 2 steps left" and lists both. "Gjort" on Vibrasjon: the PWA's "Avkrysset (1)" within ~6 s, security event `setup_tick_phone` ("telefon 1: Vibrasjon"). "Hopp over" on Touch feedback: still under "Valgfritt". Fingerprint isn't a step here (no Android lock: `when: android_lock`) | **Pass**, the same (security event 02:04:26) |
| 9c | No first policy (all requests but the enrolment answered 503) | **Pass.** The `always` steps ran at once (notification access, background, the parent steps); with them done (adb grants) and the parent steps skipped: "One moment …", no buttons; after 30 s "Try again" and "Finish". Proxy opened, "Try again": the policy landed, Modify system settings and the assistant joined | **Pass**, the same in en and nb ("Et øyeblikk …", "Prøv igjen", "Ferdig"; the 30 s restart after the language change recreated the screen) |
| 9b | Notifications at the hold's start (calls unmanaged) | **Pass.** No permission dialog over the flow (POST_NOTIFICATIONS granted `POLICY_FIXED`); both "Finish setting up Vibb One · 2 steps left" and the window's "All restrictions paused until …" show; the window notification's body tap opens the flow; its N equals the PWA's (2) | **Pass** (no dialog; both notifications) |
| 10 | "Ferdig" | **Pass.** `Relocked (lock_again, [pause])`, `mLockTaskModeState=LOCKED`, Home is the child's; the hold row gone; "Setup: 2 steps left" = the phone's 2 | **Pass** ("Setup: 1 step left" = the phone's 1) |
| 11 | Restart in the middle | **Pass.** `adb reboot` in the hold: up LOCKED, Home the child's, `Relocked (boot, [pause])`; the PWA's hold row gone at the next report ("Setup: 4 steps left"); "Åpne for oppsett": the notification "4 steps left" = the PWA's 4; its tap opens the flow ("1 of 7") without a PIN | **Pass.** Up LOCKED with Home, `Relocked (boot, [pause])` at 02:42:57; the PWA's hold row gone at the next report ("Setup: 4 steps left"); "Åpne for oppsett": "Finish setting up Vibb One · 4 steps left" = the PWA's 4. The tap: Android 16 put Vibb's notifications in a collapsed group in the silent section, and a tap on the collapsed group opened Home (the system's group summary), not the flow - expanded, the row's own tap opens the flow (seen on 14). Android's grouping, not this branch's; noted for the Jelly Star |
| 12 | The PWA ends the hold | **Pass.** "Lås igjen" (`end-windows`, `back=setup`): lands on `/devices/1/setup?...#open`; `Relocked (command, [pause])`, LOCKED, the flow gone, Home the child's | **Pass**, the same |
| 13 | Parent Settings' row | **Fail, fixed.** PIN → Settings: the first row "Setup · 2 steps left"; none of the moved rows on the main screen (Setup, Hidden apps, Rotate screen, Licenses, Version, Server URL, Server certificate, Scan setup QR, Enroll with code, Sync now, Install from Play, Pause all restrictions). The row opens the flow at "Pause for 2 h"; **after the PIN the flow was gone and Home showed**: the pause's leave of lock task clears every locked task, the flow's too, and Android starts Home. Fixed (a9867e049): Home's resume brings the flow back once. Re-run: after the PIN, the flow at "Notification access, 1 of 8". The steps; "Ferdig" ended the pause (LOCKED); the row then "Complete" | **Fail, fixed.** On 16 the same pause left **parent Settings** in front (Home not restarted), so Home's hook didn't fire. Fixed (1c584180e): the destroyed flow brings itself back once (1.5 s), unless Back or Ferdig closed it. Re-run: after the PIN, the flow at "Modify system settings, 1 of 5"; Back closed it for good. Row "Oppsett · 1 steg igjen" / "Setup · 2 steps left" |
| 14 | Location with Locate off | **Pass.** `location_mode=off`: FINE and COARSE `granted=true` `POLICY_FIXED`, BACKGROUND `granted=false`; no Vibb entry in `dumpsys location`; `cmd wifi list-scan-results` lists AndroidWifi. `fake-portal.py`'s window not run (design 102's own gate covers it) | **Pass**, the same grants |
| 15 | Not device owner | **Pass** (`compat34` only). A wiped phone, the launcher installed without `set-device-owner`, enrolled as device 3: no "setup hold started", no flow (Home is Android's chooser); the report `"device_owner":false`; the PWA's red "Vibb One doesn't manage the phone" (`not-device-owner`, to `#step-device_owner`), no `Setup` row; Oppsett lists device_owner first and the steps | not run (by design) |
| - | The 4 h ceiling | **Pass (the wall-clock half).** Faked with the clock: `cmd alarm set-time` +4 h 5 min a minute after the enrolment: `Relocked (timer, [pause])`, LOCKED, Home the child's (automatic time put the clock back within ~5 s). The elapsed-clock half is `RestrictionsPauseTest`'s (no faster way on an emulator) | **Pass**, the same: +4 h 5 min 15 s into the hold, `Relocked (timer, [pause])` 02:55:02, LOCKED, Home the child's |
| - | The owed clock correction (QA L9) | **Pass.** A wall clock set back 10 minutes a minute into the hold ends it (fails closed): `Relocked (timer, [pause])`, LOCKED, the PWA "Setup: 4 steps left" with "Open for setup for 2 h". "Åpne for oppsett" recovered it: the pause on, "Finish setting up Vibb One · 4 steps left", its tap opens the flow | **Pass.** Set back 10 min half a minute into the hold: `Relocked (timer, [pause])` 02:49:46, LOCKED, Home the child's; the PWA "Setup: 4 steps left" with "Open for setup for 2 h"; "Åpne for oppsett": the pause on, the notification "4 steps left", and Settings' Setup row opened the flow at "Notification access, 1 of 7" |
| - | Settings' row, not device owner | see 13, 15 | |
| - | Warning lines only at choices that are on | **Pass.** Background and Data Saver missing: with app push **on** (the server's default) the Apps page shows both lines (`data_saver`, `background`); app push **off**: the Apps page shows none; Mobilnett keeps its Data Saver line (its own feature, `mobile_choices`) | |
| - | The tailnet http line | **Pass.** Server URL `http://100.64.0.1:3121`: "Plain http, only through the tailnet." with no "https://<navn>.ts.net er bedre." advice | nb string checked in `values-nb` ("Vanlig http, bare gjennom tailnettet."); the nb screenshot wasn't taken (the kiosk's suspended Gboard refused the edit outside the hold) |
| - | The flow's look | **Fail, fixed.** "Senere", "Hopp over", "Gjort" and the Wait screen's "Ferdig" were **invisible** (the borderless buttons took the theme's `accentButton` text colour, `kid_ground`, the screen's own ground: the button area was one colour, 12,12,20) - still tappable, but a parent sees only "Åpne". Fixed (1c584180e): they name `?android:textColor`. Re-run: drawn in the text colour | the same, fixed |

## Bugs found and fixed (on the branch, with tests)

1. **The flow's secondary buttons were invisible** (14 and 16): `activity_setup_flow.xml`'s `setup_secondary`/`setup_tertiary`
   now set `android:textColor="?android:textColor"`. Test: `SetupFlowTest` "the borderless buttons are drawn in the text
   colour". Commit 1c584180e.
2. **"Sett på pause i 2 t" in the flow lost the flow** (row 13): the pause's leave of lock task clears every locked task,
   the flow's too. Android 14 then starts Home; Android 16 leaves parent Settings in front. The PIN's success marks the
   pause (`SetupRun.markPausedFromFlow`); Home's resume (14) or the destroyed flow itself after 1.5 s (16) brings the
   flow back once, within 15 s and only with the window open; "Ferdig" and Back clear the mark. Tests: `SetupFlowTest`
   (`flowReturnDue`, the one-shot take, the clear, both hooks). Commits a9867e049 and 1c584180e.
3. **Mobile data on Android 16** (row 4): the SIM page (`ACTION_NETWORK_OPERATOR_SETTINGS`) has no Mobile data switch
   there. From API 36 the Internet panel comes first (`mobileDataActions`; the contract's two actions unchanged). Test:
   `SetupFlowTest` "mobile data opens the Internet panel first from Android 16". Android 15 not checked (no run on 35):
   it keeps the SIM page first - **owed** on an API 35 image or a phone on 15.
4. **The flow didn't see the panel's switch** (16): the Internet panel is a SystemUI dialog over the flow - no pause, no
   result after the switch - so the step stayed until the next resume. The flow reads its step again when its window
   gets the focus back (`onWindowFocusChanged`). Test: a source check in `SetupFlowTest`. Commit 1c584180e.

## Seen, not changed

- **The launcher's language in the hold.** The PWA's `launcher_ui.language` is applied in Home's `resumeContent`
  (`LauncherLocales.applyIfSafe`), which the hold skips (Home leads to the flow), so the flow shows the phone's system
  language until the hold ends. On a real phone the QR sets the system language from Phone Setup (`nb_NO` by default),
  so this shows only when the parent chose another launcher language than the phone's. After the hold, a language change
  is refused while lock task is on (design 78 §5.3's known refusal, seen as `Can't change activity type once set`). The
  nb and en screenshots used `cmd locale set-app-locales` before the first Home.
- **The progress line** reads the run's shown count: a step taken again from the end list is "k av k".
- **The notification tap through a collapsed group** opens Home (Android's group summary), on 14 and 16; expanded, the
  row opens the flow.
- **The 30 s Wait buttons** start over when the screen is recreated (a language change).
- **Notification access is never greyed** here: no restricted-setting line is needed on 14 or 16 for an adb-installed
  Vibb; the Jelly Star (provisioned) is still owed.
- The window notification's text in the hold is the pause's ("All restrictions paused until …"), as designed (§6.1.2:
  the window's notification is the pause's).

## Not run

- Row 9d (an app from the PWA in the hold): no catalog app on the offline rig (no Play, no F-Droid repo here).
- Rows 15-16 of the design's table (design 110's map steps): design 110 is not built.
- The ceiling's elapsed-clock half (4 hours of elapsed time; `RestrictionsPauseTest` covers it).
- Android 15 and 17 (no images run); the Jelly Star (owed, design 114 §11: the 6-tap QR into the hold, the wizard's
  screen lock after "Ferdig", Unihertz's page names, the SIM page and the Internet panel, the restricted-setting check).

## Screenshots

`jelly36`, nb and en, for the user (not committed): the flow's step, a parent step, the end, "Sett på pause i 2 t", the
Wait screen at 0 s and 30 s, Settings' Setup row, the PWA's summary and Oppsett in the hold and after, Connect without
and with a Parent PIN; the Android pages each "Åpne" opened (both versions, `e34-*` and `j36-*`).
