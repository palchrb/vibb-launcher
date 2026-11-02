# PLAN: handy — an open, parent-curated kids' phone

Handoff from a design discussion (claude.ai, Oct 2026). Goal: a simple,
locked-down Android phone for my son, built on open pieces instead of a
proprietary kids' ecosystem (Xplora). Not urgent: he is 9 and gets an XploraOne
now; the target is a working phone by lower secondary school (~age 13).

> **The plan's history is in `docs/plan/plan-archive.md`** (moved verbatim on 2026-11-01 to keep this file small): the fork decision, "What handy adds",
> the repos, architecture and hardware, the 2026-10-05 status and next features, the phases, "Approved for the next fix batch" (checked against the code
> 2026-11-01), "Before stable `music-v0.4.0`", "Parked", "Tooling and docs", "Left the queue 2026-11-01" and the "Done (not released yet)" entries older
> than 2026-10-27. This file keeps what is live: the goals, the queue, what was asked for and not settled, what is done and ships in the next release,
> what waits on the user, and the open questions.

## Goals

- Phone does only what the parent allows: calls to/from approved contacts,
  Element X (Matrix) instead of SMS, the Vibb music player (separate repo), a
  few curated apps.
- No Google Family Link, no ad-funded third-party blockers. Everything
  self-hosted and open where possible.
- One parent PWA for everything: phone admin (apps, calls/messages, device
  features, DNS, schedules) and Vibb's music library.
- Rules are enforced locally on the phone; the home server only defines them.

## Fix backlog

The running list of fixes and small follow-ups, kept up to date as items come in and are
done. Process for each batch: design note where needed, QA, implementation, code QA, fix all
findings, merge to `main`, full suite once.

### Approved, not built - the queue (kept current; checked against the code 2026-11-02)

**Release 2 PUBLISHED 2026-11-02**: `server-v0.62.0`, `launcher-v0.38.0-rc.2` and `music-v0.6.0-rc.1`, all on 63cb6bbf (the launcher's build re-run after a proxy.golang.org stream error, then signed). Next tags: `launcher-v0.38.0-rc.3`, `music-v0.6.0-rc.2`; the Done list below ships in the next server release.

One line per approved item that has no code yet. An item leaves this list in the commit that merges it (or the one right
after), never later. Details stay in the entries below.

**Emulator session E6 DONE 2026-10-31 on `jelly36` (main 9064518e; run doc `docs/testing/2026-10-31-e6-run.md`):** nothing blocks the release. §6y calendar links 22 pass / 0 fail (2000 events applied in 3.0 s; a kill mid-apply recovers at the next sync; both EXDATE forms honoured, `EXDATE_COLUMN` stays; DST faithful); §6w 8-9 18 pass (Dagen its own task, E5 H1 fixed; dots, legend, the lock row's dot, auto-pick, "No free colour"); §6 step 12 fold 4/4; B6 4/4 (the essential list names no GMS; no GMS line in an hour - the image has no Google account, so the nag stays a device check); nb heading "Lørdag"; the Android 16 image's Conscrypt still trusts emSign ECC Root CA - G3 (a Conscrypt mainline update drops it, nothing of ours to do); Etar's battery-intent crash reproduced (Etar's own). **`launcher-v0.37.0-rc.8` + `server-v0.60.0` PUBLISHED 2026-11-01** on main 2e2b243b (CI green, 1463 server tests; server release run 41281600476 with the signed tarballs and the image, launcher run 41281600354, a prerelease). **`server-v0.61.0` + `launcher-v0.38.0-rc.1` + `music-v0.5.0-rc.8` PUBLISHED 2026-11-01** on main bc8dfb2b (CI green; release runs 41304221491 server, 41304222053 launcher, 41304222008 music; the launcher went to 0.38.0-rc.1 because rc.9 is out of range - rc.1..8, the 9th code is the release).
  - **E6's Low findings, queued (not started):** **MERGED 2026-11-01** (`small-c` 5dea62b6; server CI 1470/0, jstest 204/0, launcher 1909/0; light code QA 0/1/3/2 fixed; ships in the next server release and launcher RC) (L3 and L5 only; L1 stays) - L1 the lock glance's views log `requestLayout() improperly called` (12 lines; none from Home); L3 without the music key the calendar-link form says nothing - the server makes a new key at start and old links turn `key_lost` only at their next fetch (a notice on the Calendar page); L5 each time-rule redirect stacks another `LockActivity` in the home task (three seen; reuse the instance). L2/L4 are test-doc wording (webcal needs https; a removed link's row; the task count grep; the LAN feed address), fixed in emulator.md with the run notes.

Rounds and waves: `docs/plan/2026-10-25-queue-rounds.md` (2026-10-25). Combined lines are split at their own
separators, one item per bullet, so each sits in its round; the words are unchanged.

- **Cleanup**
- **Launcher**
  - **Round L1 - glance and Home layout** (merged 2026-10-26, `launcher-glance-c`; RC3-8 pinned, six-inch F3/F4 and the lower music tile built; only Q5 is left):
    - **Fingerprint with the screen on, Jelly Star check** (the user, 2026-10-25, emulator: with the screen on the fingerprint unlocks only once SystemUI's pad is up; from screen-off it unlocks at once): expected in the hybrid - the glance occludes the keyguard, and Android listens for fingerprint only while the keyguard is showing and not occluded (design 59); no API lets us dismiss a secure keyguard after our own biometric check. Check on the Jelly Star (side-mounted sensor; some OEMs keep listening while occluded); if it does not work there with the screen on, one line in Kid Settings' help ("press the sensor from a dark screen, or swipe up first"), nothing more. Not a bug.
  - **Round L2 - pause end and enforcement** (`launcher-enforce`; **merged 2026-10-26**, code QA `docs/review/qa-l2-enforce-code.md` 0/0/3/3 + fix round; ships in `launcher-v0.37.0-rc.6` with L3): READ_PHONE_STATE (d) built; **the pause-end kill is not buildable** - since Android 14 `killBackgroundProcesses` only ends the caller's own processes (AOSP 5b7edbf2ba07; no device-owner force-stop; only hiding kills an app, and that drops its alarms/jobs/widgets), so suspended apps stay cached until Android's low-memory killer reclaims them - the user's 2026-10-26 `dumpsys meminfo` (WhatsApp 92 MB, Element X 86 MB resident) is that, not a leak; `launcher/CLAUDE.md` gotcha; the item below stays only if the user wants hide-and-unhide for the apps that are blocked anyway. Per decision 3 the VoIP fixes (a), (b), (c) are dropped ((a): E1 never reproduced the missing call screen in android mode); the App blocker waits for the package dump:
    - Jelly Star "App blocker" hidden/suspended at enrolment (waits for the user's package dump).
  - **Round L4b - the launcher's security Lows, the rest** (`launcher-sec-lows-b`; L4's first branch merged 2026-11-01, see Done): S-L4 (needs the real Play Protect / account-security channel ids from a device capture), S-L5's audio-only pairing filter (needs a server policy switch), S-L9's DoT connection reuse, S-L10's death count after connect + a "Leave tailnet" command (server command), P-L2 + P-L3 in `LocateCommands` (with design 64's zones; P-L3's server half - skip an equal `captured_at` - in one commit with the launcher).
  - **Emulator session E2 - screen sizes** (the coordinator's, after L1 and M1):
    - **Screen-size matrix on emulators** (the user, 2026-10-25: "kjøre tester med emulatorer med veldig forskjellig skjermoppløsning og dimensjoner så vi kan sjekke hvordan løsningen vår skalerer på ulike telefoner"): one pass of every launcher screen (Home grid, phone book, keypad, the glance in own and android mode, the time-rule screen, Kid Settings, the pause screen, the music app's library and player) on at least four AVDs - a narrow 4" (540x1168 @ 320 dpi, 270x584 dp, the Bluefox NX1's class), the Jelly Star (480x854 @ 240, 320x569 dp), a 6.1" (1080x2400 @ 420, 411x914 dp - `six35` exists) and a 6.7" tall (1440x3120 @ 560), each at font 1.0 and 1.3, 3-button and gesture navigation; screenshots under `docs/testing/screens/sizes/`, a findings table like `2026-10-23-six-inch-android15.md`. Launcher (+ music), M; the narrow one first since it is below our 320 dp floor.
  - **Waits on the user / the Jelly Star / phase D** (no round yet):
    - **Design 38 device check, the Jelly Star half** (the emulator half passed 2026-10-25: `svc power reboot` → "is restarting" in the inbox within 2 min, `docs/testing/2026-10-25-r13-run.md`): the power menu's Power off and Restart and a flat battery on the Jelly Star - the user's check.
    - Phase D (own lock removed)
    - **Design 32b, now the screen-on hand-over in android mode** (the user, 2026-10-26, `jellystar2` screen recording of three power-button cycles, read frame by frame: "Er en slags resizing tidvis av bakgrunnen ved power button cycles her; er dette noe vi kan addressere?"): at every screen-on the stock keyguard is on screen for 1-4 frames (40-170 ms) before the glance - once with SystemUI's own clock, once as the bare wallpaper - and in one cycle the glance's STALE page (the old clock, 12:04) showed first for 4 frames; in those stock frames the system wallpaper is at SystemUI's wake-up zoom-out (1.0), while the glance's own opaque ground (design 25's copy) is at the wallpaper's resting scale (`config_wallpaperMaxScale` 1.10) - so the ground steps 10 % when the glance covers them (the same step Home solved 2026-10-25 by keeping its copy). Decision 4's premise ("own mode only") is wrong: the stale frame exists in android mode too, so 32b stays. Fix direction (a design note first, emulator-measured, `screenrecord` + `vidsum.py`): the glance's window must be the first frame at screen-on (why does our window drop out for a frame after it was there with stale content - the keyguard's re-occlude transition? `setTurnScreenOn`/`showWhenLocked` timing?), the page's content blanked at the lock-up so a stale frame can't show (design 32's ground rule), and if a stock frame remains, the glance's ground may start at the wake-up's zoom-out and settle with SystemUI's own zoom so no step is visible. Launcher, S-M; after L2. **Design 71 written 2026-10-27** (e62854b9, `docs/design/71-screen-on-handover.md`; in design QA): the architect re-measured the video and **corrects the reading above** - our copies draw at 1.0 (no code reads `config_wallpaperMaxScale`), the keyguard's frames show our own baked lock wallpaper at Android's resting 1.10, so the 10 % step is the other way round and fixed, not an animation; cycle 3's "stale page" is the lock task's snapshot from the last unlock (no page content), not a stale draw. Recommended: every full-screen wallpaper copy (glance/keypad ground, Home, phone book, kid Settings) drawn at `config_wallpaperMaxScale` (`WallpaperZoom.kt`, a `zoom` on `GroundDrawable`), `setRecentsScreenshotEnabled(false)` on `PinLockActivity` as Home has; our window can't be the first frame without `turnScreenOn` (design 32 refused it), so 1-4 keyguard frames stay but look identical; emulator §6x before/after, done = 0 stale frames and zoom 1.000 ± 0.005 on every lit frame. **Design QA'd (`docs/review/qa-71-design.md` 1/3/4/2) and revised 2026-10-27 (a47d8dc7)**: the measurement goes through the emulator console's recording (`screenrecord` blacks our secure window) with a tracked `scripts/zoom-match.py --selftest`; the 12:04 frame is most likely the emulator's host composer, not our lock (`setRecentsScreenshotEnabled(false)` kept as parity with Home only); `jellystar2` is the desktop AVD, so **the step is BLOCKED on the user's pre-check on the physical Jelly Star**: a slow-motion clip of three power cycles read with `zoom-match.py` - 1.10 = build, 1.00 = close the item as emulator-only. Recommended D1-b (android mode only): the lock wallpaper handed to Android baked pre-shrunk by 1/maxScale (`WallpaperApplier`, one `canvas.scale`, a `lockzoom=` key part) so every screen keeps today's framing; D1-a (every copy at 1.10) the alternative; D2 accept the remaining keyguard frames (design 32 §7.1). Waits for the user's clip and D1 pick.
    - RC3-4 (release-build setup timing)
    - Immich wallpapers
    - design 60 (hardware-bound token)
- **Music app**
- **Server / PWA**
  - **Round S1 - server small items** (`server-small-e`; **merged 2026-10-26**, f20cc7f5, code QA `docs/review/qa-s1-server-small-e-code.md` 0/2/5/4 + fix round; server 0.54.0, tag after design 66 lands or on its own if that waits): `PHONE_SERVER_URL` in `.env` (DB wins; QR + Connect page; `deploy/env.example`, `install.sh`); episode pictures: the r5 finding was a per-host hold (three failures on one dead host ended the whole art pass) - now per host, plus a pass-wide cap of 6 while no host answered; GitHub asset `digest` into `ReleaseAsset.sha256`, a mismatch refused (catalog APKs; tarballs keep minisign); page timing (`static/nav-timing.js`, `?timing=1`, Software Updates › Diagnostics › Page timing row - step 1 only, the prefetch step stays below); settings saves in the security log (`settings_log::log_saves` inside `require_full_auth`: a 303 back to a page = a save, refusals-by-redirect carry `settings_log::Refused`, a handler that wrote its own event gets no second row, 10-min coalescing per parent and page, `NOT_SAVES` list); one red alert while paused (the Attention row stays, the Quick actions card is one plain line; browser pass light/dark done); the dead-code review's server list, D3's server half, D4's route. **Owed to the user:** the iPhone tab-switch numbers through About › Page timing after the tag; WebKit pass of the summary.
    - PWA tab-switch timing step 2: prefetch after the measurement (waits for the iPhone numbers).
  - **Dagen: the kid's date picker shows no day numbers** (the user, 2026-10-27, `jellystar2` screenshot of "Ny avtale": the framework `DatePickerDialog`'s header and the selected day's circle, no other numbers, no weekday row): a theme gap - the dialog's day text is drawn in the background's colour. **Fixed 2026-10-27** (5cb066d0 on `day-view`, merged): the activity follows the system's day/night mode, so in light mode the system's light dialog drew our white day numbers on white; both pickers now use `R.style.VibbPickerDialog` (parent the framework's dark `Theme.Material.Dialog`, every colour named, the day/clock numbers through `res/color/vibb_picker_number.xml`), `PickerThemeTest` pins the constructors, the items and 4.5:1 contrast; emulator.md §6w step 7 (light and dark via `cmd uimode night`). 1878 launcher tests. Ships in rc.8; not yet seen on a screen.
  - **The notification filter kept Play services' cross-device welcome nag with the switch ON** (the user, 2026-10-27: "notification filter har vært PÅ hos meg helt siden starten ... it does not work correctly"; the dumpsys: `com.google.android.gms`, channel `multidevice...WELCOME_CHANNEL_ID`, flags `AUTO_CANCEL`, importance 4, our listener allowed): `nagVerdict` keeps it only as POLICY_UNKNOWN/SWITCHED_OFF/UNMANAGED (the runtime sweeps once the policy is known, so not the first), ESSENTIAL_PACKAGE (the resolved essentials: `essentialNotificationPackages` - the SMS_CB/SMS_EMERGENCY_CB receivers with MATCH_SYSTEM_ONLY, dialers, Telecom, emergency dialer, clock, IMEs; **GMS lands there if it registers a cell-broadcast receiver on this image**), or ALLOWED (the effective allowlist + messaging apps + a rule's usable apps). The user's checks (2026-10-27): both cell-broadcast actions resolve only `com.google.android.cellbroadcastreceiver` (GMS is NOT an essential by that route); logcat `Rule active: 9 essential packages` while the nag was posted (~05:37) and at the listener's reconnect 06:31 (a sweep); a shell notification (`cmd notification post`, package `com.android.shell`, clearable) was removed at once - **the rule works in general; GMS's is kept or its cancel refused for a reason we don't log**. The server never allowlists Play core (`play.rs` `PLAY_CORE`), `facts()` reads nothing that throws for it. The full logcat holds no `cancel com.google.android.gms` line, and the first cancel of any package/channel is always logged (`nagLogDue(1)`), so `act()` never ran: **the verdict was Keep** - the remaining reasons are ALLOWED (the allowlist ∪ the contacts' messaging apps ∪ a lock's usable apps) or ESSENTIAL_PACKAGE (the 9 resolved essentials), neither logged. **Fix MERGED 2026-10-27 (`nf-keep-log` 9e04fc84 + the fix round a40f318f via `launcher-small-b`: `kept <pkg> / <channel>: <REASON> [via allowlist|messaging|lock-usable] (N so far)`, FACTS_FAILED/GROUP_CHILD_KEPT for the silent returns, `platform kept` after a cancel that did not take, emulator.md B6; 1882 tests) - code QA `docs/review/qa-nf-keep-log-code.md` 0/0/4/3 Merge; fix round done: the unreadable-children path logs `ACTIVE_UNREADABLE`, `refresh` logs the resolved essentials BY NAME, every filter line under tag `NotificationRule`, skipped post-cancel checks counted; ships in rc.8 - then the user's logcat at the next GMS nag decides (B6)** (launcher, XS-S; the user, 2026-10-27: "kommer vi til bunns i hvorfor den fortsetter å vise notifications er det jeg lurer på"): (1) log a keep's reason for a non-essential package at the cancel lines' rate (package, channel, `NagKeep`), (2) after a cancel check once (1 s) whether the key is still active and log "the platform kept it" (Android 15/16 refuses some listener cancels), (3) emulator.md: the shell-notification check (`cmd notification post -S bigtext -t T tag1 hei` vanishes within 1 s with the filter on, stays with it off).** Then re-read the user's logcat at the next GMS nag.
  - **A Google nag in the shade on `jellystar2`** (the user, 2026-10-27, screenshot: "Tjenester på flere enheter · Nye funksjoner på flere enheter" with a Settings link, plus Android's own "Oppdatert/Slettet/Installert av administratoren" group): the admin-install group is `android`'s and stays by design (launcher/CLAUDE.md "Notification rule"); the cross-device one is GMS - whether the rule should have cancelled it depends on the server switch `notification_auto_cancel` (default OFF until B5) and the channel. Owed from the user: `adb shell dumpsys notification --noredact` (or without the flag) while it is still in the shade, so we see the package, channel and flags (memory: google-nag-capture). No work until then.
  - **Spotify playback on `jellystar2`: starts, the progress never moves, then "Trenger internett"** (the user, 2026-10-27, screenshot: "Cult Logic - Miike Snow", the time row "Kobler til…", the pause button shown): under investigation - the shape matches the web player's two watchdogs in `music/.../spotify/SpotifyRenderer.kt` (`armFirstState`: no state of ours within 10 s of the play call; `armGap`: no state for 30 s while playing; both log before failing with UNREACHABLE). Asked the user for `adb logcat -s VibbSpotify VibbMusicDebugFeed MusicBridge BridgeSync` across a repeat, whether sound comes out, and whether the track played before. Hypotheses: the page's state posts don't reach the bridge, or they carry a relinked uri the tracker doesn't count as ours. **Not captured 2026-10-27**: the Spotify log is debug-only (`spotifyLog` under `BuildConfig.DEBUG`), so the release app on `jellystar2` gave no `VibbSpotify` line; the user started another playlist and it played again (a healthy session in the general logcat: positions advancing, Widevine key renewed). **Captured 2026-10-27 19:59 (read 2026-10-31)**: play at 19:59:32 (BUFFERING at 15117 ms, a resume mid-track after the app had been in the background), the page opened a new Widevine session, got its key, created the audio decoder and took audio focus at 19:59:34 - so the SDK WAS playing - and at 19:59:43, 10 s after the play call, the app went PAUSED at the same position: `armFirstState` ("no state of ours after the play call"). The page's state posts don't reach the renderer (or aren't judged ours) after a background spell - suspect the WebView's JS timers/events paused or throttled while the app was invisible (`SpotifyWebHost`: `onPause`/`pauseTimers`? the page's `postMessage` path), a relinked uri second. The diagnostic log (below) decides; the fix likely `resumeTimers` + a re-read of the SDK state on a start. Open as intermittent: the next time, before touching anything, `adb logcat | grep -i -E "vibb.music|spotify|scdn|chromium|cr_|widevine|MediaDrm"` plus the Web filter's Blocked Activity (the host list `spotify_hosts.json` is still provisional - a refused CDN host would give exactly this shape), or the debug music app on the emulator. **Approved 2026-10-27** (the user: "Ja legg gjerne til diagnoselogg i 15 min for spotify takk"): a parent-side "Spotify diagnostic log for 15 minutes" button under Device › Advanced.
  - **Spotify audiobooks shared as shows end in "Nothing in it can be played here"** (the user, 2026-10-27, iPhone screenshot: "Vitser: prøv å ikke le 3", `spotify:show:5VDiAIsKP2QCJKDNe0kDI0`, and `show/5Mf1oJgaKevODXPdivLdqB`; "spotify har kommet med lydbøker i Norge! Men den funker ikke for oss?"): Spotify now shares audiobooks as show links, so design 26c's refusal of `audiobook`/`chapter` URIs at add time doesn't catch them; `GET /shows/{id}/episodes` returns every chapter with `is_playable: false` (third-party clients can't play audiobooks; `restrictions.reason` says why), `spotify::episode` leaves them all out, and the listing fails with the generic `no_items`. Fix (server, XS-S): when a show's page has items and every one is unplayable, keep the first `restrictions.reason` and, for `payment_required`/an audiobook-shaped show, fail the listing with its own code worded "This is a Spotify audiobook - Spotify lets only its own apps play them." (en/nb through the catalogs; `music.rs:685`'s `no_items` stays for a genuinely empty show); the add form may say the same at once when `GET /shows/{id}` marks it (check the show object for an audiobook marker first - `media_type`/`is_externally_hosted`/`type`). The user runs the decisive check meanwhile: whether open.spotify.com plays the audiobook in a browser with the same account (if the web player can, the SDK path is worth a spike; if it says "listen in the app", it is closed). Queued after the Spotify diagnostic log. **The user, 2026-11-01:** "lydbøker fra spotify som jo har show lenker, spiller helt problemfritt på go-librespot; så lurer på om vi kanskje rejecter lydbøker unødvendig". So the account has the rights, and `is_playable: false` may be the Web API's view for our client id, not the player's: librespot speaks Spotify's own protocol, the Web Playback SDK is the web player's engine (open.spotify.com) - not the same path, but a strong hint. **Changed plan: a spike before any refusal sentence** (**MERGED 2026-11-01** (`spotify-diag` 66c57573: 09d3cdcc, 49345044, e0ec88a5 + the QA fix round; server CI 1467/0, launcher 1910/0, music 1236/0; migration 0108; light code QA 0/1/1/3, fixed; ships in the next launcher/music RC and server release): the server switch `SPOTIFY_AUDIOBOOK_SPIKE`, default off, and the specific sentence when every chapter is unplayable) - keep a show's unplayable chapters when the reason is audiobook-shaped (behind a flag), hand one chapter uri to the SDK on the user's phone and see whether it plays; if it does, list them as a show's episodes (progress per chapter as today); if the SDK refuses, the sentence above. Needs the user's real account (fake-spotify can't answer it).
  - **Assess the eMudhra CA key compromise (CVE-2026-111943, 2026-10-17) against our trust chain** (the user, 2026-10-31: "vi bør vurdere om vi er påvirket av den emudhra sertifikat sikkerhetshullet som nettopp har kommet ut"): eight emSign intermediates' private keys were recoverable from the public certificates; browsers blocked them 10-17..10-20, Mozilla removed the root, distros updated `ca-certificates`; no malicious use confirmed. Verified 2026-10-31 where our trust comes from: the server (reqwest + rustls, `rustls-no-provider`) trusts the OS store of `distroless/static-debian12` / the VM - nothing compiled in; the phones trust Android's store (Conscrypt mainline updates); the launcher does NOT pin the server's certificate (the phone reaches it over Tailscale or an https host); parents' browsers are already blocked. Exposure = a forged leaf + an on-path attacker on a path that still trusts the emSign roots: on the server side the feeds and the Spotify/Storytel API calls (tokens), not the update path (minisign) nor APKs (signer pins); on the phone side the server connection only outside the tailnet. **Checked 2026-10-31: the pinned distroless digest (still the registry's current `nonroot`) carries BOTH roots Debian removed** (emSign ECC Root CA - G3, AAA Certificate Services; the bundle also has emSign G1/C1/C3, which Debian kept). **Fix MERGED 2026-10-31** (`ca-distrust` 43551aba; the dispatched CI run 41240818114 green on amd64 and arm64: "ca bundle: 150 in the base, 2 removed, 148 kept", "TLS ok: api.github.com answered 200 OK"): the Dockerfile's busybox `certs` stage filters the bundle by DER SHA-256 against `server/deploy/distrusted-roots.txt`, fails the build if a listed root survives or fewer than 100 remain, the final image copies the result over `/etc/ssl/certs/ca-certificates.crt`; the image smoke test's `selftest-tls` proves it works; DEPLOY.md + the hardening note say where the trust comes from. Ships in server 0.60.0 (the user's VM pulls the image). Still to do (S): (1) check the server image's `ca-certificates` date (rebuild on a base that dropped the roots; the user's VM too) and whether the emSign roots are still in the phones' stores (`Settings › Security › Trusted credentials`, or `adb shell ls /apex/com.android.conscrypt/cacerts | grep -i emsign` on the emulator); (2) decide whether the launcher should pin the server's SPKI from the provisioning QR (works with Let's Encrypt only with `--reuse-key`; a design question, not a quick fix); (3) one sentence in `server/DEPLOY.md` on CA-store hygiene. Sources: Help Net Security 2026-10-21, Cloudflare's write-up, Mozilla bug 2083449. **State 2026-11-01:** the server image fix shipped in server 0.60.0 (`ca-distrust`); E6 checked the phones - the Android 16 image's Conscrypt still carries emSign ECC Root CA - G3 (Google's Conscrypt mainline update drops it, nothing of ours); left: the decision whether the launcher pins the server's key from the QR (a design question, Let's Encrypt's rotation).
  - **Calendars without ICSx5/DAVx5 - "paste a link in the PWA and that's it" (design 72)** (the user, 2026-10-27: "er det DAVx5 og ICSx5 gjør vanskelig nok til at det rettferdiggjør to ekstra installerte apper?? Hadde vært mye lettere om det bare var å lime inn lenker i PWA"): proposal, not approved yet - the server fetches ICS feeds (it already fetches RSS and the F-Droid index with the LAN-pinning rules) and CalDAV calendars (read-only first: PROPFIND/REPORT with an app-specific password kept on the server, never on the kid's phone), hands the phone the VEVENTs of a bounded window with RRULE/EXDATE/timezone intact, and Vibb One writes them into a local calendar through the provider (which expands recurrences) - no sync app, no account, no per-app grants; ICSx5/DAVx5 stay possible for those who have them. Steps: (1) ICS links (the school feed case) S-M; (2) CalDAV read-only M; (3) CalDAV write-back of the kid's events later/maybe. **Approved 2026-10-27** (the user: "Og kjør! Ics er pri 1, dav pri 2."): **design 72 written, QA'd (`docs/review/qa-72-design.md` 2/5/12/8) and revised 2026-10-27** (20f7cab6, 313 lines: unfold on the bytes then decode, an orphaned source deleted by trigger, an empty feed keeps the last good set until the 3rd empty fetch or 24 h, provider-gone vs row-refused, the name optional, overrides in the series' zone, a CalDAV 401/403 stops the account; §13 = the implementer's brief items). **Step 1 (ICS) BUILT 2026-10-27** on `calendar-feeds-1` (f6b2fbf3 contract + server, 3537f945 launcher, b1dea016 docs; server 1443 / launcher 1897 tests; fixtures `server/testdata/calendar_feeds/`, `src/windows_zones.txt` CLDR 49, emulator.md §6y) - main (slice 1) merged in 5ec48921 + counted words 2a1cd898 (274 keys per catalog, server 1450 / launcher 1897); code QA `docs/review/qa-calendar-feeds-1-code.md` 1/1/8/8 "mergeable after a short fix round" (H1: `ics::parse` panics on an absurd DURATION/TRIGGER and the one fetch task dies with it; M1: a sibling phone attaches to a `key_lost` row); fix round done 3f7db5d4 (H1: checked sums, a 400-day cap, a panicking parse stored as `unreadable`, each fetch its own task; M1: re-sealed on re-add; L1-L8 as the review asked; server 1456 / launcher 1907); **MERGED to main 2026-10-27**, E6 §6y 22/0, shipped in server 0.60.0 / rc.8; **step 2 CalDAV read-only MERGED 2026-11-01** (see "Done since the last release"); **left: step 3 write-back (**shape answered 2026-11-01**, the user: "man skal kunne legge inn en caldav kalender og velge at det er kalenderen barnet selv skal kunne legge inn dagen i dag hendelser i": the parent marks one CalDAV calendar as the kid's own; the events the kid makes in Dagen today (its LOCAL calendar) are written there by the server - PUT of a VEVENT with the app-specific password the server keeps; design after step 2)**; the nb words sent to the user to read; (migration 0105; 0104 is slice 1's): one `calendar_sources` row per URL (uid = SHA-256 of the URL, so a re-added link keeps its rules), per-phone links, the fetch every 60 min + "Fetch now" through the RSS client's rules (5 MB cap, backoff, last good set kept), own content-line parser `ics.rs` (no crate, no RRULE expansion - the provider expands), Windows zone names via a vendored CLDR table, policy `calendar.feeds: [{id, name, rev}]` + `GET /api/devices/calendar/feeds/{uid}` with ETag (contract `calendar_feeds.json` both dirs), one LOCAL read-only calendar per link on the phone (`FeedPlan` diff by uid + series rev, rev written last), PWA "Kalenderlenker" with status/Fetch now/Remove, capability `calendar_feeds_v1`; step 2 CalDAV read-only (ctag first, password only to the hosts confirmed at Test); step 3 write-back shape only. Owner decisions (defaults): per phone with a shared fetch, 60 min, 60 days back / 400 ahead, the link sealed, plain http to a public host refused. ICS first, CalDAV second; server-side parsing (the user's reflection on sharing more with the server weighed - the server already sees the rest, the password stays off the kid's phone).
  - **CalDAV write-back - the kid's own Dagen events to the parent-marked CalDAV calendar (design 72 step 3)** (shape answered 2026-11-01, see the entry above): **IN DESIGN 2026-11-02** (`design-72b` c3a20af8: note 72b written - the whole set posted, the calendar wins a clash, ETag-conditioned PUTs, migration 0117; design QA 2/8/13/8 "revise first" (H1 adopt-on-412 overwrites, H2 no hand-back ack), revised 89aca7a6 + ee3f444b after the revision check (0/2/5/4, both Mediums folded in: `ended` uids never deleted, the hand-back ack by version id); **design MERGED to main 2026-11-02** (`docs/design/72b-caldav-write-back.md`, review `docs/review/qa-72b-design.md`); server L + launcher M, one branch `caldav-write`, migration 0117: **IN BUILD 2026-11-02**; **the user's answers 2026-11-02**: (1) two-way, the calendar wins a clash ("det må jo kunne synkroniseres!"), (2) no past events at the pick, every future one however far ahead (no forward horizon), (3) what was written stays when writing stops, (4) titles as the kid wrote them, (5) the choice in Dagen's "Barnets avtaler" select - to the note in the post-QA revision; its design note was due after step 2, merged 2026-11-01, and waited while newer items were built); next after the QA and the answers, design QA, then server + launcher (M). Own line since 2026-11-02 so it can't hide inside design 72's entry.
  - **Round S-i18n - the PWA in Norwegian and English** (**slice 3a Devices MERGED 2026-11-01** (see Done); **3b Apps MERGED 2026-11-01** (see Done); **3c Locate MERGED 2026-11-02** (see Done); left: 3d Music, 3e Settings, then slices 4-5**; device_calendar*/account join MOVED in 3e; `pwa-i18n`, server; the user, 2026-10-26: "Husk at jeg ønsker i18n på ting her, så greit å tenke på i all ny kode. Ønsker det på hele oppsettet vårt" - was "scope not settled" since 2026-10-24): a translation layer (`server/src/i18n.rs`: `Lang` en/nb, one catalog per language, `t(lang, key, params)`), the parent's language on the account (design 62's `admin_users`), `Accept-Language` as the default before a choice, every template's words and every event/push/security-log text as a key plus parameters (design 64's zone events already are; the older prose events migrate), Norwegian first. Design 69 (Dagen) introduces the layer for its own pages; this round moves the rest. **Standing rule from 2026-10-26: no new user-facing string is hardcoded - launcher strings in `values` + `values-nb`, server words through the i18n layer.** L, in five slices (2026-10-26): (1) the layer + the parent's language (an `admin_users` column, a row under Account; `Accept-Language` before a choice) - design 69 introduces the layer; (2) events, pushes and security-log labels stored as key + params and worded in the reader's language (the older prose rows stay prose); (3) the templates tab by tab (Devices, Apps, Locate, Music, Settings), each slice adding a scan test that refuses literal words outside `t(...)` in the moved templates; (4) the words Rust builds - summaries, relative times, dates and clock formats per language, plurals; (5) the seam with the phone (the phone names rules itself - the server stops sending English names where the phone has its own). After L7 - the user, 2026-10-26: "la oss kjøre dagen i dag komplett først" (done 2026-10-27: Dagen built, QA'd, merged, emulator-checked, shipped in rc.7 / 0.59.0). **Design 70 `docs/design/70-pwa-i18n.md` written, QA'd (`docs/review/qa-70-design.md` 3/6/8/5) and revised 2026-10-27** (60f2aa17: `/sw.js` gets no words, `phone_off` params `reason`/`battery`/`late` with older rows = a non-empty title, the phone-line budget keeps `device {id}` in `detail`, the PWA names the active rule by `active_rule_id`, slice 1 gives every template struct `lang` + `Words`, `i18n::error`, the language stored once on the first signed-in request; decisions D1-D5 stand as defaults until the user says otherwise). **Slice 1 MERGED 2026-10-27** (07099709 built, code QA `docs/review/qa-s-i18n-1-code.md` 0/0/5/7, the fix round b12034ef: unmoved pages keep `lang="en"`, an unreadable stored language falls back to the header in memory, `t` warns once per missing key, the nb words the user chose; merged 8141b65b, 1426 server tests; ships with the next server tag). **Slice 2 MERGED 2026-10-31** (`pwa-i18n-2` 02a73f6b; ships in server 0.60.0) on `pwa-i18n-2` (cbee7056, four commits; 1458 server tests, 193 jstest; 671 keys per catalog, 396 added; migration 0107; the nb list sent to the user as a file) - main merged ea3b9b7f (1462 tests, 684 keys per catalog); code QA `docs/review/qa-s-i18n-2-code.md` 0/1/5/7 "sound - merge after one fix" (M1: the security log shows a row's words OR its facts, never both - the route after a CSRF refusal, the phone of a per-phone Screen Time save, lost); fix round done 67f51288 (M1: `Detail::shown` = words · facts on the row's detail line; L1-L5; nb: "(fra {peer})", "Sikkermodus eller en avbrutt start", "Egne regler på." / "Ramme {budget}."); branch CI run 41249661563 red (25 tests: the id facts `device {id}`/`entry {id}` shown raw after the words) → fixed 02a73f6b: a bare id fact is hidden beside words that name the phone or entry, and the phone's own lines that don't name it lead with the reader's phone word ("phone 1: …", nb "telefon 1: …"; keys `security.detail.phone`/`on_phone`, 686 per catalog); run 41250961086 green, 1463 passed. English the later slices must take (from the QA): `parent_events::battery_line` and `parent_pin::where_`/`lockout_text` and `lifts::minutes_text` → slice 4; push.rs "The push service refused it", admin.rs "Time unknown"/"a while", `Kind::switch` labels → 3e; the music pages' `%b` dates and `settings_hub::day_time_label` → 3d/3e (§4: the times in both languages, the 8 event kinds as key + params, pushes in the subscriber's language, the security log's `params`; migration 0107 - 0105 ICS, 0106 colours; the session banner "Wrong codes before this sign-in" belongs to 3a). Was: branch `pwa-i18n-1` (the catalog rules, `t_count`, the key-itself fallback, `Words` on ~65 structs, migration 0104 `admin_users.lang`, the extractor order `?lang=` → account → Accept-Language → En, the Account row + `POST /account/language`, `account.html` moved).
  - **F-Droid Q33, the ABI filter** (`fdroid-abi`, server, S): the pick honours `manifest.nativecode` (arm64-v8a first, armeabi-v7a, x86_64 for the emulator) - waits for its first app that ships per-ABI APKs (S4 merged 2026-11-01 without it).
  - **During a time rule: Recents after the PIN, one allowed app (e.g. Kalender) opened straight after the PIN, its reminders on the real lock screen, no Recents** (the user, 2026-11-02: "når rules er på, og pin er skrevet inn ... Recent mulig å hente fram ... kan man komme seg videre til andre programmer der? ... Dagen/Kalender appen vår kan få fungere selv om skole modus er på ... om det kun er ett program som er tillatt, at man taster pin og så åpner man rett inn i det programmet ... Helst ingen recent apps"): asked, not approved yet. **Read from the code 2026-11-02:** under a rule (kiosk on, the usual case) Recents shows only the lock task's tasks - Kalender's own `.day` card (it bounces to the time-rule screen) and the rule's exempt apps; blocked apps are suspended (`setPackagesSuspended`), Settings too; nothing reaches a blocked app. The PIN-pad path is Android's own gate (the square key over the keyguard asks for the PIN, then opens Recents). Kalender can't be allowed today: the rule's app list is the installed-apps report without our own package, Kalender's redirects check only `lockReason`, `glanceUsableUnderLock` hard-codes it false, and the time-rule screen has no Kalender row. **Proposal sent:** (a) a per-rule/bedtime `calendar_allowed` switch ("Kalender" at the top of Exceptions; server S-M + launcher S-M), (b) when exactly one thing is usable, the unlock lands straight in it (launcher M), (c) the reminder ping under a rule - a question, (d) no Recents during a rule - unreliable (P59-1, 16d) and removes stock gestures; recommended no. Waits for the user's answers.
  - **Scope not settled / waits on the user** (no round yet):
  - **Android mode: the glance's own lock task vs a helper app (rc.2 run R2)** (moved up 2026-11-01 from the fix batch, where it sat with no queue line): `lockTaskEntry(.. androidLocked)` still returns START_SELF; design 59b left R2 "not changed - a decision for the user", 59d calls it "its own small round". **APPROVED 2026-11-02** (the user: "Ja til ditt r2 forslag"): with the kiosk off and Android locked, the glance takes no lock task of its own (Android's keyguard already keeps other starts away; the status bar stays disabled through `setStatusBarDisabled`), so a helper unlocked into (DeskClock from an alarm row) stays (`android-r2`, launcher, S): **BUILT 2026-11-02, in code QA** (`android-r2` f07c46cf; launcher 1945/0; code QA 0/2/3/2 - M1 the leftover own lock task on WAIT→RELOCK paths, L1 the heal without lock task - fix round done 4c3f46ca (STOP_OWN, the screen-off status-bar heal, the kiosk-on recheck, the Home heal gated; launcher 1947/0); the user OK'd what may show over the lock 2026-11-02 ("ja det mener jeg bør være greit ... hva som vises over android lock screen er allerede gjennomtenkt"); waits for the emulator check (after the CalDAV build and the passkeys fix round - no Gradle or cargo with an emulator up); side effects told to the user: with the kiosk off a Home gesture on the locked face now meets Android's PIN pad instead of nothing, and an allowed app's show-over-lock screen can come over the glance - neither passes the PIN); emulator check owed after the QA; emulator check of a helper after unlock, and that nothing gets past Android's PIN (an allowed app's show-over-lock screen may come forward over the glance - QA M2, told to the user 2026-11-02).
  - **PWA small things left from the old list** **MERGED 2026-11-01** (`small-c` 5dea62b6; server CI 1470/0, jstest 204/0, launcher 1909/0; light code QA 0/1/3/2 fixed; ships in the next server release and launcher RC) (item (3) only) - (moved up 2026-11-01): (2) "add an existing family contact" to another phone - not built; (3) a Cancel in the `<details>` confirms (Remove Contact… in `device_calls.html` has none). Server, XS each.
  - **The caller's name on the ring card via `EXTRA_CALL_PERSON`** (long list since 2026-10-2x, "memory only"; moved up 2026-11-01): not built; launcher, S; no round.
  - **x86_64 emulator build of the release launcher** (2026-10-23, from the fix batch): the release APK's tsnet is arm64 only; a second asset or a debug-tsnet path for x86 emulators - not built; CI, S; low.
    - **Discovery in the music app: curated shelves by age** (the user, 2026-10-26, "mer en sånn større ting som vi kan tenke på på sikt": "hvordan kan vi legge inn et element av discovery/browsing i musikk appen; må jo på en måte være en slags kurert playlist/ebooks etc i riktig alder"). Today the kid sees only what the parent ticked per phone (`device_music_entries`); the server already reads Storytel's `kidsBook` flag (`storytel.rs:659`, data, never a filter) and the NRK and RSS sources. A sketch, parent-first: (A) a "Finn" page in the PWA's Music tab that browses NRK Super / Storytel's children's shelves / a podcast directory (Podcast Index or iTunes search, "Kids & Family") with an age filter where the source has one, one tap = into the library and ticked for the phone (server only, S-M, every search from the server, never the phone); (B) collections as entries: a source's shelf ("NRK Super hørespill", "Storytel 6-9 år") becomes one browsable node the parent ticks and the sweep keeps in sync like a feed, so the kid browses inside a parent-chosen pool (both sides, M, a `kind` on `music_entries` + the app's list); (C) a per-phone age and an "Oppdag" shelf the server fills from the allowed sources' age metadata, with a hide per item (L; needs metadata quality - Storytel has `kidsBook`/age groups, NRK Super is all-kids, podcasts have no ages). Order if taken up: A, then B; C only with real age data. Privacy: no browsing or search leaves the phone; the server queries the sources. Spotify stays parked. No round, no note yet.
    - Home Wi-Fi as a trigger
    - Wi-Fi in Phone Setup
    - notifications phase 2 (ntfy/Matrix)
    - Zone series numbers after the user's Jelly Star day
    - developer-verification registration (nothing in the repo)

  - **Passkeys for parents, passkey-only accounts, a Face ID lock on opening** (the user, 2026-11-01: "La oss legge inn at man kan selv velge å legge inn en passkey inne på accounts og parents? Der kan man vel også det velge å kun bruke passkey for kontoen din og ikke tillate passord innlogging? Eller er det farlig? Og også da velge om man skal velge og kreve biometric og passkey etter x min for pwa?"; after the coordinator's assessment of passkeys / Face ID lock / OIDC - OIDC not asked for): add passkeys under Settings › Account (and see them per parent), optional "passkey only" (no password + TOTP sign-in) with a safe recovery, optional "lock with Face ID after N min" for the PWA. **APPROVED 2026-11-01; APPROVED 2026-11-02** (all eight questions answered, design 74 §8; mockup v2 with the whole Account page and the passkey-only sign-in approved: "Mockup ser bra ut, du kan kjøre på" - https://claude.ai/artifact/MimkFPaRd2fq7cW4ZyQH2g). **Step 1 BUILT 2026-11-02, security code QA done** (verifier 0/2/3/7: a shared RP ID let a sibling subdomain relay with a faked Host, the challenge store could be filled; web flows 0/3/7/7: Add's passkey re-auth broken, the options GET counted cross-site, every /login load cost a ban point) - **fix round done 8ce305da** (cargo 1568/0, jstest 224/0), **revision check running** ( `passkeys-1` 5cccaeaa; cargo 1555/0, jstest 220/0; migration 0116; 64 keys per catalog). Steps 2 (Kun passkey + the passkey-only sign-in page) and 3 (the lock) queued after it: design note 74 written (`passkeys-design` 89de90e7: own verifier on `ring`, RP ID = the request host or `PASSKEY_RP_ID`, Kun passkey needs 1 passkey with a warning that two are recommended (the user, 2026-11-02), recovery by another parent's Reset or `reset-sign-in`, the lock Av/1/5/15/60 min per device with no password fallback, steps 1 passkeys M / 2 Kun passkey S-M / 3 the lock M), design QA 3/5/9, all taken in af50a317 (origin = this request's origin, resets delete passkeys from step 1, only user activity counts for the lock, a server-side challenge store, one readiness predicate, the lock check in `admin_or_why`); note 74 merged to main; eight questions to the user (the six (lock times incl. "med en gang", per device vs per account, Kun passkey with 1 synced passkey, the nb word, which hostnames, logging unlocks, plus: the child knowing the parent's phone code, other services on a shared `PASSKEY_RP_ID` domain); then a mockup; server only (WebAuthn), M-L.

### Asked for, scope not settled yet

- **PWA i18n** (the user, 2026-10-24, on design 64's push wording: "burde gå for i18n så man enkelt kan endre og legge
  til flere på sikt ... mer som en generell long list ting"): the PWA is English-only; a translation layer (strings in
  one table per language, the parent's language on the account, Norwegian first) would also need every event text to
  be a key plus parameters - design 64 step 1 already stores zone events that way; the older events are prose. Long
  list; decide the scope when a second language is wanted.
- **Design 50: the player and the library, the user's picks of 2026-10-20** ("Jeg liker 1, 4 ... Ang 5 ... husker
  hvilken tile som ble spilt ved siste avslutning ... Ang 6 ... grey out de tilesene man vet man ikke får spilt av"):
  (1) **skip buttons** for everything that resumes (books, podcasts): the control row's two side slots become "15 s
  tilbake" and "30 s fram" (music gets shuffle/repeat there, design 49); the same two on the lock's music tile for
  such entries (the launcher; one glyph pair, Material Symbols replay_10/forward_30 or our own 15/30). Previous/Next
  stay vibb's (`previousAction`: an item over 30 min never restarts, it steps to the previous part; positions per
  item are never reset by a step - the user's worry - the entry's current item moves, each part keeps its place). (2)
  **the sleep timer's extra choice "Til slutten"**: finishes the current track/episode/book part, then stops (the
  Sov menu's fourth row; shown as the remaining time of the item). (3) **the carousel opens on the last played
  tile**: at start `carouselIndex` is the stored now-playing entry's index (`NowPlayingRow`; 0 today) - no new state.
  (4) **a tile the phone can't play right now is dimmed**: an entry with no playable item under the current network
  (a Spotify entry when `spotifyPlayability` refuses - cell without the streaming switch, offline; an NRK/RSS entry
  with no copy and no streaming allowed; a Storytel book with no copy) draws its cover at ~60 % with its quiet line
  ("Trenger Wi-Fi", "Ikke lastet ned"); a tap still answers as today (the line, never a silent block), nothing is
  hidden. (5) **Favoritter**: a heart per track/episode in the list and the player, a "Favoritter" category tile
  first after Alle (the tile row scrolls past four, so width is no issue); phone-only (Room), never synced. Not taken:
  the "Fortsett" strip (3 covers it), the offline check badge (4 covers it the user's way). The rewind-after-a-long-pause
  idea (5-10 s back after > a few minutes paused) was declined (the user, 2026-10-20: "Nei legg det bort"). Design note first;
  the music round with 46 and 49.
- **Design 49: shuffle and repeat in the player's control row** (the user, 2026-10-20: "en spesifikk shuffle knapp og
  repeat knapp ved siden av next/prev ... toggle for shuffle av og på - og toggle for ikke repeat, repeat all eller
  repeat 1 sang"). Today: a shuffle button exists in the row's side slot but only for `canShuffle` = own files that
  don't resume (`logic/Queue.kt`); no repeat anywhere. Proposal: for every `Kind.MUSIC` entry (own music, Spotify
  playlists/albums; never podcasts or books, where order is the point) the row's two side slots are a shuffle toggle
  and a three-state repeat (off → all → one, the glyph with a "1" badge), both remembered per entry on the phone like
  design 39's flip (Room, never synced). The phone's player: ExoPlayer's shuffle and `REPEAT_MODE_ALL/ONE`; a Sonos
  room: the controller's queue bookkeeping (vibb's order rules); Spotify: in list mode our window is shuffled on the
  phone (the tracker's "ours" widens to any uri of our window) and repeat-all re-sends the window at its end,
  repeat-one re-sends the track; in context mode Spotify's own shuffle/repeat API is set to the kid's choice (today
  both are forced off before the first play; the tracker already takes every track of the context as ours), and the
  queue's "next 20" view (design 46) follows. Resume entries keep shuffle hidden as today. The behaviour reference is
  vibb's player for the phone; Spotify's own app for the look of the two toggles. Design note first; the music round
  with 46.
- **Design 51: "show the text" per app on the lock's glance page** (the user, 2026-10-20: "definere per enhet som
  får poste push meldinger på lock screen hvorvidt full tekst skal vises eller ikke ... for en klokke app er det
  lite å tape på det, samtidig som fulle meldinger vil man kanskje ikke vise på låst skjerm"). Today `lock_glance`
  is `{apps: [pkg…], show_text: bool}` - one switch for all (design 25 §2.1, `GlancePolicy`). Decided: per app -
  the policy becomes `{apps: [{package, show_text}…]}`, no top-level `show_text` (the launcher keeps reading the old
  shape: a plain string = that package, sender only - an old server's global switch is ignored; an older launcher
  given the new shape ignores objects it can't read and shows missed calls only, design 25's fail-safe); migration 0083
  adds `device_lock_glance_apps.show_text`, backfilled from the old switch, and a new tick or a re-tick starts at the
  sender only; the PWA's Lock page gets a text switch per ticked app in place of the global one (`device_lock.html`
  #notifications: each app one cell with its tick form and, ticked, its text form `POST
  /devices/{id}/lock-screen/apps/text`, both autosave and back to the app's row `#lock-screen-app-<pkg>` - the no-jump
  rule; 404 and nothing written for an app that isn't ticked); `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest` move together, both directories in one commit. Design 42's clock tile is untouched (its
  own reader, the time only). Server round 6 with design 48, the launcher part in the same commit.
  **Status**: implemented on `r7-b` (2026-10-20; code QA `docs/review/qa-r7-b-code.md`, its fixes in). Release note
  for both the `server-v*` and the `launcher-v*` release: an older launcher shows missed calls only on its lock until
  it updates; on an older server a new launcher shows the sender only for every ticked app. Owed: the emulator check
  of design 51 §3 (two ticked apps, the text on for one, a message from each - the sender for both, the text for one;
  neither switch jumps the page).
- **Design 48: one Activity log on the phone's summary** (the user, 2026-10-20, a screenshot of the summary after
  round 5: "Hvorfor recent og today, og så vises ikke den today saken på recent heller? ... samle loggen på bunnen";
  "høres greit ut" to this): today the summary has Recent (the override commands the parent asked for, design 44) and
  Today (the phone's events of the day, design 35) as two groups with different sources. Decided: one group at the
  bottom, **Activity**, newest first, the last 7 days, 10 rows, mixing a command row as Recent draws it (label, the
  answer, time, status, Cancel while it waits) and an event row as the inbox draws it (title, sub-line, time, the
  chevron to its target), then a row "Everything for this phone ›" to `/notifications?device={id}`. Today and Recent
  go as groups; Quick actions stay on top. The Today card's answers that a command row repeats are not shown twice
  (round 5's rule, the reverse way round now: the command row wins, the event row stays for events without a
  command). Server only (`device_detail.html`, the summary's view in `device_pages.rs`/`locate::recent`,
  `parent_events` by device); tests: the mixed order, the cap, the Cancel still landing on `#activity`, the link's
  filter; the no-jump rule. **Also in 48 (the user, 2026-10-20, the Locate page's Recent: "Den expired location
  event burde jo forsvinne etterhvert? ... Trenger også tidspunkt og ikke dato ... Usikker på om vi skal beholde
  den"; "ja" to this):** (1) Locate's Recent becomes **Requests**: only what waits (with Cancel) and what failed or
  expired in the last 24 h, plus the newest result of Ring, Lock and Erase; a successful Update Location is no row (its
  fix is on the Locations page); the group is gone when empty. (2) Command rows always carry the time: "i dag 13:41",
  "i går 14:50", "15. okt 14:02" (`display_time`, a clock on every row, not only in `title`) - on Requests and on the
  Activity log. (3) `device_commands` older than 30 days are pruned by `retention::prune` like events and locations
  (never a waiting one). (4) The Activity log is where the commands' history lives, with the same clock words.
- **Design 46: a context-mode playlist shows what is coming, and the kid picks a track** - DECIDED 2026-10-20: the
  queue view (the playing track + the next 20, `docs/design/46-context-queue-and-play-marks.md` §1.1-§1.3, the server
  scope + one re-login per account) is on the long list, not in the music round (the user: "Legg køvisning for neste 20
  på longlisten vår, så husker vi på den til senere"); 46b (the parent's own account as a reader, no code) is the road
  to the whole list now. The music round builds 46's play arrow (§1.4) only. The note stays as written for later.
  History: (the user, 2026-10-20: "se hele
  playlisten slik den er, og så velge en konkret sang"; not the queue as such). CORRECTED 2026-10-20: the whole list is
  NOT readable for exactly these playlists - Spotify's development mode lists a playlist's items only to its owner or a
  collaborator (design 26c decision 4, verified 2026-10-13), and a playlist the kid's account owns or collaborates on is
  already list mode with every track. So for a context-mode playlist the phone can show only what Spotify reveals
  without reading the playlist: `GET /me/player/queue` - the playing track and the next up to 20 in the playlist's
  order (shuffle off, so it is the list's order; confirmed by the user: "rekkefølgen følger min playlist helt perfekt").
  Decided: the list screen of a context entry shows the playing track and the next ~20 from the queue, refreshed on each
  track change; a tap on a row plays the context from that track (the same play call with `offset.uri` = the row's uri,
  which needs no playlist read); the hint becomes "spillelistens rekkefølge · de neste 20" for a playlist and stays
  "rekkefølgen velges for deg" for an artist. The full list with free choice exists only in list mode (the kid owns the
  playlist or collaborates on it - the parent can make the child a collaborator to get it). Also noted: a context's
  first play after an idle pause takes a few seconds (the web player is released after a pause and reloads: SDK page,
  token, `ready`, transfer, play) - expected; holding the web player longer on Wi-Fi + charger is a battery trade-off to
  weigh, not a bug. Music app (+ the queue call); design note, the usual process; the behaviour reference stays vibb's
  list. Also in 46 (the user, 2026-10-20: "den viser et lite play tegn på alle storytel bøker og podcaster - men
  ikke på spotify tiles"): the carousel's play mark and progress bar show for a started entry that resumes
  (`entryUi`: `entry.resume && started(...) && !isSpotifyContext`), and a context entry is left out by 26c §5.9 because
  it has no list to measure progress through - so every one of the user's (parent-owned, context-mode) playlists shows
  no mark although it was started. Change: a context entry with a stored live track shows the play mark (started)
  and no progress bar; a list-mode Spotify entry already follows the rule. Music round after stable 0.4.0's blockers.
  **The carousel's play arrow means "tap to play", on every tile** (the user, 2026-10-20: "i mitt hode betyr den
  'trykk på tile for å starte avspilling'"; a deviation from vibb, where the mark means started - the user's call):
  the peach play disc shows on every cover in the main carousel; "started" is said by the progress line and the hint
  alone (unchanged: a started resume entry). `CoverBadges` + `EntryUiTest`/`FitTest`; same music round as 46.
  **Not pursued: a librespot-style session for playlist metadata** (the user, 2026-10-20: "go-librespot klarer å hente
  lister og metadata ... for innloggede kontoer"). True - the client protocol (spclient/Mercury) lists any playlist the
  session can read, free of the Web API's development-mode limits - but it is the same session machinery
  `docs/design/vibb-spotify-options.md` rejected for playback: login5/OAuth churn, new accounts failing (S2), outages
  the official app survives (S5), against Spotify's terms, and a Go client beside the Rust server. For the family's
  own playlists the reader account (46b) gives the same list with the official API and no new code; for a stranger's
  public playlist the queue's next 20 (46) is the honest fallback. Revisit only if 46b proves insufficient.
  **46b - the cheaper road to the whole list: a reader account** (2026-10-20, the user: "er du helt sikker på at vi
  ikke klarer å hente hele listen?"). Design 26c decision 1 says the code already allows it: "N accounts, one bound per
  phone, any of them a reader" - the parent's account is left out by the user's own choice at the time ("ingen grunn
  til at min konto skal involveres"), not by a technical limit. If the parent logs in their own account too, bound to
  no phone, the server lists every playlist the parent owns or collaborates on with that token, and the kid's phone
  plays the tracks by uri in list mode (playing by uri needs no playlist read) - the full list, free choice, pictures,
  today's code. Costs: one of the app's 5 development-mode users (User Management in the dashboard), the parent's
  refresh token sealed on the server like the children's (6-month expiry, the same warning), and the setup text
  "never yours" rewritten to "your own account too, as a reader, if you want your playlists listed in full - it plays
  on no phone" (design 47's "Plays on" picker shows it as "Plays on no phone"). The user decides whether to log their
  account in; 46 (the queue's next 20) stays the answer for lists nobody logged in can read.
- **Investigate: does one bad episode picture URL hold a listing's other pictures?** **done 2026-10-26** (round S1, f20cc7f5). (from qa-r5-design.md r5-3,
  2026-10-20: the proposed `Why::Art` failures rule changed nothing, because with `failures > 0` the regular time already
  is the backoff). Find out whether a single picture's failure raises the listing's `failures` at all, or whether the
  hold is `art_due`'s one-day retry / `ART_MAX_FAILS`; then decide the rule with a test that reproduces the case
  (`fake-audio.py --feed` with one 404 picture). Next music/server round.
- **Music app's episode list: thumbnails and a kid-side order flip** (the user, 2026-10-19, from a Snipp Snapp Snute
  screenshot): (1) small pictures in place of the number circles, only for an entry whose episodes really have their
  own art (the carousel's rule, `showsCarousel`: at least two different pictures; a one-picture entry keeps the
  numbers; rows outside the keep set fetch their picture when shown, under the audio's network gate) - a deviation
  from vibb, which shows own art only in the highlighted row; (2) a small button top right of the list that lets the
  kid reverse the order for that entry on that phone (remembered per entry; the parent's order stays the default; the
  play direction follows the flip). Mockup approved by the user 2026-10-19 ("ja det blir bra!",
  https://claude.ai/artifact/HJbqDJz6F6SD5SEz74tYop: three artboards - own pictures oldest first, flipped newest first,
  a one-picture entry keeping the numbers). **Design 39** (`docs/design/39-music-list-pictures-and-order.md`, revised e002e9b3 - the user, same day: the
  server fetches pictures for every episode, not the first 200, and the phone fetches the playing item's ahead
  whatever the download choice; design QA `docs/design/qa-39-design.md` approved for implementation, with its last
  Medium - reserve ~100 of the day's 400 picture requests for new items during a backfill - for the implementation
  brief). **Merged into `main` 2026-10-19 (937bf7eb)** after code QA (`docs/review/qa-39-code.md`, 0 High / 1 Medium / 3 Low,
  all fixed). Device checks owed: §6q on the emulator; row pictures, TalkBack, the real Spotify SDK through a flip and
  a real Sonos room's first reorder on the Jelly Star.

- **Parked: a display-only widget host on the lock's glance page** (the user, 2026-10-20: "Er det umulig å lage en
  sikker widget host sak i vår launchers lock screen? ... klønete å gjøre alt dette manuelt per app"). Feasible only as
  pure display: `AppWidgetHost` with `appwidget grantbind` at enrolment, every widget under a touch-consuming layer
  that leads to the keypad (the click-interception API `setInteractionHandler` is hidden, so a widget's own
  PendingIntents can't be filtered), accessibility actions off as the keypad's; the parent picks widgets per phone in
  the PWA (they can show content); `glanceFit` takes arbitrary heights. It does not give the timer tile (no clock
  widget carries a running timer with controls - the notification does, design 42). Decision 2026-10-20: not now; the
  targeted tiles (music, timer/alarm, flashlight) cover what was asked. Revisit when a third app needs a glance
  surface.
- **REMOVED 2026-10-20 (the user: "La oss ikke blande oss i det!"; design r7 §2.5): the report's `battery_exempt` and the
  Apps page's "Background:" line are gone, launcher and server; the text below is history.** Catalog apps' battery state in the report, no battery-exemption flow (the user, 2026-10-20: "sikre
  at vi kan installere apper direkte på telefonen, og samtidig live gi de batteriunntak"). The battery optimisation
  exemption: NO flow (the user, 2026-10-20, on the proposal of a time-boxed Settings window for Android's dialog: "Nei
  vil ikke auto åpne settings, da er det bedre at vi ikke legger inn noe spesielt rundt batteriunntaket. Da får folk
  styre det selv") - the parent grants it by hand during a pause as design 19b says, and the app's row in the PWA only
  shows the state ("Background: unrestricted" / "Background: optimized (Android's default) - change it during a
  pause", from the status report's `installed_apps[].battery_exempt` for the catalog apps we installed). The planned
  force-stop protection (`setUserControlDisabledPackages`) was built and then **dropped** after code QA
  (`docs/review/qa-l7-b-code.md` finding 1: a protected package can't be suspended, hidden or uninstalled by the device
  owner either - AOSP `SuspendPackageHelper` "protected package" - so time rules, the budget, the camera lock and the
  update fence would fail open for those apps; the architect's decision, the user to be told). Design:
  `docs/design/r7-launcher-small-items.md` §2. Launcher round 7 (+ the status contract field in both directories).
- **Wake the screen for a notification - a global switch** (the user, 2026-10-20, on the lock screen never waking for a
  notification, design 32b's exclusion: "la det være en global bryter i pwa? Under feks lock screen kategorien i phone
  settings"): one per-device switch in the PWA under Device › Lock screen, off by default (today's behaviour), a policy
  field `lock_wake_on_notification` (both directories, `policy_json_keys_snapshot` + `PolicyResponseCompatTest` in one
  commit); when on, the launcher wakes the screen through the existing VoIP wake path (`PinLockRuntime.show(wake)` with a `WakeKind`; the lock activity
  itself never gets turn-screen-on, design 32 §7; never a dismiss), off again after 15 s untouched, not in a pocket
  (proximity), for a missed call and for a glance app's new row (alerting, not suspended, passes DND; design 51's
  list), never for the music tile. Launcher round 7 (the server half with it).
- **Any allowed app may ring over the lock** (the user, 2026-10-20: "det er ikke sånn at vi bare automatisk kan
  sense om en app har call funksjon med den call style biten? Isåfall kan den få automatisk tilgang"): design 17's
  reading is already generic (a CALL-category notification with a full-screen intent from an unsuspended app); only
  `voipCandidates` (`lock/VoipCallPlan.kt`) fixes the set to Element X and Signal/Molly (the contacts' messaging apps
  while calls are managed, `KNOWN_VOIP_PACKAGES` while unmanaged). Change: the candidate set is every allowed,
  unsuspended app minus the SMS packages, the essential/dialer packages and our own family, under the same gates (calls
  on - a no-calls time rule is folded into the effective state -; none while calls are off or unknown); no ring budget (the user, 2026-10-20: "jeg
  liker ikke tanken på at man ikke kan ringe noen 7 ganger på en time" - and the lock is the kid's own PIN, so a faked
  ring gains the kid nothing). The residual, accepted: an allowed app that fakes a ring gets its own screen over the
  lock for that ring, and with the kiosk on that screen can open other allowed apps for the call's duration (as
  Element X today); strangers can ring through apps whose contacts Vibb doesn't manage. The status report lists the apps that rang (`lock_state.voip_rang`, package + `last_at_ms`, no text) so the PWA can show "rang over the lock". The in-call phase stays
  "a CALL-category foreground notification (or Element's channel) + audio IN_COMMUNICATION": an app without it gets
  the lock back after the 15 s grace, its call going on in the background. Launcher only (+ the status field in both
  directories). Launcher round 7. **Built 2026-10-20 on `l7-a`** (design r7 §1.4: status, deviations, device checks owed).
- **Launcher round 7 §4 - small things from the Fossify Launcher comparison** (the user, 2026-10-20: "kunne du kikket
  på kildekoden til fossify launcher og se om den gjør noe på et kløktigere vis"; the note
  `docs/review/fossify-launcher-comparison.md`): Fossify is simpler, not cleverer - our icons, wallpaper ink, threading
  and accessibility are ahead - but five small things are worth taking: (1) **evict an app's icons when it is
  updated** - `appIconKey` has no version and nothing clears the cache on `onPackageChanged`, so an updated app keeps its
  old icon on Home, the glance page and the time-rule screen until the process dies (`KidAvatars.evictPackage(pkg)`
  from the `LauncherApps` callbacks before `loadApps()`); (2) **pressing Home on Home scrolls to the top** (an
  `onNewIntent` on `HomeActivity`, only when its content was already up - never the lock's or boot's Home starts);
  (3) **the home grid updates one tile** (DiffUtil with stable ids, no item animator, a badge-only payload instead of
  `notifyDataSetChanged()`); (4) **coalesce `loadApps()`** (one job; a burst of suspend/unsuspend callbacks can finish
  out of order today); (5) **an inherited escape**: `actions/AppAction.kt`'s "can't open" dialog's OK calls
  `AppInfo.openSettings` (the app's details page in Settings) - reachable only in a rare fallback and blocked by the
  kiosk, but unguarded (`KidScreensEscapeTest` doesn't scan `actions/`): drop the OK action and add the directory to the
  scan. Not copied (deliberately): the shade fling, app shortcuts, App info/Uninstall in the menu, the widget bind and
  resize flows, the Canvas home with folders, double-tap to lock, drawer search, `WallpaperColors` as ink. A disk icon
  cache / per-app tone for the placeholder is the optional (M) follow-up.
  **Built 2026-10-20 on `l7-a`**: all five as written (`HomeFront.EXTRA_BROUGHT` marks our own Home starts, so only
  the kid's press scrolls; the "can't open" dialog keeps one OK that closes it, its message now "It can't be opened
  right now." / "Den kan ikke åpnes akkurat nå."); tests `HomeRound7Test`, `KidScreensEscapeTest`. Device checks
  owed: an app update's new icon on Home without a restart, also an update while Home is in front with the screen on
  (qa-l7-a-code #1: the package is evicted again right before the reloaded list is posted); Home pressed on a scrolled Home (and not after unlocking,
  at boot or from another app); a badge change rebinding one tile with no flicker; the menus closing on the press.
- **Bluetooth headphones: connect a paired device - fix Kid Settings, then a "Lyd ut" list in the music app** (the
  user, 2026-10-20: "kunne paring/bt connect av eksisterende parese [sic] enheter dukke opp i musikk appen? ... Er du sikker?
  Sjekk andre prosjekter"). Checked against AOSP `android16-release` (packages/modules/Bluetooth): the A2DP profile
  proxy's `BluetoothA2dp.connect(device)` is `@hide` but `@UnsupportedAppUsage` **without** a `maxTargetSdk` (so
  reachable by reflection at target 36, on the "unsupported" list - it may be blocked in a later release), and the
  service (`A2dpServiceBinder.getServiceAndEnforceConnect`) asks only for `BLUETOOTH_CONNECT` + the active user; it
  refuses a device with `CONNECTION_POLICY_FORBIDDEN` or without the A2DP sink UUID and disconnects the current sink
  when only one is allowed. **Our Kid Settings calls
  the wrong method**: `QuickControls.connectBluetoothDevice` reflects `BluetoothDevice.connect()`, which since Android 13
  is `@SystemApi` needing `BLUETOOTH_PRIVILEGED` + `MODIFY_PHONE_STATE` and returns an `int` (read as `Boolean` ->
  always false) - so "Connect" there has most likely never worked on the Jelly Star. Design: `docs/design/52-bluetooth-headphones.md` (QA'd and revised 2026-10-20): Kid Settings connects/disconnects through the A2DP proxy (HFP disconnect too), the music app's 'Spill av på' gets 'Koble til'/'Bytt til' rows for paired audio devices, the launcher grants the music app `BLUETOOTH_CONNECT`; LE-only devices get no Connect.
  **Launcher half built 2026-10-20 on `bt-a`**: `server/BluetoothAudio.kt` (the rules, the A2DP + HEADSET proxies
  awaited by suspending, connect/setActiveDevice/disconnect by reflection on IO), Kid Settings' Bluetooth page through
  it (grant first, live rows on the A2DP/ACL broadcasts, "Kobler til …" then "Fikk ikke kontakt med <name> – er den
  på?", Disconnect also HFP), the address log lines gone, `MUSIC_RUNTIME_GRANTS` (`BLUETOOTH_CONNECT` DENIED without a
  policy); tests `BluetoothAudioTest`, `MusicMediaPlanTest`, `KidScreensEscapeTest`. Device checks owed: design 52 §4
  (the launcher's part: the grant in `dumpsys package me.vibb.music` and its withdrawal, the "unsupported, reflection,
  allowed" line for `BluetoothA2dp;->connect`, a made-up bonded address answering `false`; on the Jelly Star Connect
  with the mark following live, "Fikk ikke kontakt" for a switched-off headset, Disconnect stopping a call's ring in
  it, a watch/keyboard still reading connected, and the scan finding devices again - its receivers were
  NOT_EXPORTED, which the Bluetooth app's broadcasts never reach; now EXPORTED).
- **Design 53 (to write): Sonos grouping + precision** (the user, 2026-10-20). (1) **Grouping in "Spill av på"**,
  approved sketch https://claude.ai/artifact/JZHNZTury59y3UAQzek94G ("digget jeg designeforslaget for gruppering og
  ungrouping! Vi kjører med det!"): a round "+" on every other room row = "spill også her" (the room joins the playing
  group: `SetAVTransportURI x-rincon:<coordinator uid>`), the group as one selected row with its members under it,
  each with "−" (`BecomeCoordinatorOfStandaloneGroup`; the last one out stops); the kid groups by itself ("Barnet
  skal få gruppere selv"), no limit on which rooms ("Ingen begrensing på hvilke høyttalere") - a room playing
  something else may be added (it stops that and joins); the PWA's room scope still limits what the sheet shows; the
  volume cap set on each joining room. (2) **Precision**: GetPositionInfo every 1 s while the player screen shows and a
  room plays (5/15 s otherwise, as today), no backward jumps under 1 s; a measuring round first for the user's Pi
  observation ("pi misforstår etter optimistisk start ... flapper den tilbake") - the phone's `SonosController`
  already holds a step's seed until the speaker reports the new uri or `SETTLE_MS` (8 s) after the SOAP answer, and
  the art comes from our queue: verify with rapid skips on real speakers. (3) **Push**: Home Assistant (open source)
  gets Sonos state from UPnP GENA events through soco's event listener (an HTTP server Sonos calls back:
  `advertise_addr`) and uses the local WebSocket (`wss://<ip>:1443/websocket/api`, `sonos-websocket`) ONLY for
  announcements (`play_clip`) - so GENA would break "the phone never serves" and a WebSocket playback subscription is
  unproven locally: a probe script against the user's speakers first; if it pushes playback/metadata events, a
  WebSocket subscription on top of polling (polling stays the fallback). Sources: home-assistant/core
  `homeassistant/components/sonos/{__init__,media_player}.py`, jjlawren/sonos-websocket. **The DIDL is never changed**
  (the user, 2026-10-20: "Ikke endre noe i didl!!" - no duration, no art, nothing added to what the room is sent; the
  music app covers the gap for the kid; and the reason - "å sende med mer enn vi gjør i vibb pi gjør at det blir
  rejected": the speaker refuses metadata beyond vibb's; `music/CLAUDE.md` carries the rule).
  **Measured 2026-10-21** (`scripts/sonos-play-test.py` on the user's Pi, speaker Sverre, Hallo Bablo, both `--didl
  vibb` and `--didl app` accepted): the local WebSocket pushes `playbackStatus` on every change only (BUFFERING,
  PLAYING, PAUSED, IDLE - no ticks; 8 events while playing over ~60 s), each with an exact `positionMillis`, 0.0-0.4 s
  after the command (the 1 s UPnP poll: 0.3-2.6 s); the natural end is `IDLE` the moment the audio ends (16.5 s after
  a seek to 15 s before the end) - the app's advance then plays the next within ~0.7 s, where today's 5/15 s poll
  leaves up to 15 s of silence. `metadataStatus` carries only `container` = our `dc:title` (no track, duration or
  art, whichever DIDL). So for (2)+(3): a WebSocket subscription anchors the position on each event and the phone
  extrapolates between, and `IDLE` near the end advances at once; UPnP polling stays the fallback and the commands
  stay UPnP. **Grouping, measured the same day** (`sonos-ws-probe.py --all --do group=Edith ungroup=Edith`): one
  socket follows only the groups its speaker coordinates - subscribing to another group answers
  `groupCoordinatorChanged` `GROUP_STATUS_MOVED` with that coordinator's `websocketUrl` (connect there); `groups:1
  modifyGroupMembers` on the own group added and removed Edith in ~0.2 s, the group's id unchanged while its
  coordinator stays (`RINCON_...:100`, name "Sverre + 1"). So the phone keeps one socket, to the coordinator of the
  room it plays to, and follows a MOVED to the new coordinator's url; the grouping verbs can stay UPnP (`x-rincon:`,
  `BecomeCoordinatorOfStandaloneGroup`, as soco) with WebSocket only for events. **The key, measured the same day**
  (`sonos-ws-probe.py --key`, the user's Pi, Sverre): no `X-Sonos-Api-Key` header -> the upgrade is refused (HTTP
  400); a random UUID -> accepted, the full handshake runs. The local endpoint needs the header but doesn't check its
  value - the RFC 4122 example UUID is no secret and no borrowed key (it is in `jjlawren/sonos-websocket`'s first
  commit, 2023-04-09, and in Music Assistant's `aiosonos`; SmartThings' official driver sends its own Sonos-issued key
  to the same port 1443). **The certificate, checked the same day** (the user's Pi, `openssl s_client` to Sverre): the
  speaker's leaf (CN = its MAC, OU Sonos Devices) chains to the **Sonos Device Authentication Root CA** (SHA-256
  `42:DC:D4:A7:D4:B2:3B:D2:86:83:B4:03:37:7B:A8:8A:16:9B:35:78:EC:94:F4:15:BB:E6:FD:48:3D:D5:A0:8F`, 2015-2055; the
  copy in `nickblt/sonos-websocket`, matched against the speaker) - `Verify return code: 0 (ok)` - and its SAN holds
  `urn:sonos:udn:RINCON_<uid>` (plus `sonos-<mac>.local`, the household id, a user id - none of which may be logged). So
  53's trust-any-on-the-private-ip can become: the chain verified against that pinned root, and the leaf's
  `urn:sonos:udn:` equal to the uid we dial (round 53-c). **Next, only after the device checks** (the user asked whether to lean more on the socket):
  commands stay UPnP (a play needs vibb's DIDL, which `loadStreamUrl` can't carry); if the socket proves stable on the
  Jelly Star for some days, relax the UPnP poll while it is healthy (e.g. 60 s instead of 5/15 s) as a small round. **Grouping from outside the app is
  followed** (the user, 2026-10-21: the kids add and drop rooms by holding Play on a speaker, which can change the
  coordinator - "Endres groups utenfor vår musikk app skal vi klare å følge med"): the four cases - a room joins our
  group, a member leaves, our coordinator leaves (Sonos promotes a member and the stream moves: vibb's migration-follow,
  stage B2, ported in `SonosSession`/`SonosController` `_sonos_stream_moved`, today a 0/5/10 s probe), our room joins
  another group (`GROUPED_AWAY`) - each shown in "Spill av på" and the player as it happens; the socket's `groups`
  events and `groupCoordinatorChanged` `MOVED` (the new coordinator's url) make them immediate, the UPnP probe and
  `ZoneGroupState` stay the fallback. Test on the speakers with the hardware button, all four.
  **Status (2026-10-21)**: written as `docs/design/53-sonos-grouping-precision.md` (QA'd, revised, Q2/Q4 answered);
  rounds 53-0 (vibb's DIDL, the goldens) and 53-a (the event socket, the bar's anchor, the 1 s display poll, the IDLE
  advance, the extra polls' guards, `sonos-ws-probe.py --save`) merged after their code QA (`qa-53a-code.md`); 53-b
  (grouping: the UPnP verbs in `GroupVerbs`, the sheet per the approved sketch with the group block, "+"/"−", busy
  rows and per-room/"Begge" volume sliders, the four outside cases followed, Q4's accepted rooms, the joiner cap, the
  advance guard, `fake-sonos.py`'s join/leave) on branch `d53-b`; code QA `qa-53b-code.md` (0 High, 4 Medium, 6 Low,
  6 Nit) and its fix round done - see the note's "53-b status". **Decided and done in that round** (the user,
  2026-10-21: "enig i at den bør slå av alle grouped høyttalere"): the sleep timer's fade on a group fades every
  known room with the coordinator and puts each one's own volume back after the Pause (found in 53-b: before, only
  the coordinator faded).
  §5 check 1, the capture, done 2026-10-21 (the `ws/` fixtures are it, scrubbed; the parser fixed where it
  disagreed). **53-c** (branch `d53-c`): the socket's trust-any is gone - `SonosSocket.kt` verifies the chain
  against the pinned Sonos root alone (`SONOS_ROOT_PEM`, its fingerprint tested) and the leaf's `urn:sonos:udn:`
  against the uid we dial, in the handshake; a refused certificate counts as a refused connect (five park) and logs
  its reason class only; intermediates the speaker sends are path-built under the root - see the note's "53-c
  status". Code QA `qa-53c-code.md` (0 High, 1 Medium, 5 Low, 5 Nit) and its fix round done: a park holds for the ip
  it was earned at (a stale ip after a DHCP shuffle never parks a good speaker, 53-a's TCP case too), `dates` never
  parks (a phone clock far off), several udns, a leaf as issuer, `sonos-ws-probe.py --root` (check 10 from the Pi). Owed on the speakers: design 53 §5 (checks 2-5, 9-11 - check 10 now the phone's TLS accepting the chain:
  `certificate CN ..., issuer CN "Sonos Device Authentication Root CA" - verified` in logcat; checks 6-8c
  for 53-b). **53-d** (the polling round, branch `d53-d`): the "Sonos polling round (53-d)" entry below and the note's "53-d status".
- **Home Wi-Fi as a trigger** (the user, 2026-10-19, "mer en tanke"): the parent marks one network as home; the
  phone reports joining/leaving it (the launcher already holds fine location and the device owner grants it, so the
  SSID is readable; debounce: home after ~2 min on it, away after ~10 min off it); events `arrived_home`/`left_home`
  for design 35's inbox and push, and an outbound webhook (a parent-set URL + secret, e.g. Home Assistant) for parent
  events in general. Not a lost-contact signal: Wi-Fi off or a pocketed phone looks like "away". Scope open.

- **PWA small things**: (0) Notifications › Getting Notifications: after Send a Test the device rows poll for ~30 s
  (as Install Now's row does) so "Shown just now" appears without a reload (the user, 2026-10-19: "måtte refreshe
  siden"); only the rows re-render, never a scroll jump. (1) ~~a switch in the off state loses its knob~~ - fixed 2026-10-19 (d99b3bdc: the knob was
  painted under the positioned switch; `position: relative` on it); the toggle "jump" on Device › Apps fixed the same
  day (scroll-name: a row that moved groups keeps the exact position; `window.name` carries the record too); (2) "add an existing family contact" to a phone
  (the server already keeps one address book per number); (3) every `<details>` confirm
  (Remove Contact… and the rest) gets a clear Cancel that closes it.
- **The child's own photos as wallpaper, from Immich** (the user, 2026-10-15): the child has his own Immich
  account and the Immich app on the phone (the user's Immich is on a public address, so the app reaches it
  without the tailnet). Idea: Vibb One is a share target for images ("Bakgrunn (Vibb One)", `ACTION_SEND`
  `image/*`, maybe `ATTACH_DATA` "Use as"): from Immich (or the camera) Share -> it, cropped to the phone,
  re-encoded, kept on the phone only and added to the kid's wallpaper picker. Behind a parent switch "Can use
  own photos as wallpaper" (off = no share target: the activity alias disabled). The share sheet is already a
  kiosk helper. To check first: what Immich's share sends for a photo that is only on the server (a file
  after a download, or a link). Launcher mostly, plus the switch on the server. Scope not settled yet.
- **`PHONE_SERVER_URL` in `.env`** - **done 2026-10-26** (round S1, f20cc7f5). Was: the phones' server address as an env default; the DB value
  (Phone Setup) wins when set, the page says "from .env", a startup warning when it isn't https.
- **Wi-Fi in Phone Setup**, one of: (A) remember the Wi-Fi for the setup QR (write-only like the
  Tailscale key, left out of backups) - small; or (B) push the family's Wi-Fi networks to every
  phone through the policy - server, launcher and contract, larger. Waiting for the choice.

- **A configurable emergency menu** (the user, 2026-10-16): "Nødsamtale" (the PIN lock's keypad and glance
  page, the VoIP ring card, the time-rule screen, the phone book's 112 tile) calls the phone's emergency
  number - 112 by default, changeable per phone in the PWA - and, when the parent has added more (each with a
  name: "Ambulanse 113", "Brann 110", "Legevakt 116 117"), opens a sheet of large choices instead of "Ringe
  112?", the default first and largest, then Avbryt; one tap calls (the sheet is the confirm). **Only real
  emergency numbers** (the platform's `isEmergencyNumber` or the launcher's country list) are called past the
  call rules and the locks; any other number on the menu follows the call rules like a contact (else the menu
  is a way around the lock) - the PWA says so when one is added. Glyphs, Vibb's language, nothing on the sheet
  opens anything else; `EmergencyCall`'s flow and the `EmergencyDialer` fallback per number. Crosses the API
  (the policy's list, both directories in one commit). A mockup first.

- **Episode pictures on demand, not by the sweep's clock** (the user, 2026-10-19, Snipp Snapp Snute stuck without
  pictures on rc.4 until "Check now": "tlf må be server om filen med en gang"): today an item has a picture hash only
  once the server's sweep fetched it, so the phone can't ask for what it doesn't know of, and an entry checked under
  the old caps waits for its interval (6 h) - `art_more` is 0 for it after the 0.29.0 upgrade. A new entry's first
  check already fetches every picture (continuing at once past the 250-request/10-min budget), and a new episode's
  picture comes in the check that finds the episode, so no phone-to-server "want" is needed (the user, 2026-10-19).
  Two fixes: (1) migration 0082: `art_more = 1` for every listing with items lacking a picture, so the first sweep
  after an upgrade fetches them; (2) soften `failures == 0` in the `Why::Art` rule: one bad episode URL shouldn't
  hold every picture for 6 h.
- **A per-phone avatar as the push notification's icon** (the user, 2026-10-19: the phone's avatar with the round
  Vibb mark as a small tag bottom-right): the worker's `icon` (and the declarative payload's) can be any URL of ours,
  composited on the server from the phone's picture. Chrome/Edge/Brave/Samsung/Firefox show it; Safari (iPhone, Mac)
  shows the web app's own icon and is believed to ignore `icon` - step 1 is one test on the iPhone with a distinct
  icon before anything is built. Goes with the item below.
- **A photo of the child for each phone in the PWA** (the user, 2026-10-16, low priority): the Devices list,
  the phone's summary header and the Locate switcher show a coloured disc with the first letter; let the
  parent upload a photo per phone instead (`photos::process` with `Shape::Square`, like contact photos - EXIF
  and GPS gone, ≤ 512 px - its own `photos::Store` and backups), the disc as before without one. Server only.

### Done since the last release
- **Missed Element X calls on the contact card and the phone book's badge (round L5)** (asked 2026-10-25; no capture needed after Element X Android's source was read 2026-11-02) **MERGED 2026-11-02** (`missed-voip-calls` a284c0bd + fix round 42613749; launcher only; 1953/0; light code QA 0/2/4/5, fixed: a re-judge when the call rules' Element contacts change, an early return before parsing, redacted `toString`, the privacy scan over every source file): the room's `missed_call` notification marks the contact through the learned DM room or the caller's MXID; the badge counts both kinds; the sheet says "1 tapt anrop i Element X i dag 13:05"; cleared when the notification goes, flips back to `message`, or the sheet is opened. Not with an app PIN in Element X. The user's check on the Jelly Star owed. Ships in the next launcher RC.
- **The kid's calendar app is called "Kalender" (en "Calendar"), not "Dagen"** (the user, 2026-11-02: "Navnet på appen, bruk Kalender istedet") **MERGED 2026-11-02** (`calendar-name` becc7cde; launcher + server strings only; launcher 1943/0, server 1526/0; XS, the coordinator's review of the diff): `day_title` (Home tile, Recents, the glance) and the reminder row's "Lås opp og åpne Kalender"; Device › Calendar's group "Kalender-appen"/"Calendar app" and its words; code names, `#dagen`, routes and keys stay "dagen". Ships in the next launcher RC and server release. Still open from the same entry: the colour nit (berry next to peach, 1.21:1), no work.
- **S-i18n slice 3c - the Locate tab in nb and en (design 70 §5.1)** **MERGED 2026-11-02** (`pwa-i18n-3c` 372534f8 + fix round f61892db; server only; cargo 1528/0, node 212/0; light code QA 0/0/3/3, fixed: `locate.zones.full` takes `{max}`, `error.install_send` → `error.command_send`, six nb wordings) - device_locate, device_locations, device_zone, locate.js (data-w-*, `?lang` on locate-result.json), every command row worded (`models::command_label` returns a key); 210 new keys per catalog, the nb list sent to the user. Left for slice 4: `kid_lock::PinAnswer::words`; 3e: the Notify Me About heading must read exactly "Varsle meg om" (Locate's footer quotes it). Browser pass at 390 px owed. Ships in the next server release.
- **Apps › Navn og ikon: the long hint cut, "Vibb-stil" renamed** (the user, 2026-11-02, iPhone screenshot: "Kutte ut denne teksten fra app tab?") **MERGED 2026-11-02** (`app-display-words` 3c43aacd; server only; server 1526/0; XS, the coordinator's review of the diff): the paragraph under the picker gone (and `reset_hint()`); chips "Automatisk"/"Automatic" and "Appens logo"/"App logo", preview "Logo", the describe and error words to match. Ships in the next server release.
- **Ended pauses and extra time in the phone's Activity list** (the user, 2026-11-02: "Ja det er greit" to showing ended lifts there after the Tidsregler tidy took "Nylig" away) **MERGED 2026-11-02** (`activity-lifts` 6122c75f + fix round 953940b6; server only, no migration; server 1526/0; light code QA 0/0/2/2, both Lows fixed): every `time_lifts` row shows once at its grant time, read-only, except a rule pause still running (that one stays in Quick actions with End now, the same `datetime('now')` boundary); "Skole satt på pause i 30 min · Sluttet 14:30", "Avsluttet tidlig 14:20", "+15 min ekstra skjermtid · Lagt til". Ships in the next server release.
- **"Sist sett" again, and an env default language for the PWA** (the user, 2026-11-01: "Sist sett gir mer mening. Ønsker btw å kunne sette en env variabel som gir pwa som engelsk som utgangspunkt?") **MERGED 2026-11-01** (`pwa-default-lang`; server only; 1524 tests, CI 41360789671 green; light QA 0/0/3) - nb "sist sett {at}" / "sist sett for N min siden" (en "last seen"); `PWA_DEFAULT_LANG=en|nb` (`ForkConfig::pwa_default_lang`, invalid = unset, logged): `?lang=` > the account's language > the env default > Accept-Language > English, and the first signed-in request stores the env default; the account switch still wins. The sign-in page itself stays English until slice 3e. Ships in the next server release.
- **The chosen tab's icon filled with peach, same outline and stroke** (the user, 2026-11-01: "fersken fargen skulle fylle det som er grått eller tomt i taben"; mockup A approved: "A ser bra ut, kjør på med den! Med samme str på ikonene som i dag") **DONE 2026-11-01** on main - the same 24 px icons and 2.1 stroke; the chosen/pending tab fills `.ti-body` and the note's beam (`.ti-fill`) with `--tab-on-icon`, and cuts `.ti-detail` (the phone's bar, the pin's dot, the gear's middle) out in `--bar`; style.css v70. Ships in the next server release.

(Everything before shipped in `server-v0.62.0` / `launcher-v0.38.0-rc.2` / `music-v0.6.0-rc.1` on 2026-11-01/02 and moved to `docs/plan/plan-archive.md`.)

### To decide (the user)


- **The app's DIDL differs from vibb's** (found 2026-10-21 against `pi/daemon.py` `_sonos_body`, branch pipewire): for a
  podcast, RSS or NRK series item the app sends the listing's `duration` on the `res`, which vibb sends only for
  Storytel, and never the picture vibb sends when it is http (`PlayBodies.build`, `SonosController` calls it without
  `art`). The user's rule is vibb's DIDL field for field; the speaker test `scripts/sonos-play-test.py --didl vibb|app`
  shows whether a speaker refuses either. Fix after that: the app sends exactly vibb's fields. **Decided and done**
  (design 53 §2.1, round 53-0, branch `d53-a`): a duration only for Storytel, no picture (the phone holds hashes only),
  one `builtDidl` checked byte for byte against `scripts/sonos-play-test.py --golden`'s files; the listing's duration
  rides as the session's tail-cadence hint only. Owed: design 53 §5 check 9 on the speakers.

## Open questions

- **Full Tailscale for the kid** (wanted: Immich and other tailnet services
  from his apps). Upstream's embedded tsnet is userspace inside the launcher
  only, so other apps can't reach the tailnet, and Android allows one VPN at a
  time (currently the launcher's DNS-filter `KidVpnService`). Direction
  (2026-10-04): standalone Tailscale app as always-on VPN set by the device
  owner, **no exit node, no lockdown** (lockdown without an exit node kills
  all non-tailnet traffic, tailscale/tailscale#12925; the kid still can't turn
  the VPN off with `DISALLOW_CONFIG_VPN`). Upstream did this before commit
  `cdb4835`; the managed-config plumbing is in its history. DNS filtering
  moves to the tailnet (Tailscale DNS → AdGuard Home at home); verify on the
  phone that Tailscale DNS applies to all lookups without an exit node.
  Tailnet ACLs: kid's device reaches Immich and the server's device API, not
  the admin UI. Per-device switch between this and upstream's tsnet mode.
  **Design 22 (2026-10-09)** settles the shape: per phone one of tsnet, the
  Tailscale app (mode 2, launcher step L2 after design 03's spike) or direct
  HTTPS; in mode 2 DNS filtering moves to the server's own tailnet resolver
  (S6) instead of AdGuard Home. Server 0.23.0 already splits the admin and the
  phones' API into two listeners (the admin answers only tailnet/loopback
  unless `ADMIN_PUBLIC=on`), so the ACL can give `tag:kid` the phone port only.
- Messaging: own Matrix homeserver so Element X contacts are enforced there?
