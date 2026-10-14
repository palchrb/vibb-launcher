# Emulator round on main 05fd8e3 - 2026-10-14: §6l re-run (Spotify, design 26c) and design 34

Two passes on one emulator, no product code changed:

1. the re-run of `docs/testing/emulator.md` §6l part A after the fixes of 2026-10-14 (F1-F10 of
   `2026-10-14-spotify-6l-a.md`, design 26c "Fixes after the emulator check §6l A"), with the new call and alarm checks;
2. the emulator pass of design 34 (every step's "still to check on the emulator" list, steps 0-11 and 13).

**Result: 74 checks - 59 pass, 7 fail, 8 blocked.**

| Area | Pass | Fail | Blocked |
|---|---|---|---|
| §6l A1, the whole chain | 27 | 2 | 2 |
| §6l A2, the phone alone | 13 | 3 | 0 |
| Design 34 | 19 | 2 | 6 |

A design 34 row whose core passes but which has a finding is counted as a pass; the visual findings are listed below.

The Spotify fixes hold: no crash anywhere (F1), the tone keeps playing with Chromium's media session as the only
focus holder (F2), offline steps stay put (F3), the browser login lands on the callback (F4), and the account word
reaches the PWA on "Sync now" (F7). The new alarm and sleep-timer rules (QA H1) work.

Six new findings, the two most important:
- **N1 (High).** In list mode, a paused state at a track's end makes the phone show paused and release its wake and
  Wi-Fi locks while the page plays the next track.
- **N3 (Medium).** On the emulator the launcher never sends `CALL_HOLD` for Spotify, so the call rule never engages.

The contacts-row swipe results the user's decision needs are in [their own section](#the-contacts-row-swipes-design-34-step-4-m1-for-the-pending-decision).

## Setup

- **Code:** branch `emulator-round-1014` = `origin/main` 05fd8e3e.
- **Builds** from this worktree, nothing else building on the VM:
  - debug launcher 0.23.6-debug (tsnet stub) and debug music app, one debug key;
  - server 0.24.0 debug, `CARGO_TARGET_DIR=~/.cache/handy-target/emu`.
- **Server:** run from `scratchpad/emu/srv` (`static` linked), `BIND_ADDR=0.0.0.0:3206`, `INSECURE_COOKIES`, `RUST_LOG=debug`,
  `SPOTIFY_ACCOUNTS_BASE=SPOTIFY_API_BASE=http://127.0.0.1:8731`.
- **PWA:** driven with Playwright/Chromium 1140 at 390 px (forced password change, TOTP computed in the script).
- **Emulator:** AVD `handy36` (Android 16 `BE2A.250530.026.D1`, WebView 133), swiftshader, `-no-audio`, `wm density 240`
  (the Jelly Star's 320x569 dp), zone `Etc/UTC` (Phone Setup set to it, §3b), system language en_US.
- **Order of the run:**
  1. A2 on a freshly wiped AVD with the music app unmanaged;
  2. §4 provisioning (adb + "Enroll with code", the `WRITE_SETTINGS` app-op granted);
  3. A1;
  4. design 34.
- **The fake used for long play (deviation, see N1).** `scripts/fake-spotify.py`'s stub posts a `paused` state at
  every track's end, and the phone reads that state as a pause (N1). So:
  - N1 is shown with the fake as it is.
  - Every check that plays across a track boundary used a scratch copy of the fake whose `pause` listener ignores the
    `ended` pause (one line: `if (!self._audio.ended) self._changed();`; `--selftest` OK).
  - All of A1 ran on that copy.
  - Rows that used it are marked †.
  - Nothing in the repo was changed for this.
- **Track lengths:** A2 used `--track-seconds 20`, except the 60-track window (8 s and 4 s tracks, to reach track 50 in
  minutes).
- **Emulator crashes:** the emulator was OOM-killed twice (N13) and cold-restarted (`adb emu kill`) once to clear a
  phantom call (N14).
- **Evidence** (not committed) is in `/tmp/claude-1000/-workspace-handy/430a895f-506c-4fe9-bfff-b4b9a36a016b/scratchpad/emu/`:
  - `shots/`: about 200 screenshots, the lock's through the emulator console.
  - `logs/`: the fake's per-run logs, logcat dumps, `server.log`, `swipes-*.txt`, `gfx-home.txt`, `qemu-rss.log`.
  - `rec*/sdk_states/`: the state recorder's files.
  - The scripts.
- **Committed:** ten small montages in `docs/testing/2026-10-14-emulator-round/`.

## §6l A1 - the whole chain (managed phone, fake on 8731 †)

| # | Step | Expected | Observed | Result | Evidence |
|---|---|---|---|---|---|
| 1 | Not configured | Registration text, no add form | Text as specified; `form#add` and `form#login` absent | PASS | `logs/pw-a1-1.txt` |
| 2 | The app | Client ID saved, `…cdef`, loopback redirect | "Saved.", "ending in cdef", redirect offered | PASS | same |
| 3 | First login **in the browser** (F4 fix) | The browser lands on `…/callback?code=…` | The navigations go `/music/spotify/login` → the fake's `/authorize` (consent page) → `/authorize/accept` → `http://127.0.0.1:43117/callback?code=…` (chrome-error, as expected). Then the paste: `login=ok`; fake `authorize: consented as parent -> FAKE-CODE-1`, `code exchanged for fakeparent`. No workaround. | PASS | `logs/pw-a1-1.txt`, `logs/a1-fake-part1.log` |
| 4 | The kid's login | `fakekid` listed | `FAKE-CODE-2`, "Ella" listed | PASS | same |
| 5 | Plays as | Ella; the Web Filter note | "Plays as Ella" on every entry; the Web Filter's Spotify note | PASS | `logs/pw-a1-2.txt` |
| 6 | Links + Check Now | Playlist 200 (fakeparent), 3 tracks; album; show; artist "Spotify picks" | As expected. The artist page says "an artist plays as Spotify does" (F5 fix). | PASS | `logs/pw-a1-3.txt` |
| 7 | Debug switch | `spotify: http://10.0.2.2:8731`, report `none`, the bridge's entries | As expected (4 entries) | PASS | logcat |
| 8 | Tiles | Kinds and lines; no "Spotify" | As expected; UI dump has 0 "Spotify" | PASS | `shots/a1-lib.png` |
| 9 | Cover tap | One `play:`; the tone keeps sounding; the focus stack | One `play: 3 uris … at 0 ms`; plays on. `dumpsys audio`: only `org.chromium.content.browser.AudioFocusDelegate` (the music uid) on the stack. No `spotify: audio focus` line. Report `"spotify":"ok"`. | PASS | `logs/a1-fake-part1.log` |
| 10 | Pause, resume, seek | No `play:`; a seek past the buffer is a 206 and the tone jumps | Pause, resume and seek: no crash, no `play:` (F1 fixed). **A seek past the buffer stalls the tone**: the WebView re-requests `Range bytes=<n>-` every 1-2 s for minutes while the page reports playing at a frozen position (N4, the debug proxy). | FAIL (N4) | `logs/a1-fake-part1.log` 373-404 s, `rec1/` |
| 11 | Next, mashed next, previous | One `play:` per burst | Next: one `play:` from track 3. Next ×2 within a second: **one** `play: 1 uris from …D…`. Previous ×2 within 3 s: one `play: 2 uris from …C…`. Previous later restarts the track through the SDK (no `play:`). | PASS | fake log 306-356 s |
| 12 | A pick | One `play:` from the pick | Yes (Liste, track 1 → "Spor 1 av 3") | PASS | |
| 13 | The queue's end | Plays out, closes, `clear_state` | The required seek near the end stalls (N4). Without a seek it passes in A2 † (C → D → A, closes to the library, "fortsetter der du slapp"). | BLOCKED (N4) | |
| 14 | Resume after a restart | Bar paused at track 2, fake silent; one `play:` at the place | Bar "Månesang — Kari Li", "Musikk · spor 2 av 3", paused; the fake shows only a BrokenPipe from the killed page. Play: one `play: … at 26945 ms`. | PASS | `shots/a1-resume-open.png` |
| 15 | A headset's play after a restart | One `play:`, the session shows the title, playing, no crash | `play: 3 uris from …C… at 42399 ms`. `media_session`: PLAYING "Månesang — Kari Li". `ExoPlayerImpl: Init` only. No crash (F1). | PASS | |
| 16 | Screen off and Doze | Plays on for 30 min with track changes; wake lock only while playing | Ran 15 min in forced deep idle (`deviceidle get deep` = IDLE): the `vibb:spotify` wake lock was held throughout, and the page alive. The tone stalled after about 7 min (N4) and the 14:30 episode never reached its next track. A second 15 min with an alarm ran too. | BLOCKED (N4) | `logs/a1-fake.log`, `dumpsys power` |
| 17 | The sleep timer (15 min) | Fade in the last 10 s, paused, full volume on the next play | Paused exactly 15:00 after Sov (15:22:27 → 15:37:27, in Doze); the wake lock was released in the same ms. The fade can't be observed: `-no-audio`, and the fake doesn't log `setVolume`. | PASS (fade unobservable) | `tasks/b3n2hhirb.output` |
| 18 | A bedtime edge | `pm suspend`: paused, page destroyed; plays start nothing; unsuspend stays paused | Paused within 0.2 s, `spotify: web player destroyed`. MEDIA_PLAY: nothing. Unsuspend: still paused. | PASS | |
| 19 | A call (short) | Paused at the ring, plays on after `gsm cancel`, no `play:` | PAUSED while ringing, PLAYING 6 s after cancel, no `play:`. The resume is Chromium's: `MusicCalls: call began: music not playing -> NONE` (see N3). | PASS | logcat 15:04:58 |
| 19b | A call longer than 5 minutes | Plays on after `gsm cancel`; log which came first | 5 min 40 s call: PLAYING after cancel (position moving), no `play:`. **Order:** the page paused first (`spotify: wake and Wi-Fi locks released` 15:05:34.256), then `MusicCalls: call began: music not playing -> NONE` (15:05:34.316). No `CALL_HOLD` was sent (N3). | PASS (N3) | logcat |
| 19c | Play tapped during a call | No tone, no `play:`, `spotify: a start refused (a call)`; after the call plays only if the call paused it | Paused before the call, then a call (answered), then play tapped in the music app: no line. The page held for 66 ms and was paused by Chromium. **After `gsm cancel` it PLAYED**, although the call hadn't paused it. No `a start refused` line: the launcher never sent HOLD (N3). | FAIL (N3) | logcat 15:12:50-15:13:22 |
| 20 | Another app's audio: an alarm | Paused while it rings; plays on after Dismiss by itself, the player follows | PAUSED at 15:15:00 (deskclock `GAIN_TRANSIENT_EXCLUSIVE` on top). Dismiss: PLAYING, the session follows, no `play:`. | PASS | `shots/a1-alarm-ring.png` |
| 20b | Another app's audio: Chrome | Pauses and stays paused, no `play:` | Chrome played a WAV (fake-audio): Chrome's `AudioFocusDelegate` on top, the music app PAUSED. After Chrome's pause: still PAUSED. No `play:`. | PASS | |
| 20c | Pause while the alarm rings, then Dismiss (QA H1) | Stays paused; a `vibb.pause()` right after the page's own resume | MEDIA_PAUSE during the ring. After Dismiss the session stays PAUSED. Recorder: `EVENT pause` (ours), then the page's `playing`, then our `EVENT pause` 0 ms later, then `paused`. | PASS | `rec1/…151939Z.jsonl` |
| 20d | Sov ending while the alarm rings | Stays paused after Dismiss | Sov 15 min from 15:39:09, alarm at 15:54:00. The timer's `EVENT pause` came at 15:54:09 during the ring. Dismiss: page `playing`, our `pause` at once, PAUSED. | PASS | same |
| 21 | Design 28: Home card, lock tile | Show while playing; paused; gone 10 min later | Card "Spilles nå: Eventyrstund. Episode 3…" with Pause. The lock tile showed it playing. 10 min after the pause neither shows. | PASS | `shots/a1-home-card.png`, `lock-glance-playing.png`, `lock-after-10min.png` |
| 22 | The output picker | "Kan ikke spilles på Sonos", the phone plays on, no `SetAVTransportURI` | Toast "Kan ikke spilles på Sonos", output stays Telefonen, PLAYING, `VibbSonos: … left out (spotify)`, 0 `SetAVTransportURI` | PASS | `shots/a1-picker-stua.png`, `logs/fake-sonos.log` |
| 23 | The artist, context mode | One `play: context … at 0 ms`; next/prev without `play:`; next while paused only moves | `play: context spotify:artist:3FakeArtist… from Spotify's start at 0 ms`. Next: "Bare i Sverige". Pause, then next: "Månesang", still paused. No `play:`. (Previous within 3 s couldn't be timed through the UI dump.) | PASS | |
| 24 | The 403 playlist (with the cooldown) | 403 notes; tile goes context; restart resumes at the live track; back to list mode after re-login | `GET …/items: 403 (fakekid doesn't own the playlist)`; the notes text. Tile "rekkefølgen velges for deg". Restart: bar "Månesang — Kari Li", fake silent. Play: `play: context … from …C… (track 2 of 3) at 7623 ms`. After the paste login and "Check Now" (once the 15-minute cooldown had passed): "fortsetter der du slapp". | PASS | `logs/a1-fake-part1.log` 2941 s+ |
| 25 | A refused refresh, "Sync now" ~20 s later (F7) | `400 invalid_grant`, "Spør en voksen" everywhere, `reauthorize`; the PWA's texts; heals | `token: 400 invalid_grant` at the tap. The report said `reauthorize` within seconds (F7 fixed). "Sync now" 36 s later brought the Attention row "Spotify login needs renewing", the Music page and the Spotify page texts. After the re-login and a play: `ok`, the Attention row gone. | PASS | `logs/a1-fake-expire.log`, `pw/shots/a1-refused-summary.png` |
| 26 | No secrets | 0 everywhere | logcat 0; music app (76 files incl. WebView) 0; launcher (27) 0. `spotify_state.json` = `{"outcome":"ok","at":…}`, `spotify_page_account` = `2:2`. Server log 0 (`code_verifier`, F8). | PASS | `logs/final-logcat.txt` |

Counted: 31 rows (#19b-#19c and #20b-#20d are rows of their own) - 27 pass, 2 fail (#10, #19c), 2 blocked (#13, #16).

## §6l A2 - the phone alone (unmanaged, fresh AVD, debug feed, `--track-seconds 20`)

| # | Step | Expected | Observed | Result | Evidence |
|---|---|---|---|---|---|
| 0 | **Track boundaries with the fake as it is** | Plays on across tracks | At track 1's end the player shows **paused** ("Spill") and `wake and Wi-Fi locks released` while the stub plays tracks 2 and 3. The recorder: `paused` at `position 20000`, then the next track `playing` at 0 (N1). | FAIL (N1) | `logs/a2-basic*.txt`, `rec/sdk_states/…135348Z.jsonl` |
| 1 | Window's end and queue add (60 tracks) † | `play: 50 uris`, `queue: …51` at track 50, no gap; a pick of track 3 later | `play: 50 uris from …001`; `queue: …051` as track 50 starts, then 51 plays and `queue: …052`… one ahead. Pick of track 3: one `play:` from 3. Track 3 ends, the leftover add plays: `a track that isn't ours was paused`, then `play:` from 4, list goes on. Never "Kan ikke spilles". (8 s tracks.) | PASS | `logs/a2-window.log` |
| 2 | Cold start, second tap and the play/pause button | Pause shown throughout; one `connect:`, one `play:`; no crash | `--slow-ready 15`: a second cover tap gave one `connect`, one `play`. The play/pause button during the wait (pause, then play): no crash, one `play:` | PASS | `logs/a2-slow*.log` |
| 3 | Elsewhere: play takes it back | `play:` at the place | "Spilles et annet sted", then play: `play: 2 uris from …C… at 9287 ms` | PASS | `logs/a2-elsewhere.log` |
| 4 | One unplayable track (C) | C skipped ("Kan ikke spilles"), D plays, the report names C | † The stub skipped C and played D. The phone resent `play: 2 uris from …C…` and stopped with `three unplayable in a row`. The report lists **A and C** as unplayable, though A played to its end (N2). With the fake as it is, D plays while the phone shows paused (N1). | FAIL (N2) | `logs/a2-unpl1*.log` |
| 5 | Autoplay after the list † | Paused at once, nothing saved | After D: `FAKEautoplay…` → `a track that isn't ours was paused`; closed to the library; no `spotify_context.json` | PASS | `logs/a2-autoplay.log` |
| 7 | A detached web player (`--transfer-in 60`) | Not ours paused, the podcast plays on, no Spotify wake lock | Spotify play, then the podcast (fake-audio `lan` RSS entry). `spotify: detached - a play from outside is paused`. At 60 s: `a track that isn't ours was paused`. The podcast went 0:10 → 0:57 with no gap. Last `vibb:spotify` REL before it, no ACQ after. | PASS | `logs/a2-detach.log` |
| 8 | Relinked † | Track 2 plays as the copy, ours | `audio FAKErelink…`; no pause, no "not ours" | PASS | `logs/a2-relink.log` |
| 9 | Shuffle and repeat on the account † | Both off before `play:`; in order, once | `shuffle: false`, `repeat: off`, `play:`; A, C, D, then closed | PASS | `logs/a2-shuffle.log` |
| 14 | Offline (F3) and Wi-Fi/mobile data | "Trenger internett", fake silent, next/previous stay; "Trenger Wi-Fi"; plays with stream on | Airplane: "Trenger internett" on the player and the tile; next and previous stay on "Spor 1 av 3"; the fake silent (F3 fixed). Wi-Fi off on cellular: `stream_mobile_data:false` → "Trenger Wi-Fi" (`NEEDS_WIFI`); `true` (downloads off) → plays. | PASS | `logs/a2-offline*`, `a2-wifi.log` |
| 15 | The artist (entry 7) played out † | Context play, steps without `play:`, closes, the context entry gone | `play: context spotify:artist:FAKEartist… at 0 ms`; next and previous without `play:`; after track 5 closed; `spotify_context.json` `{}` | PASS | `logs/a2-artist.log` |
| 16 | Entry 6 as a context | Restart resumes the live track; autoplay paused; a foreign transfer paused | Restart: bar "Fake track 4", fake silent. Play: `… from …004 (track 4 of 5) at 10685 ms`. `--autoplay`: paused, `{}`. `--transfer-in 20`: paused at once, title unchanged. | PASS | `logs/a2-ctx*.log` |
| 17 | A stored track that left the playlist | 404 twice, then from the top; the refused line; the file names track 1 | Exactly that: `play: 404` ×2, `play: context … from Spotify's start`, `spotify: the context's stored track was refused - from the top`, tone, file → FAKEtrack1 | PASS | `logs/a2-404.log` |
| 18 | Our leftover queue add, then a context | When the context's track ends the leftover is skipped (`our leftover queue add skipped`), the context goes on | Two tries: entry 6 flipped to a context in the pushed library **while its list play was alive** (playing; paused right after `queue: …051`). The cover tap **resumed the old list**: recorder `EVENT resume`, `audio …050`, queue adds 52, 53, 54… No player or bar opened, and no context play until the old list ran out (N5). The leftover check itself couldn't be reached. | FAIL (N5) | `logs/a2-18b.log`, `rec/sdk_states/` |
| 19 | The state recorder | Lines per state/event; no token; logcat clean; off | Files with `ready`, `play from 0 to 49 at 0 ms`, `queue 50`…`57`, `pause`, `resume` (A1 adds `play context`, `seek`). `grep FAKE-ACCESS\|Bearer\|token` 0; logcat `"position"` 0. Off: `spotify: state recording off`. | PASS | `rec/` |
| 20 | No Spotify entry left | Tiles go, `none`, cleared, no `spotify_page_account` | `web player destroyed`, `web player's cookies, storage and cache cleared`, report `none`, the file gone | PASS | |

Counted: 16 rows - 13 pass, 3 fail (#0, #4, #18), 0 blocked.

## Design 34 (density 240, nb unless noted; secure screens through the emulator console)

| Step | Check | Observed | Result | Evidence |
|---|---|---|---|---|
| 0 | Recipes | `wm density 240`, font scales, the zone recipe (`Etc/UTC` into Phone Setup) and the app-op line all used as written. `outside_play` didn't arise: the music app was installed before provisioning. | PASS | |
| 1 | Home with the icon states, the Phone book and Settings tiles | Vibb style with a monochrome layer (Disk, Kalender, Kontakter, Maps); without one, its own logo (Kamera). The own logo (Chrome, a white disc). A glyph on a colour (Gmail mail on Rose). A glyph on `auto` (Foto). Telefonbok green, Innstillinger slate. Matches mockup 01. | PASS | `home-font-scales.png`, `shots/home-2.png` |
| 1 | The glance page with an app in each state | Needs app notifications on the lock (only a missed-call row could be made) | BLOCKED | `shots/d34-glance-rows.png` |
| 2 | Press feedback, focus ring, long-press, the badge | D-pad focus: the peach ring on the grid and on a contact; a press dims and shrinks the icon; long-press opens the menu ("Skjul", "Gi nytt navn"); the quiet badge seen on a grid tile | PASS | `focus-press-longpress.png` |
| 2 | Gestures with "Remove animations" (all three scales 0) | Tap → phone book; long-press → menu; swipe-up at the grid's end → drawer; swipe-left → kid Settings; swipe-right → stays Home | PASS | `scripts noanim.sh` output |
| 2 | N6 translucent icon background | No such app on the image | BLOCKED | |
| 3 | Avatars, phone book, 112, the sheet, the call screens | Soft discs with dark ink. The 112 contact is a dark red disc with a red ring. The sheet: with 3 missed calls (red line, badge), with Message ("Melding åpnes i Meldinger"), and plain. Incoming (over the lock) and in-call screens. Matches mockup 03. | PASS | `shots/d34-sheet-missed.png`, `d34-phonebook-112.png`, `a1-call-ring.png`, `d34-incall.png` |
| 3 | The sheet with a photo; the outgoing call screen | No photo uploaded. The emulator's modem drops every outgoing call 20 ms after DIALING. | BLOCKED | |
| 4 | Home at 1.0/1.3/2.0, 3 and 4 columns, nb and en | Fades: the top fade and the bottom fade over the last row; the contacts row peeks with a 16 dp fade at five contacts and doesn't scroll at three. "Innstillinger" at 1.3: "Innstillin-ger" (hyphenated). At 2.0 the row was cut by the screen's end. **At 2.0 contact names break between letters ("Mamm/a", "Mormo/r"). In en: "Chro/me", "Melding/er", and with 4 columns "Ph/one…" (N7).** | FAIL (N7) | `home-font-scales.png`, `home-en-4col.png` |
| 4 | Frame time (N4 of the QA) | `dumpsys gfxinfo` while flinging the grid ×10: 274 frames, 5.1 % janky, p50 16 ms, p90 17 ms, p99 48 ms, GPU p99 16 ms (swiftshader) | PASS | `logs/gfx-home.txt` |
| 5 | Drawer; parent Settings with a switch and a dialog | Night drawer ("Alle apper"), parent PIN dialog (nb), parent Settings night with a switch; the Serveradresse dialog is light (the parent's, as designed). Differs from mockup 02 (N12). | PASS | `drawer-parent-vs-mockup.png` |
| 5 | Phone-book call confirm in light and dark system mode (M1) | "Ringe Mamma?" on the night panel with peach AVBRYT/RING in both `cmd uimode night no` and `yes` | PASS | `shots/cmp-confirm.png` |
| 5 | Wi-Fi password dialog | The emulator has only the open `AndroidWifi` | BLOCKED | |
| 6 | Clock format with the app-op | 24 h: `time_12_24`=24, the lock "15:44", the status bar 24 h (it lagged a few seconds). 12 h: 12, the lock "3:45" (no AM/PM) like the status bar. Follow: back to `null` (the value before). | PASS | `clock-format.png` |
| 6 | Without the app-op | 24 h chosen: the setting stays 12; the PWA's Home Screen shows "Clock format not applied - The phone can't change its clock format yet."; Vibb's clocks follow the status bar (12 h) | PASS | `shots/clock-noappop-lock.png` |
| 6 | Device check: the write under `DISALLOW_CONFIG_DATE_TIME` | The restriction is on (`no_config_date_time` in `dumpsys user`), and the writes of 24 and 12 were accepted (read back). **Recorded: SettingsProvider does not refuse `TIME_12_24` under the restriction on Android 16 (emulator).** | PASS (recorded) | |
| 6 | Vibb in nb on an en_US system follows the status bar | Follow: both 12 h ("3:04" on the lock and the status bar) | PASS | `shots/lock-glance-playing.png` |
| 7 | The time-rule screen: school, bedtime, custom (nb) at 1.0/1.3/2.0 | School "Skole" (book icon), bedtime "Leggetid", custom "Lekser" with 3 exempt apps as rows. The texts grow and the screen scrolls, so at 2.0 the rows, "Nødsamtale" and the code button are all reachable. With four rows at 1.0 the two buttons are below the fold until a scroll. "Til 11:30 PM" (N9). Matches mockup 04 in style. | PASS | `time-rule-vs-mockup.png`, `shots/cmp-rule2.png` |
| 7 | en; the budget lock; 112 with Telecom refusing | en: the launcher kept nb while a phantom emulator call was "active" (by design no language switch during a call, N14). The budget (30 min, 53 used) didn't lock in time. 112 not provoked again (two emulated 112 calls happened by accident, see N14). | BLOCKED | |
| 8 | Keypad and glance at 1.0/1.3/2.0, nb and en | Fresh (after a reboot): at 2.0 the keys shrink (79 dp) and the title, "Nødsamtale"/"Emergency call" and "Foreldrekode" grow, as mockup 05b. 1.0 matches 05. The glance clock is bold. **The lock doesn't follow a font-scale change until it is recreated, and the glance date at 2.0 wraps start-aligned (N8).** | PASS (N8) | `keypad-2.0-vs-mockup.png` |
| 8 | Nødsamtale from the keypad | Confirm "Ringe 112?" (a light dialog, N10). RING → the emulator's emergency dialer stays in front over the lock. | PASS | `shots/d34-112-confirm-lock.png`, `d34-112-dialer-from-lock.png` |
| 9 | Wallpapers: picker names, Home and lock over the three | Picker (nb): Natt, Mørk blå, Skog, Plomme, **Fjord, Kveldshimmel, Skumring** (TalkBack: "Natt, valgt"). Home, glance and keypad over each are calm and legible. en names not checked. | PASS | `wallpapers.png` |
| 10 | Kid Settings at 320/360/411 dp × 1.0/1.3/2.0 | The segment fills the card, the labels wrap and the control grows (2.0: "Lydløs (vibrerer)" on two lines), nothing cut. TalkBack: RadioGroup "Lyd på telefonen", RadioButtons with `checked`. The tap switches NORMAL ↔ VIBRATE (`dumpsys audio`); the volume key still shows its panel. DND: both halves disabled, none checked. | PASS | `kid-settings-vs-mockup.png`, `shots/d34-kids-*` |
| 11 | Music empty states | Library empty: headphones disc, "Her kommer musikken din" plus the body; at 2.0 it scrolls to show all. Nytt: "Ingenting nytt akkurat nå" (with "Fjern alle", N11). Search: "Fant ingenting". | PASS | `music-vs-mockup.png`, `shots/d34-music-*` |
| 11 | The full library with the bar at 1.0/1.3/2.0 | 1.0 and 1.3 fit. **At 2.0 the tile's line "Musikk · spor 1 av 3" is clipped to half height, the category labels ellipsize ("Mu…", "Po…"), the dots are hidden, and the slot doesn't scroll (N6).** | FAIL (N6) | `music-vs-mockup.png` |
| 11 | Without the bar | Not reached: the bar's queue stays stored | BLOCKED | |
| 13 | P13: "until" with the zone | Phone Setup zone = `Etc/UTC` = the phone's. School/bedtime/custom running: the summary and the Devices list say "Skole until 23:00", "Leggetid until 23:30", "Lekser until 23:30". Phone Setup `Europe/Oslo`: "Lekser" alone. Back to UTC: "until" again. | PASS | `pw/shots/d34-p13-summary.png` |

Counted: 27 rows - 19 pass, 2 fail (step 4's hyphenation, step 11's clipping), 6 blocked.

## The contacts-row swipes (design 34 step 4 M1, for the pending decision)

Left swipes (finger right → left, 120 ms) on Home, nb, density 240. "First" = Home's first gesture after the PIN
unlock. "After a tap" = the gesture right after tapping a grid tile and pressing Back/Home. The result is what came to
the front (`dumpsys activity`) and whether the row moved (the first contact's bounds before and after).

**Three contacts.** The row doesn't scroll, and its three 144 dp items cover the whole width, so every start inside the
row lands on an item.

| Start | First gesture after the unlock | After a tap on the Telefonbok tile's **label** |
|---|---|---|
| On an avatar (Mormor, x 384; Mamma, x 140) | **kid Settings opens** | **nothing** (no scroll, no Settings) |
| Between two avatars (x 312, the item boundary) | **kid Settings opens** | **nothing** |
| On a name (y 300) | **kid Settings opens** | **nothing** |
| Control: on the grid (Chrome tile) | - | kid Settings opens |

**Five contacts.** The row scrolls; Mamma, Pappa and Mormor show, Morfar peeks behind the fade.

| Start | First gesture after the unlock | After a tap on the tile's **label** | After a tap on a tile's **icon** (Chrome) |
|---|---|---|---|
| On an avatar (Mormor) | **kid Settings opens** (the row doesn't move) - twice | **the row scrolls** (Pappa becomes first) - twice | **kid Settings opens** |
| On an avatar (Mamma) | **kid Settings opens** | **the row scrolls** | - |
| Between avatars (Pappa\|Mormor, x 277) | **kid Settings opens** | **the row scrolls** | - |
| Below the names, in the row's empty space (y 305) | **kid Settings opens** | **kid Settings opens** | - |

**What this shows.** The outcome depends on the gesture before the swipe, not on where the swipe starts. That matches
the M1 analysis: a DOWN on a contact goes to the clickable item, so `onFling` decides with a stale DOWN.
- **As Home's first gesture:** every left swipe opens kid Settings, even one that starts on a scrollable row (the row
  never scrolls).
- **After a tap on a tile's label:** a swipe on the row scrolls it (five contacts) or does nothing at all (three
  contacts, where there is nothing to scroll).
- **After a tap on a tile's icon:** a swipe on the row opens Settings again.
- **A start in the row's empty space** (below the names, five contacts) always opens Settings.
- **The swipe that does nothing** (three contacts, after a label tap) is the case a kid would notice most.

Logs: `logs/swipes-3c.txt`, `logs/swipes-5c.txt`.

## Findings

### N1 - High: list mode, a paused state at a track's end leaves the phone "paused" while the page plays on

- **What happens.** At a track's natural end the stub posts the HTML audio element's `pause` event as
  `{paused: true, position: <duration>}`, then the next track `{paused: false, position: 0}`. The phone then:
  - shows paused ("Spill") while the next track plays;
  - releases the wake and Wi-Fi locks (`spotify: wake and Wi-Fi locks released` at the boundary);
  - (with an unplayable track) lets the stub play on while it shows paused.

  In Doze this would end the play.
- **Where.** `SpotifyTracker.ourState`: a paused state is an end only when `positionMs < END_RESET_MS` (Spotify's reset
  to 0). A pause at the duration falls through to `Event.Paused(ours = false)`, and the renderer maps it to
  `pwr = false`, `Paused(FAULT)`, `heldByPage = true`. Then `advance()` sees the expected next track **playing**,
  emits `ItemChanged` and never `Resumed`, so `pwr` stays false.
- **Context mode recovers.** `onContextOurs` emits `Resumed` (`wasPaused && !pausePending`): the locks go and come
  back within 10 ms.
- **Evidence.**
  - `rec/sdk_states/20261014T135348Z.jsonl`: `paused 20000 "Uten artist"`, then `playing 0 "Stille natt"`.
  - `logs/a2-basic-logcat.txt`.
  - UI dump "Spor 3 av 3 … Spill" while the fake serves track 3's audio.
- **Repro.** A2 with `python3 -I scripts/fake-spotify.py --track-seconds 20`: play Kveldsliste and wait 25 s.
- **Open question.** Whether the real Web Playback SDK posts such a state between tracks is part B's question (the
  recorder will show it). Either way the phone's list mode is fragile.
- **Suggested.** Treat a paused state at or near the duration as an advance, not a pause (as `ranOut` already measures),
  or let `advance()` emit `Resumed` when the new state plays after an unproven pause. Also make the fake's stub not
  post the `ended` pause, or post it the way Spotify does.

### N2 - Medium: an unplayable track mid-list is blamed on the track that just played out

- **What happens.** The stub emits `playback_error` when it reaches the unplayable C, with no state for C first.
  `SpotifyTracker.onPlaybackError()` blames `unplayable(index)`, the current item, which is A, the track that just
  ended.
- **What follows.**
  - The renderer re-sends `play: 2 uris from …C…`.
  - C errors again, and `spotify: three unplayable in a row - stopped`.
  - The report lists `item_errors` A **and** C.
  - D, which the stub had already started, is paused as "not ours".
- **Repro.** A2, `--track-seconds 20 --unplayable 1FakeTrackC00000000003` (with the † fake; with the fake as it is,
  N1 masks it).
- **Suggested.** Attribute a `playback_error` that arrives after the current item played out (`ranOut`) to the next
  item, or ignore it until a state names the failing track. Part B should record the real SDK's order.

### N3 - Medium: the launcher never sends CALL_HOLD for Spotify; a play tapped during a call starts after it

- **What happens.**
  - Chromium's media session pauses the page at the ring 60-220 ms before the launcher's
    `MusicCalls: call began: music … -> <action>` reads the music session, so it logs `music not playing -> NONE` on
    every call (short, long, answered). The music app's `launcherCall(true)` never runs.
  - The call's resume is Chromium's own after the transient loss. It works short and after 5 min 40 s.
  - The design's M1 (`heldByPage` counted as playing in `callStep`) never gets the chance, and neither does L2
    (`inCall`): a play tapped in the music app during an answered call holds the page for 66 ms, Chromium pauses it,
    and after `gsm cancel` it **plays**, although the call never paused it. No `spotify: a start refused (a call)` line.
- **Where.** `launcher/.../music/MusicCalls.kt` decides HOLD from the session's playing state.
  `music/.../PlaybackService.kt` `Commands.CALL_HOLD -> launcherCall(true)`.
- **Suggested.**
  - Send the call's begin/end to the music app regardless of the session's state (the music app decides what it held).
  - Or have the music app read the telephony state itself for `inCall`.
- The order asked for in §6l: **the page's pause came first** (`VibbSpotify` 15:05:34.256), then `MusicCalls`
  (15:05:34.316).

### N4 - Low (debug path only): the debug proxy stalls the tone after a seek or a start at a place

- **What happens.** After a seek past the buffer, a start at a stored place, a resume after an alarm, or about 7 min of
  streaming:
  - the WebView re-requests the same `Range bytes=<n>-` through the music app's debug proxy every 1-2 s, for minutes;
  - the page keeps posting `playing` at a frozen position (the recorder: `playing 415143` for the whole 15 min Sov run);
  - the session's position is extrapolated.
- **Not the fake.** It answers those Ranges correctly (curl: 206, `Content-Range bytes 2505132-2800043/2800044`,
  full length).
- **Likely cause.** `SpotifyWebHost.proxy`:
  - passes `Content-Type`, `Content-Range`, `Accept-Ranges` and the CORS header, but not `Content-Length`;
  - uses OkHttp's `callTimeout(45 s)`, which bounds a whole streamed body.
- **Effect.** Blocks A1 #10's "tone jumps", #13, and #16's track changes in Doze. The real SDK doesn't go through this
  proxy.
- **Suggested.** Pass `Content-Length` and drop `callTimeout` for the proxy (keep the read timeout). Re-check with
  `curl`-like seeks.

### N5 - Medium: an entry flipped from list to context while its list play is alive resumes the old list

- **What happens.** With entry 6 playing (or paused right after a queue add) and the library changed to
  `spotify: "context"` (as the server does after a 403, A1 #24), a cover tap on the (now context) tile:
  - sends `vibb.resume()` to the old list (recorder `EVENT resume`);
  - lets the phone keep adding Spotify's queue (`queue: …052`, `…053`, …);
  - opens neither the player nor the bar (the library shows no bar while the session plays "Fake track 55");
  - starts no `play: context` until the old list has run out.
- **Repro.** A2 #18 as written (60-track list, flip at `queue: …051`).
- **Where.** The service keeps the old queue's renderer; the cover tap resumes the current item for "the same entry".
- **Suggested.** A library change of an entry's Spotify mode (or its key/version) should end that entry's live play
  (bookmark, detach) before the next tap.

### N6 - Medium (design 34 step 11): the music library at font scale 2.0 with the bar clips the tile's line

At 569 dp, font scale 2.0, with the now-playing bar:
- the line under the title ("Musikk · spor 1 av 3") is cut to half its height under the category row;
- the category labels ellipsize ("Mu…", "Po…");
- the carousel dots are hidden;
- the slot doesn't scroll (swipe tried).

1.0 and 1.3 fit; the empty state at 2.0 scrolls correctly. `FitTest` models the slot; the real heights at 2.0
(Nunito, the chips' two-line labels) exceed it. Evidence: `music-vs-mockup.png` (last panel),
`shots/d34-music-full-2.0-swiped.png`.

### N7 - Low (design 34 step 4): words cut between letters at 2.0 where no hyphenation pattern helps

- **Contact names** (Home and the phone book) have no hyphenation: "Mamm/a", "Mormo/r", "Mamm/a" at 2.0, nb and en.
- **Tile labels** hyphenate in nb ("Tele-fonbok", "Innstillin-ger", "Chro-me", "YouT-ube"). In en, English patterns
  don't break these words: "Chro/me", "Melding/er" (app labels are nb), and at 4 columns "Ph/one…", "Gmai", "Kal".
- `hyphenationFrequency="normal"` only helps when the language's patterns know the word. Consider `minWidth`/autosize or
  ellipsizing a single word instead of a mid-word break. Evidence: `home-font-scales.png`, `home-en-4col.png`.

### N8 - Low (design 34 step 8): the lock ignores a font-scale change until recreated; the glance date at 2.0

- After `settings put system font_scale 2.0` (or back to 1.0) the PIN lock's glance and keypad keep the old sizes across
  screen off/on and even an `am crash` restart. After a reboot they are right.
- At 2.0 the glance date "Wednesday, October 14" wraps start-aligned under a centred clock
  (`keypad-2.0-vs-mockup.png`, right panel).

### N9 - Low: "Til 11:30 PM" in Norwegian

- 12 h ends keep the system locale's AM/PM marker (design 34 step 6 L1). With Vibb in nb on an en_US system the rule
  screen says "Til 11:30 PM". Consistent with the decision, but mixed language.

### N10 - Info: the lock's 112 confirm is a light dialog

- `EmergencyCall.confirm` on the PIN lock showed "Ringe 112?" on a white panel. Step 5's `KidAlertDialog` covers the
  phone book's dialogs, not the lock's.

### N11 - Info: Nytt shows "Fjern alle" when there is nothing new

### N12 - Info: the drawer differs from mockup 02

- No rounded list card and no round header buttons: a plain list with larger text.
- The parent Settings' switch off-state shows only a white thumb (no visible track) on night.

### N13 - Info (environment): the emulator was OOM-killed twice with no build running

- qemu `anon-rss` 8.6 GB and 6.8 GB: after Chrome plus the WebView, and after repeated in-guest `adb reboot`s.
- RSS starts at 3.5 GB and grows (`logs/qemu-rss.log`). Restarting the emulator process (not the guest) avoids it.

### N14 - Info (environment): an emulated 112 call left a phantom active call

- Telecom kept `TC@… ACTIVE` across a guest reboot until `adb emu kill`. That held the launcher's language change (no
  locale switch during a call, by design).
- **Careful:** on the 112 sheet "Ring" calls at once (a contact named for 112). In the lock's "Ringe 112?" the RING
  button sits where AVBRYT is expected - two emulated 112 calls happened by mis-taps.

### N15 - Info: a long-press on Home offers the kid "Skjul" and "Gi nytt navn"

The upstream list menu. Not design 34's; noted for the record.

## What is left

- **Part B (the real SDK).** N1, N2 and N3 need real recordings: whether the real SDK posts the `ended` pause, the
  order of `playback_error`, and Chromium's focus during a real call.
- **After N4 is fixed:** A1 #10, #13 and #16 (30 min Doze with track changes).
- **Design 34:**
  - the glance with app notifications;
  - a sheet with a photo;
  - the outgoing call screen (a device);
  - the Wi-Fi password dialog (a secured network);
  - the budget lock;
  - the rule screen in en;
  - 112 with Telecom refusing;
  - the library without the bar;
  - N6 translucent icons;
  - the en wallpaper names.
