# Emulator re-check on main 843bf17 - 2026-10-14

A short re-check of what changed since `2026-10-14-emulator-round.md`: the final lists of design 26c ("Fixes after the
emulator round") and design 34 (the emulator-round fixes, steps 11 and 12). No product code changed. Time-boxed by the
orchestrator: the rest is marked BLOCKED (time).

**Result: 33 checks - 22 pass, 4 fail, 7 blocked.**

| Area | Pass | Fail | Blocked |
|---|---|---|---|
| Spotify (§6l, 26c's final list) | 13 | 3 | 3 |
| Design 34 step 11 (music library) | 3 | 0 | 0 |
| Launcher fixes (N7, N8, N9, N10) | 3 | 0 | 3 |
| Step 12 (Vibb One) | 3 | 1 | 1 |

Four findings: **R1** (Medium) the call's resume is sometimes undone at once; **R2** (Low, debug only) N4 is not fixed;
**R3** (Low) the library hides the bar after a list-to-context flip; **R4** (Low) the VPN session name stays English.

## Setup

- **Code:** branch `emulator-recheck-1014` = `origin/main` 843bf172.
- **Builds** from this worktree, nothing else building: debug launcher 0.23.6-debug (tsnet stub), debug music app,
  server 0.24.0 debug (`CARGO_TARGET_DIR=~/.cache/handy-target/emu`).
- **Server:** scratch dir `scratchpad/recheck/srv`, `BIND_ADDR=0.0.0.0:3207`, `INSECURE_COOKIES`, the Spotify bases on the fake.
  - Its `data/` is a copy of the previous round's database (migration 76).
  - Before the first start, a catalog row `Vibb`/`me.vibb.launcher`/`is_launcher = 1` was inserted, to see migration 0077
    rename it. The row was then switched off ("Updates" unticked), so the real release is never offered to the emulator.
- **Emulator:**
  - AVD `handy36`, wiped (`-wipe-data`), swiftshader, `-no-audio`, `wm density 240`, zone `Etc/UTC`, en_US.
  - A2 ran first, with the music app unmanaged. Then §4 provisioning, enrolled as device 1 "Testtelefon" with a new
    setup code; the Vibb language is nb.
  - The emulator was OOM-killed once (qemu 6.5 GB anon RSS, N13 of the last round) and cold-restarted.
- **Fake variants** (scratch copies, not committed):
  - `fakeold`: the stub as before the N1 fix, which posts the `ended` pause.
  - `fakestop`: `fakeold` whose `ended` never advances, i.e. a stub stopped at a track's end.
- **Evidence** (not committed) is in `/tmp/claude-1000/-workspace-handy/430a895f-506c-4fe9-bfff-b4b9a36a016b/scratchpad/recheck/`:
  `logs/` (fake logs, logcat per check, `calls-*.txt`, `flip-*.txt`, `doze.txt`), `rec/` and `rec2/` (the state
  recorder), `shots/`.
- **Committed:** six screenshots in `docs/testing/2026-10-14-emulator-recheck/`.

## Spotify (design 26c's final list)

| # | Check | Expected | Observed | Result | Evidence |
|---|---|---|---|---|---|
| A2 #0a | Boundaries, fake as it is (`--track-seconds 20`) | Plays on, locks held | PLAYING through A → C → D (titles follow), one `play:`, locks held until the list's end, then STOPPED | PASS | `logs/a2-0-new*` |
| A2 #0b | Boundaries, old stub posting the `ended` pause | Plays on, locks held | Same: PLAYING through all three, no lock release until the end, one `play:` | PASS | `logs/a2-0-old*` |
| A2 #0c | Stub stopped at a track's end | A new window or the end within ~2 s, no "Trenger internett" | A ends at 30.0 s, then `play: 2 uris from …C…` at 32.3 s; C ends, `play: 1 uris from …D…` 2.4 s later. No `unreachable`, no "Trenger internett"; the player showed "Spor 3 av 3" playing | PASS | `logs/a2-0-stop*` |
| A2 #4 | Unplayable C mid-list | C named alone, A heard, D plays | `item_errors` = only `1FakeTrackC…`. D played, after one redundant `play: 1 uris from …D…` that coincided with the stub's own start of D. The list ran out and closed to the library ("fortsetter der du slapp") | PASS | `logs/a2-4b*`, report.json |
| A1 #19 | HOLD on every call with a session | `MusicCalls: call began: … -> HOLD` | 14 short calls: every one logged `-> HOLD`, both `music playing -> HOLD` and `music not playing -> HOLD` (the page's pause first), and `-> RELEASE` at the end | PASS | `logs/calls-*.txt`, `a1-19*` |
| A1 #19 | A short call resumes what it paused | PLAYING after `gsm cancel`, no `play:` | 11 of 14 PLAYING. **3 stayed PAUSED**: the locks were held at RELEASE, then released 10 ms later (R1). Two of the three had the music app in front during the ring | FAIL (R1) | `logs/a1-19-logcat.txt`, `calls-1.txt`, `rec/`, `rec2/` |
| A1 #19b | A call longer than 5 min | Plays on after cancel | 5 min 35 s call. The page went at 5 min (`idle for 5 min - the web player goes`). RELEASE made a new page and `play: 2 uris from …C… at 60857 ms`; PLAYING | PASS | `logs/a1-19b.txt` |
| A1 #19c | Play during an answered call | Nothing starts, during or after | Paused before the call; call answered. Bar's play: `spotify: a start refused (a call)`. A headset play: nothing. After `gsm cancel`: still PAUSED, no `play:` | PASS | `logs/a1-19c-logcat.txt` |
| A1 #19c' | The same with a VoIP ring while paused | Nothing starts | Needs an app posting a call-category notification with a full-screen intent; the shell can't, and none is on the image | BLOCKED | |
| A1 #20b | Another app took the audio, then a call | Vibb stays paused after the call | Vibb playing, then Chrome played a WAV (fake-audio): Vibb PAUSED, Chrome on top of the focus stack. Chrome paused, then a call: HOLD/RELEASE, Vibb stays PAUSED, no `play:` | PASS | `logs/a1-20b-logcat.txt` |
| A1 #10 | A seek past the buffer, through the debug proxy | The tone jumps | Seek to 255 s of a 300 s track: the WebView asks `Range bytes=4063232-` **31 times in 30 s** (every 0.4-1.3 s). The position loops 254.9 → 258.9 → 254.9 s. The fake answers that range correctly (curl: 206, `Content-Length 736812`, `Content-Range bytes 4063232-4800043/4800044`). The same stall follows a resume at a stored place (A1, 88 s) | FAIL (R2) | `logs/a1-10.log`, `a1-fake.log` |
| A1 #13 | The queue's end after a seek | Plays out, closes | The seek stalls (R2) | BLOCKED (R2) | |
| A1 #16 | Doze with track changes | Plays on with track changes, wake lock held | Run on the phone alone (the same proxy): 60-track list, 60 s tracks, screen off, `force-idle`. 12 min in deep IDLE: 12 track changes (one `audio FAKEtrack…` per minute), PLAYING, the wake lock held throughout. 12 of the 30 min (time) | PASS (12 min) | `logs/doze.txt`, `a1-16.log` |
| A2 #18 cover | The flip, then a cover tap | Context plays, bar/player, no `queue:` after, the leftover skipped | `web player: the entry's mode changed under its play - its new queue starts`, then one `play: context spotify:playlist:FAKEplaylist…`. The player is open with "Fake track 2". No `queue:` after the play. `spotify: our leftover queue add skipped` (track 53) | PASS | `logs/flip-all.txt`, `a2-18-cover*` |
| A2 #18 bar | The flip resumed from the bar's play | Context starts | **After the flip the library shows no bar**: the media session still holds "Fake track 50" paused, but the bar isn't drawn (R3). Nothing to tap | FAIL (R3) | `logs/bar-ui.txt`, `shots/bar-now.png` |
| A2 #18 headset | The flip resumed with a headset play | Context starts | `MEDIA_PLAY`: the mode-changed line, one `play: context …`, the player shows it, the leftover skipped, no `queue:` | PASS | `logs/flip-all.txt` |
| A2 #18 notification | (extra) The same from the notification's play | Context starts | Same as the headset; the bar then appears with the context | PASS | `shots/a2-18-notif-lib.png` |
| A1 alarm, Home | Home's card during an alarm | Paused, never playing | Vibb playing, then the alarm at 20:28: the session PAUSED; the card "Spilles nå: Kveldsmusikk. Uten artist" shows play ("Spill") | PASS | `shots/alarm-home.png` |
| A1 alarm, lock | The lock's tile during an alarm | Paused | Power off/on during the ring: the glance tile shows the play button | PASS | `lock-during-alarm.png` |

## Design 34 step 11 (music library, density 240, nb)

| Check | Observed | Result | Evidence |
|---|---|---|---|
| Full library with the bar, 2.0, gesture navigation | Cover at 120 dp. The title, "Musikk · spor 13 av 60", the progress line, the dots, the category tiles ("Alle", "Musikk" whole) and the bar are all shown | PASS | `library-2.0-gesture.png` |
| The same with three-button navigation; vertical drags | Cover 64 dp, every line, the dots and the tiles shown. A vertical drag on the title scrolls the slot by 12 px (8 dp), and so does a drag on the cover (down and up). Small detail: at 64 dp the cover's play badge nearly covers the note glyph | PASS | `library-2.0-three-button.png` |
| Nytt empty | "Ingenting nytt akkurat nå", no "Fjern alle" | PASS | `shots/s11-nytt-empty.png` |

**For 34 step 11's status:** with three buttons at 2.0 and the bar, the slot scrolls (12 px) on a drag on the title and on
the cover. The floor needs no further change.

## Launcher fixes (design 34, "Fixes after the emulator round")

| Check | Observed | Result | Evidence |
|---|---|---|---|
| Home at 2.0, nb, 3 columns, contacts Mamma/Pappa/Mormor | Names whole and shrunk ("Mamma", "Mormor"; the peeking "Nødnu…" ellipsized). Tiles hyphenate: "Tele-fonbok", "Chro-me", "Innstil-linger", "YouT-ube". No bare letter split, also after a scroll down and back | PASS | `home-2.0-nb.png` |
| Home at 2.0 in en and with 4 columns; the phone book | Not reached | BLOCKED (time) | |
| The lock after a font-scale change | Glance at 1.0, then `font_scale 2.0` with the lock showing: the clock, date, tile and buttons resize in place and the date is centred. The same after screen off/on. Back to 1.0: the sizes return | PASS | `lock-glance-2.0-live.png` |
| Keypad at 2.0 after the change | Not looked at separately | BLOCKED (time) | |
| Time-rule screen "Til … p.m." | Not reached | BLOCKED (time) | |
| "Ringe 112?" on the lock, light and dark mode | `cmd uimode night no` and `yes`: both a night panel with peach AVBRYT/RING. AVBRYT tapped by its bounds; no call was placed (`dumpsys telecom`: 0) | PASS | `lock-112-confirm-dark.png` |

## Step 12 (Vibb One)

| Check | Observed | Result | Evidence |
|---|---|---|---|
| Settings › Apps › App info | "Vibb One (debug)" | PASS | `shots/s12-appinfo.png` |
| Notification permission dialog and the ongoing notification header | Dialog: "Allow Vibb One (debug) to send you notifications?". Shade: the group header "Vibb One (debug)", the ongoing notification's title "Vibb One" | PASS | `shots/s12-notif-perm.png`, `s12-shade.png` |
| VPN session name (and dialog) | As device owner the VPN starts without a dialog. `dumpsys connectivity`: `sessionId=Vibb One filter` - English, though Vibb's language is nb (`get-app-locales`: `[nb]`). nb should be "Vibb One-filter" (R4) | FAIL (R4) | |
| Apps tab after migration 0077 | The DB at 76 with a launcher row named "Vibb". After the new server's start, `_sqlx_migrations` max 77 and the row reads "Vibb One". The Apps tab's catalog lists "Vibb One - Launcher · all phones". The app page says "Vibb One itself: on every phone, and it can't be removed." | PASS | `pw/shots/s12-apps.png`, `s12-app-page.png` |
| The PWA's About row | Not reached | BLOCKED (time) | |

## Findings

### R1 - Medium: a call's resume is sometimes undone at once; Vibb stays paused after the call

- **What happens.** In 3 of 14 short calls (`gsm call`, 5 s ring, `gsm cancel`) while Spotify played:
  - the launcher sent HOLD (logged) and RELEASE (logged);
  - at RELEASE the music app resumed (`wake and Wi-Fi locks held`), then 10 ms later `wake and Wi-Fi locks released`;
  - the session stayed PAUSED.
- **Pattern.** Two of the three had the music app in front during the ring. Both orders failed:
  - one with `music playing -> HOLD` (the launcher's HOLD came first);
  - one with `music not playing -> HOLD` (the page's pause came first).
- **State recorder.**
  - Case 1 (`rec/…201242Z.jsonl`, t 1291234): `resume`, `playing 85290`, `paused 85290` 5 ms later.
  - Case 2 (`rec2/…201554Z.jsonl`, t 1411173): `resume`, `playing 20710`, then our own `seek` (to 17710), then
    `paused 20710` and `paused 17710`.
  - In the passing calls there is no seek and no pause after the resume.
  - So the page reports paused right after our resume. A likely cause is a race with Chromium's own resume after its
    transient focus loss, or the 3 s rewind seek. The renderer takes that paused state as the page's pause and keeps it.
- **Repro.** A1 playing, the music app in front, then `gsm call` / `gsm cancel` after 5 s. Repeat about 3 times.
- **Evidence.** `logs/a1-19-logcat.txt` (20:12:16.141/.151), `logs/calls-1.txt` (20:14:37 and 20:16:37), `rec/`, `rec2/`.

### R2 - Low (debug path only): N4 is not fixed - a seek or a start at a place still stalls through the debug proxy

- **What happens.** With `DebugProxy` (`Content-Length` passed, no call timeout), a seek past the buffer still loops:
  - the same `Range bytes=<n>-` is asked every 0.4-1.3 s;
  - the position is stuck or loops;
  - the page says playing.
- **Not the fake.** It answers the range correctly (curl above).
- **Same stall elsewhere.** A resume at a stored place (88 s) after a force-stop stalls the same way.
- **Not covered by the unit test.** `DebugProxyTest` checks the proxy's fetch; what it doesn't cover is how Chromium
  treats an intercepted 206 for a media element.
- **Next step.** Check whether the WebView honours `Content-Range` from `shouldInterceptRequest` for media at all on
  WebView 133 (it may need a 200 with the whole body, or a direct LAN URL for the debug fake's audio).
- **Effect.**
  - Blocks A1 #10 and #13.
  - It probably also adds noise to R1, since the call tests resume the page.
  - The real SDK doesn't use this proxy.

### R3 - Low: after a list-to-context flip the library hides the bar while the session still holds the old list

- **What happens.** A2 #18 with the list paused at track 50, then entry 6 flipped to `spotify: "context"`:
  - the media session and the notification still show "Fake track 50 — Fake Artist" paused;
  - the library draws no now-playing bar.
- **Why.** `nowView` finds no item of the stored queue in the entry's new items, so it returns null.
- **Effect.** The bar's play, one of the four ways N1 of the QA fix round names, can't be reached. The notification's
  play and the headset do start the context (both PASS). A cover tap does too.
- **Evidence.** `logs/bar-ui.txt`, `shots/bar-now.png` (library with no bar), `shots/a2-18-notif-lib.png`.

### R4 - Low: the VPN session name stays "Vibb One filter" on an nb Vibb

- **What happens.** After enrolment (Vibb language nb from the server, `get-app-locales` `[nb]`), `dumpsys connectivity`
  shows `sessionId=Vibb One filter`. The launcher's two notifications were English too ("Content filter active",
  "Ready for parent comm…").
- **Likely cause.** The VPN and the notifications started before the policy's language arrived, and nothing
  re-establishes or reposts them.
- **Not checked.** Whether a reboot or a later VPN restart gives "Vibb One-filter".

### Also seen

- **A resumed list wrapped round** (not on the re-check list). A2, with the bookmark on the last track D, a cover tap
  sent `play: 3 uris from …D…` (D, then A, C). With C unplayable, the run ended paused on C, not closed.
  Evidence: `logs/a2-4.log`.
- **The emulator was OOM-killed once** (environment, as N13 of the last round): qemu reached 6.5 GB anon RSS after about
  15 WebView restarts.

## Not done (time)

- Home at 2.0 in en and with 4 columns, and the phone book.
- The keypad after the font-scale change.
- The time-rule screen's "Til … p.m.".
- The PWA's About row.
- The VoIP ring while paused (needs a VoIP test app).
- 30 min of Doze (12 min done).
- A1 #13 (blocked by R2).
