# Design r10 (Keystore wrap) - emulator run, 2026-10-25

`jelly36` (the user's profile: 480x854 @ 240 dpi, Android 16 Google Play image), wiped; the test server on
`http://10.0.2.2:3160` (main at 0.49.0; phone 1 "Testtelefon", **lock mode android**, parent PIN set, Child PIN 2468);
the old build = `launcher-v0.36.0-rc.8`'s debug APK (main 0fb3c6db), the new build = main 74f5faa5 (r10 merged, code QA's
fix round in). Scripts in `scratchpad/r11/`: `secrets.sh` (the four fields' prefixes, never the values), `unlock.py`
(wake, swipe, the pad that is really on top, the PIN), `keys.py` (digits on a dialog's keypad). Screenshots are black on
the lock (secure windows), so every observation is a UI dump or a log line.

| # | Check (design §8) | Result |
|---|---|---|
| 1 | Upgrade: enrolled on the old build, Child PIN set, one pause opened and ended (a relock token exists); install the new build | **PASS**. Before: `mdm.device_token`, `pin_hash`, `pin_salt`, `window_relock` `token` plain hex. ~25 s after the first start: all four `ks1:…`; no `.bak` in `shared_prefs/`; the cached `kid_lock` has no `pin_hash` key (the one `pin_hash` left in the file is `override_pin_hash`, the parent's). The device page's last seen moved ("seen just now"), the kid PIN opened Android's pad, the parent code opened Settings. |
| 2 | Redacted cache (PIN changed in the PWA) | **PASS (android mode)**: the Child PIN saved in the PWA reached the phone as the one-shot command at the next report (`AndroidLock: apply … kidPin=true secure=true`); the new PIN opens. (The old-PIN refusal is Android's, not ours, in this mode.) |
| 4/9 | `key secrets` / `key all` **without** a real process restart | `am force-stop` did not end the device-owner process the first time (same pid): the memo and the cached key handle kept the token and PIN usable - exactly check 9's "the memo keeps them for this process". The sync went on. |
| 5 | Total loss: `key all`, the process really ended (`force-stop` twice + `am kill`), reopened | **PASS**. The glance shows the UNUSABLE prompt ("Only the parent code works right now"); the device page goes "Away" (no sync - the token is unreadable); Settings' parent-code dialog shows **NEW SETUP CODE** at once with "The code can't be checked until the phone reaches the server."; a wrong parent code is refused; the button opens the "Setup code" dialog; the code typed in **lower case** (`ccga-bdxm`, check 13) is accepted: "The phone is connected again. Try the parent code once it has synced.", the button is gone, the server opened a new command stream, the device page is "Online · seen just now", `mdm.device_token` is a new `ks1:` value. After the sync the parent code 824613 opens Settings; the kid PIN opens the lock. |
| 7 | Boot cost on the main thread (one reboot, debug build, loaded emulator) | `Unlocked setup took 5843 ms`, `Unlocked setup tail took 354 ms`, `Boot Home started 11592 ms after the unlock reached this process`, `Boot cover taken over (android mode, boot 2)`; the worst skipped-frames line 322 at the boot. Inside the previous build's range on this emulator (RC3-4: 4.5-10.8 s, Home 13-22 s after the unlock) - no regression seen; the Jelly Star release-build measurement stays owed (RC3-4). |
| 3, 6, 8, 10, 11, 12 | not run here | 3/10 need an Android credential set inside a window (the relock path; the sealed reset token exists and is `ks1:`, its use is unobserved); 6 is unchanged by r10; 8 needs a `*.ts.net` server; 11 needs a second fresh enrolment on the old build (the format is one-way); 12 (screen off during the enrol call) is a device check. |

## Findings

- **F1 (Low, android mode): the UNUSABLE prompt is wrong there.** With the kid hash unreadable in android mode the
  glance says "Only the parent code works right now", yet the kid PIN still opens Android's pad (Android holds the
  credential; r10 never touches it). The prompt is right in own mode only. Fix: in android mode the stand-in shows no
  prompt (or "Sveip opp for å låse opp" as always); `GlancePrompt.UNUSABLE` gated on the lock mode. Launcher, XS.
- **F2 (note, pre-existing): our keypad sits under the glance in android mode**, so a uiautomator tap at the keypad's
  "Parent code" position hits the glance's "Emergency call" (the two overlap: `[161,746][318,812]` vs
  `[133,734][347,806]`). Not reachable by a finger (the keypad isn't visible), only by a script; the parent's way to the
  code dialog in android mode is the kid PIN → Settings, which works. No change.
- **F3 (tooling): `am force-stop` on the device-owner HOME app can leave the process alive** on this image; a check
  that needs a cold process must verify the pid changed (check 9 vs 4/5 differ exactly in this).
