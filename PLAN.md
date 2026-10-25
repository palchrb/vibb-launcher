# PLAN: handy — an open, parent-curated kids' phone

Handoff from a design discussion (claude.ai, Oct 2026). Goal: a simple,
locked-down Android phone for my son, built on open pieces instead of a
proprietary kids' ecosystem (Xplora). Not urgent: he is 9 and gets an XploraOne
now; the target is a working phone by lower secondary school (~age 13).

## Goals

- Phone does only what the parent allows: calls to/from approved contacts,
  Element X (Matrix) instead of SMS, the Vibb music player (separate repo), a
  few curated apps.
- No Google Family Link, no ad-funded third-party blockers. Everything
  self-hosted and open where possible.
- One parent PWA for everything: phone admin (apps, calls/messages, device
  features, DNS, schedules) and Vibb's music library.
- Rules are enforced locally on the phone; the home server only defines them.

## Decision: fork kids-launcher-mdm + kid-phone-server

[kids-launcher-mdm](https://github.com/siesta5787/kids-launcher-mdm) (Android
launcher + device-owner agent, Kotlin, views not Compose, minSdk 34) and
[kid-phone-server](https://github.com/siesta5787/kid-phone-server) (Rust/Axum,
SQLite, server-rendered PWA, runs on a Pi Zero 2 W). Both GPLv3, so `handy`
is GPLv3. Started July 2026, last push 2026-08-17, 3 stars; written with
Claude Code by a developer new to programming, so review before trusting.

Already there (from the code, more than the README says):

- Device owner provisioning (QR), app allowlist via suspend/hide, lock task
  with per-device chrome, global + per-device schedules enforced offline.
- App catalog on the server: silent install/uninstall/update via
  PackageInstaller, incl. the launcher's own self-update.
- Offline override PIN (PBKDF2, verified on the phone), PIN-gated settings. Since design 24 the "parent
  PIN": one gate on the phone with an escalating lockout (15 min up to 24 h), every use, wrong try
  and lockout reported to the PWA, its own card there with an easy-PIN warning. Since design 24b a window the
  parent PIN opens (override, pause) locks again by itself and is reset or reported at the relock, "Lock again"
  on the phone and in the PWA (and a pause from the PWA), the parent PIN is kept as a Keystore HMAC and refused
  while a device admin or accessibility service the parent didn't allow is on, and boots without the launcher are
  reported (safe mode blocked by default after device check 6).
- Tailscale embedded in the launcher (`tsnet.aar`), no Tailscale app needed.
- DNS filtering via the launcher's own `VpnService` + blocklists from the
  server, private DNS lock, DNS log.
- Find my device: locate, ring, lock, wipe.
- Server→phone nudges over SSE held by a foreground service (plus periodic
  sync). (Upstream's UnifiedPush distributor, conversation journal and browser
  history were removed in the 2026-10-06 cleanup.)
- Install progress, blocked-event log (opt-in since the cleanup).
- Admin: argon2 password + mandatory TOTP, separate device bearer tokens.

Lesson they already learned: always-on VPN *with lockdown* breaks all
internet when the VPN has no default route (also tailscale/tailscale#12925),
so lockdown stays off.

## What handy adds

### Calls and messages

- **Default dialer** (`ROLE_DIALER` + `InCallService`): outbound calls only
  to approved contacts; contact buttons on the home screen.
- **Call screening** (`CallScreeningService`): reject inbound calls not on the
  allowlist. The default dialer's screening service sees all calls. Role is
  requested once at setup (parent taps accept), then locked with
  `DISALLOW_CONFIG_DEFAULT_APPS`.
- Per-device switches: calls on/off, separate in/out allowlists.
- Emergency numbers (112/110/113) always work.
- SMS on/off (`DISALLOW_SMS`); an SMS allowlist needs the launcher to also be
  the default SMS app.
- Element X: who can message him is enforced on the Matrix homeserver
  (closed registration, invite/federation rules), not on the phone.
- Carrier: ice iceJunior limits outbound to 3 numbers but not inbound, so
  screening is still needed.

### Device controls missing upstream

- Camera on/off device-wide (`setCameraDisabled`) or per app.
- Per-app runtime permissions (camera, mic, location) via
  `setPermissionGrantState`; new requests auto-denied.
- Screenshots (`setScreenCaptureDisabled`), volume cap.
- Hardening, always on: `DISALLOW_SAFE_BOOT` (safe mode bypasses the
  launcher), `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_FACTORY_RESET`,
  `DISALLOW_ADD_USER`, `DISALLOW_MODIFY_ACCOUNTS`,
  `DISALLOW_INSTALL_UNKNOWN_SOURCES`, `DISALLOW_CONFIG_DEFAULT_APPS`.
  (Check which upstream already sets.)
- Parent-settable DNS: upstream filters in its own VPN; add the option of a
  private DNS hostname (NextDNS/AdGuard/self-hosted) if that turns out
  simpler.

### Vibb integration

The Vibb music player is built in the [vibb](https://github.com/palchrb/vibb)
repo (draft `PLAN-android.md` there); it runs vibb itself, like the Pi box.
`handy`:

- installs/updates/allowlists it via the server's app catalog,
- passes settings and the library via managed configuration
  (`setApplicationRestrictions`): hide in-app settings/pairing, volume cap,
  and the library the parent edits in the PWA,
- includes it in schedules (e.g. allowed at bedtime for audiobooks).

**Status (2026-10-12)**: the player is the Vibb music app in this repo (`music/`, designs 20/21/21b, ported from
vibb). Phase 1 (NRK, RSS, own files, the launcher bridge, Home card, lock view, volume cap) is on `main`, released as
`music-v0.0.2` (`music-v0.0.1` before it); its Jelly Star checks still to come. **Phase 1b, Sonos** (design 26a: rooms in "Spill av på", the phone as
UPnP controller, adopt after a restart, bedtime stops the speaker, calls pause it, the parent's volume cap and room
scope) is built and QA'd on branch `music-1b-sonos`, main merged in (last 2026-10-12, `620e578`: design 27's
episode art and carousel, the swipe routed through the renderer; before it `444ca58`, main's instant switch adapted
to the rooms); owed: the emulator checks against
`scripts/fake-sonos.py` and the device checks with real speakers. Release after the user's merge: server (migration
0071, in 0.24.0 unless that is tagged first), a launcher release, then `music-v0.2.0-rc.1` on the merge commit
(26a "Release"). **Phase 2, Storytel** (design 26b: the server reads the shelf and lists it, the phone logs in,
mints and downloads, a book's own cover through design 27's art pass, Storytel on Sonos) is built and QA'd on branch
`music-2-storytel` (steps 1-5 done, 2026-10-12; music-1b-sonos with main and design 27 merged in, so merging
`music-2-storytel` brings 1b too); owed: the emulator checks against `scripts/fake-storytel.py` (with the fake
speakers) and the device checks with the family's Storytel account. Its server migration is **0072**
(`0072_music_storytel.sql`, after design 27's 0070 and 1b's 0071, applied in that order). Release after the user's
merge (26b "Release"): the server (a version bump past 0.24.0 if that is tagged first), the launcher, then
`music-v0.3.0-rc.1` (versionCode 2999) on the merge commit - instead of 1b's `music-v0.2.0-rc.1` when both land
together. **Phase 3, Spotify** (design 26c, `docs/design/26c-spotify.md`, architect draft 2026-10-13 on branch
`music-3-spotify`): the spike (`docs/testing/spotify-spike.md`, branch `spotify-spike`) passed item 10 on the emulator,
and the user chose the WebView route - Spotify's Web Playback SDK in a headless WebView in the music app, no Spotify
app on the phone. The parent adds a share link in the PWA with per-phone ticks; the server lists it like an RSS feed
with the family's own developer app (bring your own client ID, PKCE logins in the parent's browser, refresh tokens
sealed); the phone plays it as a third renderer with a server-minted token. **The account model** (the user,
2026-10-14): one Spotify account per phone, the child's own; the parent's own account is never logged in - the parent
pastes links, and a playlist lists its tracks only when a child's account owns it or collaborates on it (else it plays
in Spotify's order). **The kid's app shows no trace of Spotify** (the user, 2026-10-14): a Spotify entry is a tile
like any other ("Musikk"/"Podkast", plain lines); the attribution Spotify's Developer Policy asks for is the PWA's
only - a stated deviation (26c §7.3). **Built** (2026-10-13; step 8 2026-10-14): steps 1-8, each
code step with QA's code review and its fix round, main merged in at `571f86f` (design 30; Spotify's migration is
0074, after design 30's 0073) and at `5584772` (design 31 steps 4-15: Spotify's "Plays as" on Device › Music, "From
Spotify ›" the Music tab's fifth Add row). Owed: the DNS capture that replaces the provisional host list (one commit of the
list, the constants and the comment - the three `spotify_hosts.json` copies, the six constants -, before any tag of
this release), `docs/testing/emulator.md` §6l
(parts A1/A2 never ran; part B with the user's account), the Jelly Star checks (26c's QA checklist, the 2.5-hour Doze
run). Release after the user's merge (`--no-ff`; 26c "Release"): the launcher (`launcher-v0.26.0`, above
`launcher-v0.25.0-rc.1`, unless `main` tags a launcher first), `music-v0.4.0-rc.1` (versionCode 3999), then the
server - design 30's 0073 as 0.25.0 first (or in the same release), Spotify as **0.26.0** with 0074 (the bump a
commit on `main` after the merge), never before 0073; the stable `music-v0.4.0` after the owed checks. `server-v0.24.0`
is tagged (`6ae54e8`, Storytel's 0072) but its release run failed at the signing step - no release yet. **Main's
step 0**: the `release` environment admitted the tag and `SERVER_MINISIGN_KEY` is set, but minisign can't read it
("Error while loading the secret key file"): re-enter it as the whole two-line key file from `minisign -G -W` (the
pair whose public key is pinned), then re-run the failed `server-release-sign` job. Design 31 step 12 and Spotify: settled at
the merge of main `5584772` - "From Spotify ›" is the Music tab's fifth Add row, and `/music/spotify/callback` (and
`/done`) never move: it is the redirect URI every family registered at Spotify (26c "Release", open point 8).
**Design 29** (a new entry is Nytt news: one item per entry the phone never knew, once a play has been
stored; reused ids reset; own-file copies re-fetched after an id reuse) is built on branch `design-29-impl`
(2026-10-13, music app only, Room 3); owed: its emulator checks (`docs/testing/emulator.md` §6f) and a
`music-v*` release. **Design 30** (two mobile-data switches, streaming and downloads; a stream saved to the cache as
it plays; played extras kept within a per-phone "Music storage limit" and a free floor) is built in four steps,
each on its own branch (`design-30-s1` server, `design-30-s2` launcher, `design-30-s3a` the music app's switches and
the report fields, `design-30-s3b` cache-through, extras and the limit, Room 4; 2026-10-13); owed: its code QA of
3b, the emulator and device checks (design 30 §8, `docs/testing/emulator.md` §6f, `scripts/fake-audio.py`), and the
release order of its step 1 status (no `server-v*` with step 1 before the `launcher-v*` and `music-v*` that read
the two switches).
Phase 3 (Spotify) is design 26c above (branch `music-3-spotify`, steps 1-8 built, which merged this main at
`5584772` on 2026-10-14 - its migration is `0074_music_spotify.sql`, after design 30's 0073).

## Repos

- One repo since 2026-10-05: `palchrb/vibb-launcher` (this one, local dir
  `handy`). `launcher/` is the phone app (was the `kids-launcher-mdm` fork,
  branch `handy`) and `server/` the home server (was the `kid-phone-server`
  fork, branch `handy`), both imported with full history (`git subtree`).
  `docs/`, `scripts/` and this plan sit at the root. Features that span both
  land in one commit/PR.
- CI is at the root (`.github/workflows/`): `launcher.yml` and
  `server-ci.yml` run only when their own directory (or workflow) changes.
  Releases: tag `launcher-vX.Y.Z` (or `launcher-vX.Y.Z-rc.N`, a prerelease)
  for the signed APK, `server-vX.Y.Z` (must match `server/Cargo.toml`) for
  the Pi tarball. Only stable launcher releases become GitHub's "latest"
  (the provisioning QR's APK URL); the server's update check and
  install/update scripts look up `server-v*` releases by tag.
- Upstream (`siesta5787/kids-launcher-mdm`, `siesta5787/kid-phone-server`)
  is still worth following. For an occasional cherry-pick, add it as a
  remote and pick into the matching subdirectory, e.g.
  `git remote add upstream-launcher https://github.com/siesta5787/kids-launcher-mdm`,
  `git fetch upstream-launcher`, then
  `git cherry-pick -Xsubtree=launcher <sha>` (or
  `git subtree pull --prefix=launcher upstream-launcher <branch>` to merge a
  whole branch). Changes worth offering back go upstream as PRs from a
  separate clone of the upstream repo.
- Talk to upstream early (issue in each repo) about calls/SMS support before
  building it, so the design fits what they would merge.

## Architecture

- **Home server** (`server/`, fork of kid-phone-server): defines all rules, serves the
  parent PWA, reachable over Tailscale. Runs on a Pi or similar.
- **Phone** (`launcher/`, fork of kids-launcher-mdm): subscribes to the server (SSE nudge;
  FCM from step 7 was removed again in design 19) and fetches policy on a nudge or on
  the periodic sync. The phone does not serve anything.
- **Principles**:
  - Local enforcement: cached rules apply when the server or network is down;
    a server outage must never unlock anything.
  - Minimize always-on work on the phone (battery): one held connection,
    measured, not assumed.

## Hardware

- **Start: Unihertz Jelly Star on stock Android.** Device owner via QR/`adb`
  is a standard Android feature, so the launcher is the same on any later
  phone. Upstream needs Android 14+ (minSdk 34).
- Later option: a Pixel with GrapheneOS if Unihertz updates dry up.
- Upstream installs apps itself and has zero GMS footprint, so no Google
  account is needed for updates. Element X from Play uses FCM (Play services stays
  installed and unrestricted for it - our own nudges don't use FCM); the F-Droid
  build would need UnifiedPush via the ntfy app (the launcher's own distributor
  was removed in the 2026-10-06 cleanup).

## Status (2026-10-05)

Done in code (`launcher/` and `server/`, QA-reviewed): own build and release
signing, fail-closed policy, calls/phone book/message buttons, direct boot,
hardening. Not frozen: remaining features continue in parallel with device
testing on the Jelly Star. Not built yet: full Tailscale (design in
`docs/design/03-tailscale.md`), Vibb integration, AdGuard Home, launcher UI
redesign. Play account: decided 2026-10-05 to start WITHOUT one (apps via the
server catalog); add later only if Play-only apps are needed. First likely
case: Vipps (Vipps for children 7-15 exists). Vipps' own support page states it
requires Play Integrity and Play services and must be installed via the Play
Store, and refuses phones with "unsafe" apps installed. So Vipps = a dedicated
Google account on the phone, Play kept hidden from the kid but able to update.
Device test: whether Vipps accepts a phone managed by our device-owner launcher
(VPN, notification listener) or flags it as unsafe.
**Decided 2026-10-05: support Play as a first-class app source** (alongside the
server catalog). Needs: a dedicated Google account added after device-owner
provisioning (runbook `docs/setup/google-account.md`: before enrolling, or with
"block account changes" off temporarily; Android's backup to Google stays off);
Play Store and Play services never hidden or suspended (hiding
= effectively uninstalled, no updates) but not in the kiosk/launcher, so the kid
can't open Play (market:// links are blocked by lock task); a parent "install
mode" on the phone (PIN) that lets Play run in kiosk for a limited time to
install an app, after which the parent allowlists it in the PWA as today;
Play auto-updates on. Device test: Play updates in the background while not
launchable.

**Server 0.24.0 (2026-10-11, not tagged yet)**: design 22's server side (two listeners, the admin gate, hashed
enrollment codes, the Docker image), QA 22's fixes, and the server part of the security review 2026-10-10 (its
"Server security wave status" lists each finding). Before tagging it, the repo owner creates the release signing key
(`server/DEPLOY.md`, "Release signing"): root on the Pi takes only signed releases from 0.24.0 on.

**The lock's glance page (design 25, 2026-10-12, not device-tested yet)**: the PIN lock always comes up on a first
page - clock, date, the music tile, the newest missed calls and messages (Element X ticked by default; the parent
picks the apps and whether the text shows on the phone's Lock Screen & PINs page in the PWA), "Lås opp" and the emergency
call. A row's tap asks for the code and then opens the conversation or the missed calls; under a time rule only what
the rule leaves usable, over the time-rule screen. Read in memory only, never sent to the server; the lock's window
is `FLAG_SECURE`. Server migration 0066; the Jelly Star checklist is in the design's "Implementation status". **Lock fix round 25b** (2026-10-12): the camera gesture no longer crashes SystemUI on the lock (cameras off by policy while LOCKED), the lock re-fronts after a pause too (Recents), no transition or status-bar flicker at power-on, Emergency SOS works on the lock, Element X missed calls as missed-call rows, the music tile for any session on the glance page only (user decision); device checks in design 25.

## Next features (agreed 2026-10-05)

- **Launcher UI like Xplora One**: grid of round app icons and contacts with
  notification badges (unread counts per app via NotificationListener; missed
  calls per contact with a missed-call icon), phone book as a grid with a
  Call/Message sheet, Android's own status bar visible (lock-task system info).
  Mockup: https://claude.ai/artifact/9aUGgCCZEPUnJYgtB78jJh
- **Contact photos**: the parent uploads a photo per contact in the PWA; the
  server stores it and sends a hash/URL with the call policy; the launcher
  caches it and shows it in the phone book, on Home and in the contact card
  (initial letter when there is none).
- **Calendar** (decided 2026-10-06): Fossify Calendar (agenda view, no internet
  permission) + DAVx⁵ (CalDAV sync, e.g. iCloud with an app-specific password or
  Nextcloud), both from GitHub/F-Droid via the server catalog. DAVx⁵ adds an
  Android account, which "block account changes" prevents — set it up before
  enrolling or with that switch off briefly. Share calendars read-only on the
  CalDAV server where the kid shouldn't edit. Sync interval ≥1 h for battery.
  Later maybe "next event" on Home.
- **Notification shade**: pulling down notifications like normal Android. Already
  on in kiosk upstream (lock-task NOTIFICATIONS feature forced on). Check what
  Quick Settings exposes there (airplane mode, Wi-Fi, Bluetooth, the Tailscale
  tile) and add DISALLOW_AIRPLANE_MODE / tile restrictions where needed
  [needs device test].
- **School mode and time rules** (user, 2026-10-05): replace upstream's fixed
  weekday/weekend/bedtime windows with a list of named rules per weekday
  (e.g. "Skole" Mon-Fri 08:15-14:00, "Leggetid", custom blocks), each showing a
  full-screen "time + rule name" screen with nothing else usable; plus a daily
  screen-time budget (minutes of use per day, per weekday) after which the phone
  locks the same way. Emergency calls always work. Decided: when the screen-time
  budget is used up, calls (phone book) and the messaging apps chosen for contacts
  stay usable, everything else locks; during school mode nothing works except
  emergency calls (no calls or messages in or out). Screen time = time with the
  screen on and an app in use; calls don't count. The parent can lift an active
  rule from the PWA for a set time (e.g. "end school mode for 30 min"), delivered
  instantly via the existing SSE nudge, then call. Open: per-rule app exemptions
  (calendar, Vibb at bedtime). Implement after the
  UI/photos/i18n step (same launcher files).
- **Battery** (review 2026-10-05; not yet measured). Always-on work today:
  SSE keepalive every 15 s (`device_api.rs:740`, wakes the radio ~5,800×/day,
  likely the biggest drain on mobile data), periodic sync + status report every
  5 min, schedule check every 60 s, active GPS fix at most every 10 min, plus
  tsnet and the DNS-filter VPN. Plan: keepalive minutes not seconds (tune
  against carrier NAT timeouts) or FCM as the nudge (works without a Google
  account, but metadata goes to Google); sync every 15-30 min while the push
  channel is up; one alarm at the next schedule boundary instead of polling;
  location policy chosen by the parent (off / on request / every N min), active
  fix only on request or after significant movement; "update location now"
  button in the PWA. Measure first on the Jelly Star (`dumpsys batterystats`,
  idle drain per day) before and after.
  **Decided 2026-10-05: FCM for the "something to sync" nudges - reversed 2026-10-07,
  design 19** (`docs/design/19-remove-fcm.md`): the SSE stream is the only nudge again, its
  keepalive 240 s by default, hardened for deep sleep (stale-stream reconnect, a wake lock for
  the first retries); one APK works with anyone's server, no Firebase setup, no nudge metadata
  to Google. Device run on the Jelly Star still to do: time a ring in Doze, after a server
  restart and after a Wi-Fi/mobile switch, and battery at keepalive 120 s vs 240 s.
  **Parked 2026-10-19 (the user: "fcm lar vi ligge pr. nå")**: an FCM opt-in configured from the server (design 40)
  waits for that measurement - a day's bugreport from the Jelly Star, the launcher's radio-active time and wakelocks
  in Battery Historian. Under ~3 %/day: no FCM, at most a looser keepalive on Wi-Fi; well above: design 40. A
  UnifiedPush distributor (ntfy) would hold the same kind of connection in a second app, so for one app it saves
  nothing over SSE; only the system's own FCM connection does.
  **Plan B if that run is bad (user, 2026-10-09: "Evt ntfy")**: an optional UnifiedPush wake
  channel per phone, next to SSE. The launcher becomes a UnifiedPush client, and the
  family's ntfy (self-hosted with `upstream-base-url: https://ntfy.sh`, or ntfy.sh itself)
  is the push server. The ntfy app (Play flavour) runs as a hidden engine app on the kid's
  phone and is woken via ntfy.sh's FCM with a content-free poll request, so neither our
  Firebase project nor any content goes to Google. A push only triggers a sync over the
  device API. Costs: one more app to keep allowed and exempt, plus a dependency on ntfy.sh
  and Play services. Before building it, try a battery-optimisation exemption at setup
  and design 22's direct-HTTPS mode without tsnet (design 19b phase 1 is built: the status line,
  the "Background connection" row during the pause, the adb line in the setup docs). The
  original decision, as history: FCM (works without a
  Google account; Play services stay installed and are never suspended).
  Content-free high-priority data message, so a forged or replayed push can only
  trigger a sync; the phone still fetches everything over the authenticated
  device API. Replaces the always-on SSE connection and its foreground service
  where FCM is available; SSE stays as the fallback transport on devices without
  Play services; periodic sync every 15-30 min as the backstop. Server sends via
  the FCM HTTP v1 API with a service-account key in `.env` (secret); the
  launcher's Firebase config is injected at build time (CI variable / gradle
  property), not committed, so the fork stays generic. The launcher's UnifiedPush
  distributor (ntfy.sh relay) was removed in the 2026-10-06 cleanup; use the ntfy
  app if F-Droid-flavoured apps ever need one.
- **Own lock screen instead of Android's PIN** (user, 2026-10-05): Android screen
  lock "None" + `setKeyguardDisabled(true)`; the launcher shows its own PIN lock
  (kid's PIN set by the parent in the PWA, stored hashed) on screen-on, in the
  same look as the rest, with an emergency button and incoming calls from
  allowed contacts answerable. While it's up, lock-task features drop the
  notification shade so nothing is readable before the PIN. Trade-offs: no
  per-user storage encryption credential (FBE device key only); remote "lock"
  uses our lock; the before-first-unlock state disappears (simpler calls).
  Design together with the in-call screens in the next UI round.
- **Location: on request + last position at shutdown** (user, 2026-10-05): default
  location policy "on request", plus a report on ACTION_SHUTDOWN (any reason incl.
  low-battery shutdown) with the last known fix (no time for a fresh GPS fix), and
  an active fix + report when the battery drops to ~5% so a dying phone leaves a
  fresh position. Sudden power loss sends nothing; airplane mode means no network.
- **Fixes from the 2026-10-06 emulator run** (docs/testing/2026-10-06-emulator-run.md) -
  **done 2026-10-06, needs an emulator/device pass**: PIN keypad fits 320x569 dp, less top
  space on lock and Home, silent call notification while our call screen is in front, no stuck
  call notification/screen after a call, one call at a time (emergency excepted), Home contacts
  open the sheet, unclipped call badge, sheet Back/swipe, parent-set screen timeout (auto-lock)
  per device, PWA keeps the scroll position on every form, Vibb icon/splash/night palette and
  the "Vibb night" default wallpaper for new phones.
- **Kiosk escapes (design 11, 2026-10-06) - done, needs the A/B device checks**: the launcher's
  own update is downloaded at once and installed at night (02-05, screen off, no call; any quiet
  screen-off after 24 h) behind an update fence (other Home apps suspended, shade off, released by
  one idempotent check), Home comes back after the update, and other apps' nags are cancelled by
  the notification listener. Both server switches default off until A3 (Jelly Star) and B5 pass
  (`docs/testing/emulator.md` §6d).
- **Build hygiene (user 2026-10-06) - done**: FID-based FCM registration (gone with design 19),
  BluetoothManager, WifiConfiguration confined to suppressed DO-only code, kapt, R8 dnsjava warning; Kotlin
  warnings fail CI (`-PwarningsAsErrors=true`). Left: kapt's javac "RELEASE_11" note from the
  PreferenceProcessor. Device tests: Wi-Fi/Bluetooth toggles.
- **Remove FCM (design 19, user 2026-10-07) - done, needs the Jelly Star run**: server 0.20.0
  (no FCM, migration 0048, keepalive 240 s, "Instant changes" line on the device page), the
  launcher without Firebase (release guard on the classpath, one-shot cleanup of FCM leftovers)
  and with the SSE hardening (stale-stream reconnect, drop wake lock, one reopen sync rule).
  Release: server first, update the Pi, then the launcher; delete the `HANDY_FCM_*` repository
  variables only after a launcher tag on these commits; revoke the service-account key.
- **Provisioning (2026-10-06)**: QR carries locale/time zone (`nb_NO`/`Europe/Oslo`), the
  launcher has the Android 12+ provisioning-mode/compliance activities, system language locked
  while managed. QR on the Jelly Star: [needs device test], adb stays the fallback.
- **Pending user decision**: drop the top-left back buttons on full pages
  (`KidHeader.SHOW_BACK_BUTTON`).
- **Cleanup: remove monitoring and unused upstream features** (user, 2026-10-06) - **done
  2026-10-06** (plus the applicationId `me.vibb.launcher`): journal, browser history, UnifiedPush relay
  and MollySocket removed (migration 0038), DNS log opt-in with 7 days, locations 7 days by default
  (per phone), status/screen-time history and blocked calls 30 days, crash reports to the server
  instead of ReportCrashActivity. Needs an emulator pass (§5b smoke test, §6d camera checks).
  The request: remove the conversation journal (Molly fork) and browser history (launcher sync,
  DTOs, server routes/tables/templates; drop data via migration), the UnifiedPush
  relay via ntfy.sh and the MollySocket installer. DNS log off by default (opt-in,
  7-day retention or counts only); location history retention 7 days default;
  blocked-call log and screen-time 30 days; check upstream ReportCrashActivity for
  kiosk escapes (share/email) and replace with a server-side crash report.
  Principle: collect only what's needed to manage the phone, delete on a schedule,
  never store notification or message content.
- **Smoke-test script** `scripts/smoke-test.sh` (adb, remote-capable) - **done 2026-10-06**,
  `docs/testing/emulator.md` §5b; not run against a real emulator yet: lock/unlock,
  calls in/out, one-call rule, emergency from lock, call notification cleared,
  screenshots — run after every change and before allowing Android updates.
- **Vibb music on Android: Spotify via App Remote (option A) is the main plan**
  (user, 2026-10-06; docs/design/vibb-spotify-options.md). **Superseded 2026-10-13**: after the spike the
  user chose the Web Playback SDK in a WebView (design 26c, built on `music-3-spotify` 2026-10-13; its "Release"
  says what is owed), no Spotify app and no engine-app state. Requires a Play account
  on the phone and an "engine app" state in handy for Spotify. Start with the
  device spike.
- **Custom DNS upstream** (user, 2026-10-06): DNS page option "own DNS server"
  (address, port, protocol plain UDP/TCP or DoT + TLS hostname) for the on-device
  filter's upstream, e.g. AdGuard Home on a tailnet IP; tailnet addresses routed
  through the embedded tsnet (TCP). In Tailscale-app mode the user's Headscale DNS
  override sends all DNS to AdGuard and our filter VPN is off for that phone.
- **Keyboard: HeliBoard** (user, 2026-10-06): open-source AOSP-based keyboard
  (GitHub/F-Droid → server app catalog, auto-updates), no internet permission,
  adjustable height for the 3" screen. Launcher restricts keyboards with
  setPermittedInputMethods and makes HeliBoard the default (check whether
  DPM.setSecureSetting(DEFAULT_INPUT_METHOD) is allowed for device owners; else
  set once at setup). Wish: parent sets keyboard languages (nb + en layout) and
  the Norwegian dictionary from the PWA. Needs research: HeliBoard has no managed
  configuration as far as known; options are enabled IME subtypes via secure
  settings (if a device owner may write them), shipping HeliBoard's settings
  backup or the dictionary file via the launcher, or a one-time setup with the
  parent code. Design before building.
- **Recovery / no soft-brick** (2026-10-05): DISALLOW_FACTORY_RESET only blocks
  Settings; a recovery-mode wipe always works (first Jelly Star test: keys and
  menu). After a wipe, FRP asks for the phone's Google account if one was added,
  so keep its credentials. Offline, the override PIN lifts app/kiosk/lock but not
  the step-4 hardening switches (server-only, by design). Keep an override PIN
  set and written down, server backups on, and a "Recovery" runbook section.
- **Rest mode during bedtime/school** (idea, 2026-10-05; after battery is measured):
  no or rarer location fixes, backstop sync every ~2 h (the SSE stream still delivers -
  since design 19 it is the only nudge, so tsnet must stay up: pausing it would stop every
  ring, lock and lift until the next sync), optional Wi-Fi/Bluetooth off at night (mobile
  stays for calls and the stream), skip non-essential jobs; maybe pause the DNS VPN. Not
  possible: airplane mode, battery saver, mobile data off (device owner can't).
- **i18n from the start**: Norwegian (nb) and English. Launcher: all strings in
  resources with `values-nb`, per-app language (generateLocaleConfig is already
  on), language chosen per device by the parent. Server PWA: string catalog with
  nb/en and a per-admin language setting.
- **Idea, long list (user, 2026-10-12): a widget on the lock's glance page**, e.g. a Fossify widget
  (calendar, clock, weather). Not designed yet. Open: privacy on a locked phone (a widget shows its app's
  data to whoever holds it), `AppWidgetHost` in the lock activity, and which widgets the parent may allow.
- **Idea, long list (user, 2026-10-13): several families.** Parked by the user. The server is one family per
  instance; one family can share one admin login. Options, cheapest first:
  - (A) one instance per family: the Docker image, own data dir, port and Caddy site; needs only a setup script and docs;
  - (B) one process with a SQLite file per family, routed by login and device token; the shared state (catalog,
    music files, Storytel, DNS lists, backups) moves per family; about 8-12 steps;
  - (C) `family_id` on every table and query; 15-20 steps and a leak risk in every future feature.

  Hosting other families' data makes the operator the GDPR controller for it. Recommendation: (A).

## Phases

Reviews of both forks are in `docs/review/` (launcher architecture, server
architecture, QA/security, 2026-10-04). Upstream has no Rust tests, one
Android test file, and several paths that fail OPEN, so robustness comes
before features. PRs in order (S = server, L = launcher, ↑ = upstreamable):

0. **Build/test baseline**
   - S↑: test harness (`build_app()`, in-memory SQLite, router tests);
     `cargo test` + clippy in CI.
   - L↑: optional/stubbed tsnet for local builds, pinned tsnet/gomobile;
     enforcement logic extracted from `AppEnforcer` into a pure, unit-tested
     function; test that old cached policies still decode.
   - Fork plumbing (handy-only): our repo/APK URL/signing cert instead of
     upstream's in `provisioning.rs`, `system_update.rs`, deploy scripts; our
     own non-debuggable signed release APK.
1. **Fail closed** (↑, paired S+L)
   - S: policy endpoint returns an error, never a default open policy, on DB
     error/missing row; atomic command hand-out; transactional allowlist.
   - L: keep last good policy on error or decode failure; empty allowlist is
     not "unrestricted"; don't clear PIN hash on a bad response.
   - L: "pause all restrictions" requires PIN, is time-limited and reported
     to the server; Settings gated even without a PIN.
   - L: threading fixes (override PIN `apply()` off the main thread, tsnet
     connect outside the sync lock).
2. **Hardening** (↑): safe boot, debugging, factory reset, add user, modify
   accounts, date/time, VPN config, USB file transfer. Per-device switches,
   default on; debugging stays toggleable (adb is the recovery path), and
   `DISALLOW_SAFE_BOOT` only with a tested fallback for a crashing launcher.
   Schedules enforced by suspending apps, not just an overlay.
3. **Device controls** (↑, paired): camera, per-app permission policy, SMS
   switch; deny `CALL_PHONE` to other apps.
4. **Dialer** (↑, paired): `setDefaultDialerApplication` (API 34, device
   owner, no UI — test on the Jelly Star; role dialog as fallback); never hide
   `TelecomManager.getSystemDialerPackage()` (emergency fallback); contacts
   and in/out allowlists on the server (`contacts` + `device_contacts`,
   optional `call_policy` field, launcher reports a `capabilities` list).
5. **Call screening** (↑): reject non-allowlisted inbound calls, emergency
   numbers always pass; then `DISALLOW_CONFIG_DEFAULT_APPS`. Test: kiosk on,
   before first unlock after reboot.
6. **Server security** (↑): rotate session ID at login, drop sessions on
   password/2FA reset, trust `X-Forwarded-For` only from localhost, TOTP
   re-check for wipe, stream APKs instead of buffering. Keep the admin UI
   unreachable from the kid's phone (separate port/tailnet ACL).
7. **Real hardware** (Jelly Star): QR provisioning (possibly missing
   provisioning activities for Android 12+), Tailscale away from home,
   battery over a week, manual bypass checklist from `qa-security.md`.
8. **Vibb integration** via managed configuration (once Vibb for Android
   exists).

Environment notes: no KVM here, so no emulator in this sandbox; the server
builds (`cargo build` OK, 0 tests). Android needs JDK 17, SDK 36, AGP 8.13.

## Fix backlog

The running list of fixes and small follow-ups, kept up to date as items come in and are
done. Process for each batch: design note where needed, QA, implementation, code QA, fix all
findings, merge to `main`, full suite once.

### Approved, not built - the queue (kept current; checked against the code 2026-10-25)

One line per approved item that has no code yet. An item leaves this list in the commit that merges it (or the one right
after), never later. Details stay in the entries below.

Rounds and waves: `docs/plan/2026-10-25-queue-rounds.md` (2026-10-25). Combined lines are split at their own
separators, one item per bullet, so each sits in its round; the words are unchanged.

- **Launcher**
  - **In build, `launcher-glance-b`** (not a planned round):
  - **Round L1 - glance and Home layout** (`launcher-glance-c`, after `launcher-glance-b` merges):
    - RC3-8
    - six-inch F3/F4
    - compact music tile a little lower
    - **Fingerprint with the screen on, Jelly Star check** (the user, 2026-10-25, emulator: with the screen on the fingerprint unlocks only once SystemUI's pad is up; from screen-off it unlocks at once): expected in the hybrid - the glance occludes the keyguard, and Android listens for fingerprint only while the keyguard is showing and not occluded (design 59); no API lets us dismiss a secure keyguard after our own biometric check. Check on the Jelly Star (side-mounted sensor; some OEMs keep listening while occluded); if it does not work there with the screen on, one line in Kid Settings' help ("press the sensor from a dark screen, or swipe up first"), nothing more. Not a bug.
  - **Round L2 - pause end and enforcement** (`launcher-enforce`):
    - **End of a pause: end the background processes of the apps that get blocked again** (the user, 2026-10-25, emulator `dumpsys meminfo` after a pause: Immich 150 MB, Element X 122, WhatsApp 118, Fossify calendar/clock 90+88 still resident - suspending keeps the process): when `AppEnforcer.apply` re-suspends or re-hides a package at a pause's end (and at a time rule's start), call `ActivityManager.killBackgroundProcesses(pkg)` (a normal permission) for exactly those packages - never for an app allowed outside the pause (Element X with calls), never for our own or the music app; log a count. Measure before/after on the emulator (the same dumpsys). The one-hour PSS measurement of `me.vibb.launcher` is done (2026-10-25: PSS 140 → 137 MB, Views 210 → 210, AppContexts 47 → 18, Activities 2 → 2): no leak. Launcher, S.
    - VoIP small fixes: "Tilbake til samtalen" button, no SOS guess at a VoIP wake, no BlockedAppActivity flash, READ_PHONE_STATE for allowlisted apps; design 57 itself is held (design 59 hybrid).
    - Jelly Star "App blocker" hidden/suspended at enrolment (waits for the user's package dump).
  - **Round L3 - DNS: the secure-DNS bypass and the upstream line** (`dns-bypass`, launcher + server, one branch):
    - Browser "secure DNS" bypass: Chrome managed config, DoH-host block category, Firefox canary.
    - Diagnostics: Web Filter line "upstream can't be reached"
  - **Round B1 - per-app permissions** (`app-permissions`, launcher + server, design note first):
    - Camera off per app + a permissions row per catalog app in the PWA. Added 2026-10-25 (the user, Immich: "kunne tenke meg å kunne slå den på for å rydde opp etter lokal opplasting"): the row also lists the special-access app-ops a device owner can't grant - Media management (`MANAGE_MEDIA`, Immich's delete-after-upload), Modify system settings, usage access - each as "approve on the phone" with a pause-screen row that opens the exact page for that app (`Settings.ACTION_REQUEST_MANAGE_MEDIA` + the package, the `systemPageIntent` pattern of the write-settings row), never adb.
  - **Round B2 - emergency menu** (`emergency-menu`, launcher + server, mockup + design note first):
    - Configurable emergency menu
  - **Round L4 - the launcher's security Lows** (`launcher-sec-lows`; the shared line is under Server / PWA, Rounds S2 + S3).
  - **Round L5 - missed Element X calls** (`missed-voip-calls`, after the user's capture):
    - Missed Element X calls on the contact card and the phone book's badge (capture the notification shape on the Jelly Star first).
  - **Emulator session E2 - screen sizes** (the coordinator's, after L1 and M1):
    - **Screen-size matrix on emulators** (the user, 2026-10-25: "kjøre tester med emulatorer med veldig forskjellig skjermoppløsning og dimensjoner så vi kan sjekke hvordan løsningen vår skalerer på ulike telefoner"): one pass of every launcher screen (Home grid, phone book, keypad, the glance in own and android mode, the time-rule screen, Kid Settings, the pause screen, the music app's library and player) on at least four AVDs - a narrow 4" (540x1168 @ 320 dpi, 270x584 dp, the Bluefox NX1's class), the Jelly Star (480x854 @ 240, 320x569 dp), a 6.1" (1080x2400 @ 420, 411x914 dp - `six35` exists) and a 6.7" tall (1440x3120 @ 560), each at font 1.0 and 1.3, 3-button and gesture navigation; screenshots under `docs/testing/screens/sizes/`, a findings table like `2026-10-23-six-inch-android15.md`. Launcher (+ music), M; the narrow one first since it is below our 320 dp floor.
  - **Waits on the user / the Jelly Star / phase D** (no round yet):
    - **Design 38 device check, the Jelly Star half** (the emulator half passed 2026-10-25: `svc power reboot` → "is restarting" in the inbox within 2 min, `docs/testing/2026-10-25-r13-run.md`): the power menu's Power off and Restart and a flat battery on the Jelly Star - the user's check.
    - Phase D (own lock removed)
    - design 32b (one stale frame at screen-on)
    - RC3-4 (release-build setup timing)
    - Immich wallpapers
    - design 60 (hardware-bound token)
- **Music app**
  - **Round M1 - the splash and the "no network" words** (`music-splash-b`):
    - **The wordmark right under the rings on the splash** (the user, 2026-10-25: "Flytt vibb music litt høyere opp mot logoen. Men så bra ut!"): Android pins `windowSplashScreenBrandingImage` to the foot of the screen and masks the icon to a circle, so the wordmark cannot sit under the logo in the system splash. Do it in the app's own first frame instead: the system splash shows the rings alone (no branding image), and the held first frame (`MusicRoot` before `contentReady`) draws the same night ground, the rings at the splash's position and size, and "vibb music" 24 dp under them; the system's cross-fade then only reveals the wordmark. Pin the rings' position to the splash's (icon 240 dp centred, inner 160 dp) so nothing jumps. **And the rings breathe there** (the user, 2026-10-25: "burde vi ikke kanskje bruke den animerte versjonen av logoen? den som heter pust"): the held first frame plays the launcher's `splash_vibb_breathe.xml` (the same rings as vibb pi's), looping until the content is ready; the system splash itself stays static (Android caps its animated icon at 1 s). The breathing is optional (the user: "om det blir krøll å la den puste så kan vi droppe det") - any stutter, jump at the hand-over or added time means still rings; the wordmark under the rings is the fixed part. Music, S.
      **Built** (2026-10-25): the theme's `windowSplashScreenBackground` (night), `...AnimatedIcon` = `splash_vibb_mark` (the launcher's Home splash at rest, static) and `...BrandingImage` = `splash_wordmark` ("vibb" cream, "music" peach, Nunito ExtraBold as paths), both written by the new `scripts/music-splash.py` (`--selftest`, not in CI); `MainActivity.holdSplash` holds the first frame until `MusicRoot` composes with the first snapshot, at most 3 s (`logic/Splash.kt` `keepSplash`, `SplashTest`) - the platform's `OnPreDrawListener`, what `androidx.core.splashscreen` does on API 31+, so no new dependency (minSdk 34). Emulator check: emulator.md §6u steps 1 and 6-8. Fix round: the cap pinned, the first frame waits for the carousel's start tile too (`contentReady`, Q-10), NOTICE.md, `--selftest` in `music.yml`.
      **Built** (2026-10-25; design 54 "Hand-over timing (2026-10-25)"): a Spotify tile on a room is refused at once (54-a2 unbuilt); the user's wait was most likely the phone's web player - covered now too ("Kobler til…" from the tap until its first playing state, `SpotifyRenderer.starting`; code QA Q-3, the user's decision). (a) `sonos/HandOverTiming.kt`: one `VibbHandover` line per step, `+N ms` since the tap (the command reaching the service, the actor job, the description read, each SOAP answer, the share link's own steps in `playShare` ready for 54-a2, the answer on main, each settle poll, the first PLAYING poll and socket event, the now-playing update, the line's start and end); the spike comparison and what to cut once measured are in the design (the likely cut: 1 s polls instead of 5 s while our play settles/transitions; nothing cut yet). (b) "Kobler til høyttaleren…" / "Connecting to the speaker…" in the seek line's times' place from the tap until the room reports PLAYING (`SonosController.connecting` from `pending`/TRANSITIONING, `PlayerHub.State.connecting`, `logic/HandOver.kt` `timeRow`, `HandOverTest`), the room's own line winning, no new timeout; **one wording for every source**: "Spotify" can't be in a string (26c step 8, `SourceScanTest`) - the user's call if the Spotify wording should come back. Emulator check: emulator.md §6u steps 2-5 and 9-11 (the fake room with `--transition-s 4`; the web player against `fake-spotify.py`; Spotify on a room only after 54-a2). Code QA `docs/review/qa-music-r13-code.md` (0 High, 3 Medium, 7 Low, 2 Nit) and its fix round done: numbered timelines, `connecting()` tested, the privacy scan, SKIP_TO/RESTORE timelines.
    - the music app's "no network" text and the real cause in its log
  - **Round M2 - the music app's security Lows** (`music-sec-lows`; the shared line is under Server / PWA, Rounds S2 + S3).
- **Server / PWA**
  - **In build, `pwa-fixes-d`** (not a planned round):
    - **Locate: the "Answer to Update Location" row goes; a transient status instead** (the user, 2026-10-25: a stale "Found within 5 m · Answer to Update Location · 14:29" under a point from 20:41; "Er det viktig informasjon å vise der?" - no): the row shows only while an Update Location request is pending ("Asking the phone…") and for a few minutes after its answer ("Found within 5 m · 14:29"); a failed answer ("No position - the phone is offline") stays until the next stored fix; otherwise nothing - the newest point and the time row say it. Server, XS; next server round.
    - **Notifications settings: one line about re-adding the app** (2026-10-25, the user's false alarm after re-adding the PWA to the Home Screen for the splash: a new Home Screen copy is a new web app with no notification permission, while the old copy's subscription lives on at Apple - 201 on every push, no receipt): under the device list, "Added the app to the Home Screen again? Turn notifications on in the new copy and remove the old device here."; the health rule marks the old row after three unreceipted pushes as today. Server, XS; next server round.
    - **PWA shows the login page after a Tailscale outage, though the session is valid** (the user, 2026-10-25: "etter at tailscale har feilet på min tlf ... når jeg reconnecter og åpner vibb phone på nytt så ber den om innlogging. Dreper jeg pwa og starter de på nytt så er jeg logget inn igjen"): cause unknown. (1) Server: one log line whenever a request carrying a session cookie is answered with a redirect to `/login`, with the reason (no cookie / expired / epoch / source address refused by the admin listener's tailnet-or-loopback rule / re-auth) and the path - no cookie values; (2) PWA: when `reconnect.js`'s return reload or retry lands on `/login` (or a fetch gets its redirect), first ask a tiny authenticated endpoint (`GET /api/session` → 204/401) whether the session lives; if it does, reload the page instead of showing the login; (3) ask the user for the time and whether the address bar showed `/login?next=…` next time, and read the server log. Server/PWA, S. In the next server round.
  - **Round S1 - server small items** (`server-small-e`):
    - `PHONE_SERVER_URL` in `.env`
    - episode pictures on demand
    - GitHub asset digests
    - PWA tab-switch timing (Navigation Timing behind a debug switch, then prefetch).
    - Settings saves in the security log
  - **Rounds S2 + S3 - security Lows, the server half** (`server-sec-lows-a`, `-b`; the launcher half is L4, the music half M2):
    - The security reviews' Low items (server 18, launcher 16, music 9).
  - **Round S4 - F-Droid trust** (`fdroid-trust`):
    - F-Droid follow-ups: entry.jar signature
    - signer check at the first pin
    - ABI filter
  - **Scope not settled / waits on the user** (no round yet):
    - PWA i18n.
    - Home Wi-Fi as a trigger
    - Wi-Fi in Phone Setup
    - notifications phase 2 (ntfy/Matrix)
    - Zone series numbers after the user's Jelly Star day
    - developer-verification registration (nothing in the repo)

### Approved for the next fix batch
- **A last position at shutdown and at low battery** (the user, 2026-10-05, asked again 2026-10-25). **Merged 2026-10-25 (f379971e, branch `last-position`)** after code QA (`docs/review/qa-last-position-code.md`, 0 High / 3 Medium / 4 Low, all fixed): the `off` report carries the newest stored fix (`power_report.json` `off.location`, both copies; CE `power_off_location`, never a mock or a fresh fix, never under "Off"), the server stores it through the zones intake and marks `device_locations.shutdown_at` (migration 0097), Locate shows "Last position before the phone went off" with the device page's "Off since" exclusions, the `phone_off` event's params carry it (no coordinates in any text), retention clears it with the location window; at `ACTION_BATTERY_LOW` one balanced fix per episode (30 s cap, Location on, not "Off", a fix under 2 min old reused), the episode ends at BATTERY_OKAY or a discharging-not-low report. Design 38 "Last position (2026-10-25)"; emulator checks §6p 5-12 done 2026-10-25 (`docs/testing/2026-10-25-evening-run.md`: one fix per episode, the shutdown's position in the inbox, Locate's line and "Off since" after the hold); the Jelly Star half stays the user's. Ships with `server-v0.51.0` + `launcher-v0.37.0-rc.3`.

- **Shorter web push texts** (the user, 2026-10-25, queue above). **Built** (2026-10-25, branch `pwa-fixes-b`, not
  merged): every push "<name>: <fact>" / the time and at most one number ("Anna: turned on" / "07:02 · battery 64 %");
  the inbox and Activity keep the long words. Stored-words kinds keep both forms (`parent_events.push_title`/
  `push_body`, migration 0098 - 0097 is `last-position`'s), the parameter kinds are worded short where pushed
  (`parent_events::pushed`); summary and test pushes shorter too. The before/after table for every kind is
  `docs/notes/server/notifications.md` "Push wording (2026-10-25)" - **the user reads it before it ships**; open there:
  whether the PIN's place and a request's rule should come back as a word.
- **App update notices** (the user, 2026-10-25, queue above). **Built** (2026-10-25, branch `pwa-fixes-b`, not merged):
  `app_update_available` (the catalog's, no phone, one per release: key `<app id>:<tag>@<asset id>`, raised where the
  sweep and Check Now store a release over a previous one) and `app_updated` (per phone and version, from the status
  report: a higher versionCode that is the catalog's stored release), key + params like design 64; always in the inbox,
  pushed only under Notify Me About's new "App updates", off by default per parent (migration 0099, which also makes
  `parent_events.device_id` nullable by rebuilding it with its two children). Left out: `app_updated` for catalog apps
  other than Vibb One and the music app - the phone's report names no other app's installed versionCode (a launcher
  change in both dirs, not built). `docs/notes/server/notifications.md` "App update notices".
  **That follow-up built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, awaiting merge)**: `installed_apps` entries carry
  `version_code`/`version_name` (optional; the name trimmed, at most 60), stored with the list's JSON (no migration:
  `device_status.installed_apps_json` holds it), and `note_app_updates` raises `app_updated` for every catalog app
  checked for the phone by its listed code (`listed_app_updates`, `catalog_release_installed`: F-Droid by the
  versionCode, else the versionName against the tag's version - an RC spelled differently from the tag raises
  nothing). The status body is the shared contract `server/testdata/installed_apps_report.json` (code QA Q-5; the
  launcher's `SyncScheduleTest`, the server's `app_updates` tests, root CLAUDE.md's "Shared" paragraph, both CI
  filters).
- **Pull down to refresh on Devices and a phone's summary** (the user, 2026-10-25, queue above). **Built** (2026-10-25,
  branch `pwa-fixes-b`, not merged): `static/pull-refresh.js` on `devices_list.html` and `device_detail.html` only; at
  the very top a downward drag shows a small peach arrow in the title bar's area, past 70 px it turns over, the release
  spins and reloads through reconnect.js's reload in place (the new `window.handyReconnect`: the bar, the chip, the
  ping, the kept place); refused while a save is on its way, a `<details>` is open or a field holds typed text (the
  shared `handyReconnect.mayReload`). Owed: the iPhone and Android PWA by hand. `docs/notes/server/pwa.md` "Pull down
  to refresh".
- **Uploaded avatar per phone; the avatar as the push icon** (the user, 2026-10-25, queue above). **Built** (2026-10-25,
  branch `pwa-fixes-c`, not merged; code QA `qa-pwa-fixes-c-code.md`, 0 High / 1 Medium / 6 Low, all fixed): the summary's big avatar is the picker (`accept="image/*"`, no
  `capture`, so a phone offers the library and the camera), saved at once back to `#avatar`, through the contact
  photos' pipeline (square JPEG <= 512 px, no EXIF), `devices.avatar_hash` (migration 0100), `data/device_avatars/`
  in the backups; shown with the dot on every phone row; Remove goes back to the letter. Served without a session at
  `/phone-avatars/<hash>` (an unguessable content hash, only while a phone names it, behind the admin gate) because a
  push's icon is fetched for the service worker without the cookie; the push carries it as `data.icon` (legacy path;
  Safari shows the app icon anyway). Never sent to a phone. `docs/notes/server/pwa.md` "A phone's own picture",
  `notifications.md` "The push icon".
- **PWA accent: field focus A, primary button 3** (the user, 2026-10-25, queue above). **Built** (2026-10-25, branch
  `pwa-fixes-c`, not merged; code QA `qa-pwa-fixes-c-code.md`, all fixed; the fix round added the borderless fields' underline and the navigation keys): a focused field's own border in `--tint` 1.5 px and a white fill (dark:
  peach border, night fill), no ring for a tap or click - `static/focus-mode.js` marks `<html>` from a press until the
  next Tab, since a browser counts a tapped text field as `:focus-visible` too; the keyboard keeps the ring. Filled
  buttons `--tint-fill` (#E8895E / dark #F0A884) with night text; `--strong` gone; `style.css?v=54`. Chromium + WebKit
  pass on the pages the round touched (not every page yet). `docs/notes/server/pwa.md` "Field focus and the filled
  button".

- **Security and privacy reviews 2026-10-21** (asked for by the user): `docs/review/security-review-2026-10-21-server.md`
  (0 High / 3 Medium / 18 Low), `-launcher.md` (0 High / 5 Medium / 16 Low; Tailscale/tsnet verdict: keep it as is,
  dormant without an auth key, three small hardenings in S-L10), `-music.md` (0 High / 1 Medium / 9 Low). Proposed
  order: server M1 (push subscriptions survive a password change / 2FA reset), M2 (backup snapshots never pruned),
  launcher P-M1 (the fallback policy drops `locationPolicy`), music S-M1 (a planted SSDP speaker: LOCATION must equal
  the sender, LAN only, `RINCON_` uids, the cache tied to the network and pruned); then launcher S-M1 (cleartext,
  M11) and S-M2 (filter state in the report); then the Lows. Owed by the user: server M3 (Storytel credentials to the
  phone in plaintext), launcher S-M3 (browser "secure DNS" bypass) and S-M4 (a VoIP call over the PIN lock - the user
  doubts the chats are reachable; device check: lock, ring the phone from another Element X account, answer, try Back
  and the call screen's minimise arrow), music P-L4 (done 2026-10-21: the real first name in the Spotify fixtures is now "Forelder"; "Ella" is not a real name).
  **Server M1 and M2 done on branch `pwa-fixes-a` (2026-10-25, not merged yet):** M1 - Account's new password and
  "Reset Two-Factor Login" delete the account's push subscriptions (as Sign Out Everywhere), and each subscription
  keeps its session's `session_epoch` (migration 0095) so `send_due` deletes one from a signed-out session first
  (`push::forget_stale`). M2 - `backup_sync.sh` prunes the drive's `snapshots/` to the server's counts ("Backups to
  keep" `backup-*.zip`, 3 `pre-migrate-*.db`, regular files at the top only); the Backups page and DEPLOY say a backup
  keeps data past the retention windows. No encryption of the drive's copies (out of scope).
  **S-M4 checked on the emulator 2026-10-21 (Element X): no way to the chats** - Back does nothing in
  `ElementCallActivity`, the minimise arrow does nothing, Home and Recents land on the lock. **WhatsApp (same day):
  its call screen's arrow leads straight into the chats over the lock -> S-M4 stays Medium**; Element X may add such
  an arrow any day. Needs a design: either (E) the call stays behind the lock after the answer, the lock showing an
  in-call bar (app, time, hang up through the CallStyle hang-up action) - no setup, audio only until the PIN - or (D)
  only the call's own activity over the lock, seen through usage events (`PACKAGE_USAGE_STATS` app-op, adb at
  provisioning like the listener and the roles), anything else re-fronts the lock with "Tilbake til samtalen"; E as
  D's fallback. Measured: WhatsApp's ongoing call is CallStyle (callType 2) with `hangUpIntent` (a broadcast), call
  screen `com.whatsapp/.calling.ui.VoipActivityV2`; Element X's ongoing call is a plain notification on
  `call_foreground_service_channel`, content intent only - no hang-up anywhere: Element X source (main 2a8ce3ef,
  2026-10-21) exports nothing in `features/call` (`ElementCallActivity` no filter, `CallForegroundService`,
  `DeclineCallBroadcastReceiver` exported=false). `ElementCallActivity` has its own task (`taskAffinity
  io.element.android.features.call`, singleTask), so its Back can't reach the chats - why "Tilbake til samtalen" is
  safe there. **Element X hangs up when it can't enter PiP** (`PictureInPicturePresenter`: "Cannot enter PiP mode,
  hangup the call"; `onUserLeaveHint` -> PiP, auto-enter on S+) and when a PiP exits while the activity isn't
  started; PiP is refused in lock task (why design 17 starts no lock task during a kiosk-off VoIP call) - so any lock
  start over a live call must carry `FLAG_ACTIVITY_NO_USER_ACTION` (no leave hint), and the kiosk-on case needs a check.
  Usage access (D): no device-owner exemption for `queryEvents` (AOSP `UsageStatsService.hasQueryPermission`: the
  app-op or `PACKAGE_USAGE_STATS` only; the DO exemption is only `hasObserverPermission`, package-level timers), the DO
  can't grant an app-op - the parent toggles Usage access once on the phone (PIN-gated Settings row, during a pause)
  or adb; check whether Android's "restricted settings" grey it out for a sideloaded launcher.
  **Superseded the same day: usage access is mandatory** (the user: "D samtykke må gis uansett, det er en integral del av at voip skal funke") - without it no VoIP app rings over the lock at all (no E fallback, no Element X exception); with it, the D rule for every app. Design 57 (`docs/design/57-voip-call-screen-over-lock.md`, in progress). The earlier version:
  **Measured 2026-10-21 (the user's emulator):** WhatsApp's call-screen arrow starts `com.whatsapp/.home.ui.HomeActivity`
  in another task (t21 vs the call's t20) - a new activity, so design 57 sees it; Element X rings in
  `io.element.android.features.call.impl.ui.IncomingCallActivity` and talks in `...ui.ElementCallActivity`, both in the
  call task (t25), separate from its main screens. WhatsApp rings and talks in the same `VoipActivityV2` instance (one
  record through ring and call, a new task per call: t30, t33, t35), so answering on WhatsApp's own ring screen raises
  no new RESUMED event - design 57 must trust an activity our own full-screen-intent send opened through the answer
  (it is the call screen too), without a second tap. Manifest `showWhenLocked` (`ActivityInfo.flags & 0x800000`,
  AOSP `ActivityInfo.java:677`, readable by any app) is **not** a usable shortcut: Element X sets it in code
  (`ElementCallActivity.kt:100`, `IncomingCallActivity.kt:93`), and WhatsApp's only manifest `showWhenLocked` activity is
  `com.whatsapp.voicemessaging.productinfra.VoiceMessagingActivity` (taskAffinity `:voice`), not `VoipActivityV2` -
  trusting the flag would let a non-call screen over the lock during a call. More (same day): Back on a WhatsApp
  *video* call over the lock (kiosk off) -> our lock on top, but WhatsApp's video stays visible frozen (PiP over the
  lock); Decline on WhatsApp's own ring screen -> our lock, no chats; `appops set ... GET_USAGE_STATS allow` reads
  back `default` at once on today's launcher (QA 57-5 confirmed: a prep release must declare the permission first).
  Retest of Back on a WhatsApp video call: nothing happened, the call and camera ran on, top stayed `VoipActivityV2`
  (t60) - Back is a no-op there; the earlier frozen frame was a PiP over the lock. (The design-57 revision for
  qa-57-revision-check landed in commit 9e88b6ab, whose message names only the PLAN line.)
  Step-1 items measured the same evening: (3) Element X, unlocked, kiosk off, call screen up: a plain
  `am start -c HOME -p me.vibb.launcher` and the `--activity-no-user-action` one both just bring our Home to the front
  ("its current task has been brought to the front") - no PiP either way, the call lives on in the background (its
  notices still posted, audio ok); so the flag's effect on Element's PiP is unmeasured but no PiP happens on our
  start. (1) WhatsApp *voice* call over the lock, Back: our lock comes up, the call goes on, and today's reopen brings
  the call screen back - no chats.
  **The user's rule (2026-10-21): D is the condition.** Another app's call screen comes over the lock only while
  usage access is granted, and then only that call screen (the activity that came up for the call; any other
  activity resumed -> the lock again, with `FLAG_ACTIVITY_NO_USER_ACTION`, "Tilbake til samtalen"). Without usage
  access: E (the call stays behind the lock, a call bar with "Legg på" from the CallStyle hang-up action, else "Lås
  opp for å legge på"). Element X keeps today's behaviour with or without it (its call screen is isolated and it hangs
  up without PiP), as a named exception by package + pinned signer like Fossify Clock. Usage events: read only during
  a VoIP call over the lock, the last seconds, only the call app's package, memory only, never logged/stored/sent
  (the report says granted yes/no), one file, a source-scan test.
- **VoIP over the lock, small launcher fixes** (the user's emulator, 2026-10-21, approved): (1) the lock resumed
  during an IN_CALL sometimes finds nothing to reopen (`No VoIP call screen to bring back` at 16:35:17, the kid then
  needs the PIN to get back to their own call) - log which case it was (no IN_CALL notice with a content intent, or the
  send threw) and show a "Tilbake til samtalen" button on the lock whenever a VoIP call is IN_CALL and the reopen
  didn't bring it up; (2) a VoIP ring's own screen coming up right after the wake's screen-on is logged as "probably
  Emergency SOS" (16:37:12) - the SOS guess must not fire for the VoIP wake/FSI; (3) Home pressed during a VoIP call
  with the kiosk off flashes BlockedAppActivity (the app-block bit is on while the call is pinned) before the lock.
  Long list, not now: the caller's name/picture on the ring card from CallStyle's `EXTRA_CALL_PERSON`, memory only.
  (4) **READ_PHONE_STATE for allowed apps** (the user, 2026-10-21, option 1): while calls are managed the third-party
  CALL_PHONE/ANSWER_PHONE_CALLS denial greys the whole Phone group in Settings ("not allowed by admin"), so WhatsApp
  can't get READ_PHONE_STATE (it asks for it to know a carrier call is on). Grant READ_PHONE_STATE by policy to every
  allowlisted app that requests it, whenever the denial is set; CALL_PHONE/ANSWER_PHONE_CALLS stay denied. Unmanaged
  calls: back to the phone's own choice. Test workaround: `adb shell pm grant com.whatsapp
  android.permission.READ_PHONE_STATE`.
- **Browser "secure DNS" bypass (launcher S-M3, approved 2026-10-21, the user's yes to all three):** (1) Chrome's
  managed config `DnsOverHttpsMode=off` through `setApplicationRestrictions` for an allowlisted Chrome (and any
  Chromium-based browser that reads Chrome's keys - check per browser); (2) a built-in DoH-host block category in the
  phone's DNS filter (`dns.google`, `cloudflare-dns.com`, `mozilla.cloudflare-dns.com`, `dns.quad9.net`, `doh.*`...),
  never the launcher's own upstreams (protected sockets, IP literals); (3) Firefox's canary `use-application-dns.net`
  answered NXDOMAIN. Later, if wanted: route the public resolvers' IPs into the tunnel and drop 443 to them.
  **Cromite checked (uazo/cromite 3724efc, 2026-09-19):** a poor fit for a filtered phone - `DoH-improvements.patch`
  turns "automatic" into strict secure DoH and fills an empty template with `https://doh.opendns.com/dns-query` (so
  out of the box it never asks our DNS), and stubs out Chromium's "disable DoH when managed / parental controls"
  (`ShouldDisableDohForManaged`/`...ForParentalControls` return false); `Ignore-enterprise-policies-for-secure-DNS.patch`
  reports the device as never owned; `Disable-Android-AppRestrictions.patch` drops Chrome's managed-config provider
  behind a flag that defaults off (a kid can't be held to it either). With fix (2) blocking doh.opendns.com, Cromite
  resolves nothing until its Secure DNS is set Off by hand; a custom template by IP literal still bypasses names.
- **Storytel login to the phone (server review M3, approved 2026-10-21 - design 58):** the password never leaves
  the server; the phone asks for a JWT through the launcher bridge when it downloads/streams/syncs (like Spotify's token
  courier), the server shares its cached JWT (a login only when its JWT runs low, and only when a phone downloads or plays a book on a room; backoff and a server-wide cap after failures - design 58). Checked
  debba/storytel-player (2ca1e2d, 2026-10-18): its password mode is ours (re-login on expiry, the JWT's own exp-iat,
  30 min fallback; one retry on a 401); its SSO mode (Google/Apple) scrapes Storytel's web Firebase refresh token from
  the site's IndexedDB in an Electron window and refreshes ID tokens at `securetoken.googleapis.com` with Storytel's web
  API key (Bearer on api.storytel.net, the `storytel_session` cookie as the *.action token). Long list: a refresh-token
  login instead of a stored password - needs a browser login to capture it (fragile, Storytel's web internals), and a
  check that vibb's endpoints (bookshelf, asset mint, bookmarks) take the Firebase ID token. Small, any time: take the
  JWT's lifetime from its own exp-iat instead of a fixed hour.
  **Status 2026-10-22:** steps 1 and 4 merged (ec39a8b6) and released alone as `server-v0.35.0` for the measurement
  (§2.7: the user saves the Storytel login, then reads two `storytel: logged in` lines an hour apart; that decides
  Q-A before step 2). No launcher/music tag yet. Design 57's held prep release would now be server 0.36.0.
  **Measured 2026-10-22** (the user's server, two logins two minutes apart): `life_s=86400, left_at_login_s=86400,
  claims=yes, payload_has_email=no` - a fresh 24-hour JWT per login, no e-mail inside. **Q-A = branch (a)**: steps 2
  and 3 go on as written; with a 24 h life the server logs in about once a day while books are fetched.
- **The server's exposure (the user, 2026-10-21):** a public HTTPS endpoint for the phones, the PWA over the tailnet
  only. So the phone-intake budgets (server L1/L3/L4) and launcher S-M1 (refuse a non-https server URL) matter.
- **Recents reachable from the lock (privacy, high)** (the user, 2026-10-22): on today's lock a swipe up from the bottom
  shows Recents, where app thumbnails (WhatsApp chats) can be read. `featuresWhileLocked` drops OVERVIEW while LOCKED
  (`LockTaskHelpers.kt:127-133`; kiosk off: KEYGUARD|GLOBAL_ACTIONS|SYSTEM_INFO), so this should not happen - needs the
  user's repro (kiosk on/off, navigation mode, `dumpsys activity activities`/`lock-task`). Likely the same root as
  "stuck in Recents" below (quickstep's RecentsActivity, our Home not the recents provider). The design 59 hybrid
  should close it as a platform rule (nothing non-showWhenLocked is visible over a locked keyguard); its prototype
  script checks it. Today's lock gets a fix if the hybrid is more than a release away.
  The user's repro (emulator, kiosk on, LOCKED, features 113): the square key and a swipe up both open quickstep's
  Recents; a card tap flashes the app briefly, then our lock re-fronts (no PIN bypass seen). Investigation:
  `docs/review/recents-over-lock-2026-10-22.md`.
- **Stuck in Recents after an unlock** (the user's emulator, 2026-10-21 ~16:55, not reproduced, no lock log kept): the
  user had just unlocked to grant WhatsApp its call permissions; then "app not available", the lock, and after the
  unlock quickstep's empty RecentsActivity on top with lock task LOCKED, HOME resolving to ours but `am start -c HOME`
  refused with 101 (lock-task violation - the HOME feature off, as while the PIN lock is LOCKED). Suspect: the lock's
  mode or the chrome left LOCKED after the unlock (permission controller dialogs inside lock task). Try to reproduce
  (WhatsApp's first-run permission flow over the PIN lock, kiosk on); keep `logcat | grep -E "PinLock|LockTask"`.
- **PWA: a pause or "Lock again" from the PWA shows only after a manual reload** (the user, 2026-10-21): the phone
  acts at once (SSE), the page doesn't follow. Load `auto-reload.js` (3 s, design 31's pattern, scroll kept) while a
  `start_pause`/`end_windows` command is unanswered or its window isn't in the newest status yet, at most ~2 min.
  **Done on branch `pwa-fixes-a` (2026-10-25, not merged yet):** the summary loads `auto-reload.js` while
  `window_relock::waiting_for_phone` holds (the newer of the two pressed under 2 min ago, not cancelled or refused,
  unanswered or its effect not in the newest report).
  Code QA `docs/review/qa-pwa-fixes-a-code.md` (0 High / 2 Medium / 5 Low / 1 Nit): Q-1..Q-8 fixed in the fix round
  on the same branch (2026-10-25).
- **Log out removes this browser's push subscription** (the user, 2026-10-25: "Klarer vi å fjerne push subscriptions
  ved logout?"; main's PLAN queue line, Server / PWA). **Done on branch `pwa-fixes-a` (fix round, 2026-10-25):** the
  Sign Out form's hidden `endpoint`, filled and unsubscribed by `register-sw.js` (at most 300 ms), and `auth::logout`
  deletes that row when it is the signed-in parent's; without JS or a subscription, as before.

- **No network after the VPN (re)starts** (the user's emulator, 2026-10-19, launcher 0.29.0 release): every upstream
  socket of design 36's DNS engine was refused by `protect()` - Android's `Socket()` has no descriptor until a bind -,
  so the VPN answered no query and the launcher's own sync died with it. Reproduced on handy36 with the 0.29.0 APK
  from the first VPN start (enrolment) and after a reboot; fixed on branch `vpn-restart-fix` (`protectedSocket`: bind,
  then protect; `ProtectedSocketTest`; emulator.md §6m step 8 - enrolment, pause on/off, all resolve). Code QA
  `docs/review/qa-vpn-protect-code.md` (0 High / 0 Medium / 3 Low, all fixed: `Attempt.Unprotected` never counts as a
  DoT block); **merged into `main` 2026-10-19, goes out with launcher-v0.29.1.**
- **Play/pause flicker on the lock's music tile (and to check: the music app's player)** (the user, 2026-10-20, a
  19 s screen recording of the PIN lock's glance page, Spotify "80s stemning": "widget (og inne i appen) hopper litt
  frem og tilbake ofte når jeg toggler play/pause"). Frame analysis (ffmpeg scene changes + pixel diffs): each of the
  six taps flips the icon once, 40 ms after the press ripple, and the progress bar moves only while the icon says
  playing - no spontaneous flip back in this clip. What does show: about 0.8 s after a tap the WHOLE card is redrawn
  (a faint whole-card diff at 6.22 s and 17.06 s), not just the button. Cause in the code: every session callback
  (`MusicLockPanel.changed()` -> `GlancePanel.render()` -> `renderNow()`) rebinds the rows and runs `fitNow()`, which
  measures by setting the live clock's text size big, then small, and `lockGlanceMore`'s text, then `glanceFit` picks
  the tile size again - a full page re-layout per callback, two or three callbacks per tap (playback state, position,
  metadata). Fix: a session change renders the tile alone (`MusicLockPanel.render()`), and the page's fit runs only
  when `tileUp` or the rows changed; the fit measures on detached/measure-only copies, never by mutating the live
  clock; coalesce callbacks within one frame (`Choreographer`/a posted render). Then the same recording method on the
  music app's player screen (the user saw it there too; Compose, `isPlaying` from the controller) before touching it.
  Launcher round; the reference behaviour is vibb's player.
- **Diagnostics from the same incident:** (1) the music app logs nothing of its own in `onError`; Media3 prints a
  fixed "UnknownHostException (no network)" for any name failure - log the real cause (class + message) and the error
  code; (2) "Kan ikke spilles nå" shows for a name/network failure - say "no network" instead when that is the cause;
  (3) Web Filter's status line should say "upstream DNS can't be reached" while every upstream attempt fails (the
  status today only distinguishes custom vs backup), so the parent sees the cause before the music app does.

- **"Install now" for the launcher's own update** (the user, 2026-10-19: "hadde egentlig vært fint om det var
  mulig å force update for foreldrene, inne på launcherens setting i app tabben i server pwa"): the launcher's page
  under Apps (and the phone's "Play and kiosk" card) gets a per-phone "Install now" that queues a `device_commands`
  kind `install_update`; the phone then installs at the next quiet screen-off (>= 30 s, no call, no emergency)
  with the release Check Now found. The user's decisions (2026-10-19): one tap = the install happens at once, "ikke
  noe åpent tillatelsesvindu"; the download goes ahead whatever the connection setting says ("det er et valg
  forelderen tar"); the button lives only on the launcher's own page under Apps; no notification when it is done
  ("dette ser jeg jo live fra pwa"). **Design 37** (`docs/design/37-install-now.md`, revised ae03013d; design QA
  `docs/design/qa-37-design.md` approved with the listed changes, all folded into the one implementation step).
  **Merged into `main` 2026-10-19 (e3060383)** after code QA (`docs/review/qa-37-code.md`, 0 High / 2 Medium / 3
  Low, all fixed) - server 1047, launcher 1375 green on main. Not released; device checks owed (the design note's
  Implementation status: the daytime install with the fence off, the download on real mobile data, the 2-minute call
  re-check, "Done" over tsnet after the next unlock, the iPhone's poll).
- **Phone off / phone on notifications** (the user, 2026-10-19: "notification når tlf slås av/på ... Av og på varsel
  basert på oppstart/omstart og action_shutdown høres bra ut!"): `parent_events` kinds `phone_off` (from
  `ACTION_SHUTDOWN`, write-ahead in DE prefs, a best-effort POST, else reported at the next boot) and `phone_on`
  (boot), with the battery at shutdown as the probable reason; no server-side "lost contact" timer. **Design 38**
  (`docs/design/38-phone-off-on.md`, revised 671f5c6a: the `off` resent with the next boot's `on`, a 5-minute hold on
  the server so a restart is one push, one shutdown receiver; design QA `docs/design/qa-38-design.md` approved with
  the listed changes, all in the note). **Merged into `main` 2026-10-19 (3303e4ea)** after code QA (`docs/review/qa-38-code.md`, 0 High / 1 Medium / 2 Low,
  all fixed; migration 0080). Device checks owed: §6p on the emulator; the Jelly Star's power menu Power off and
  Restart, a real flat battery, the shutdown property, Restart against the 5-minute hold on Wi-Fi and mobile data,
  the shutdown POST over tsnet within 3 s. Replaces design 35's dropped "offline" alert.

- **Our own DNS server in the Web Filter** (the user, 2026-10-15: "det må vi fikse"): Settings ›
  Web Filter › DNS Server offers only Cloudflare and Quad9 (`set_upstream` refuses anything else;
  the launcher's `DnsFilterEngine` knows only those two, `1.1.1.1`/`cloudflare-dns.com` and
  `9.9.9.9`/`dns.quad9.net`, over DNS-over-TLS). Add "Custom", aimed at the user's own AdGuard Home on
  a public address (asked 2026-10-15) with a per-phone or per-family key: **DNS-over-HTTPS** first
  (`https://dns.example/dns-query/<client-id>` - AdGuard Home's ClientID, with "Allowed clients" set to
  the ids; the id travels inside TLS, so it works as a simple secret), DNS-over-TLS second (host name,
  optionally `<client-id>.dns.example` - the id is in the SNI, readable on the wire). The address with its
  id is a secret: write-only in the PWA like the Tailscale key, never logged, left out of backups'
  plain text where possible. The phone resolves the DoH/DoT host itself through the protected socket
  (bootstrap via the system's resolver or a stored IP), checks the certificate by the system's CAs, and
  falls back to Cloudflare - never to unfiltered or broken look-ups - when the custom server fails or an
  older launcher doesn't know "custom". Crosses the API (the policy's upstream fields,
  `policy_json_keys_snapshot`/`PolicyResponseCompatTest`, both directories in one commit). Open
  question: plain DNS to a server on the home network or the tailnet (a tailnet address isn't reachable
  from the VPN's own socket; tsnet is inside the launcher). A short design note first. Approved
  2026-10-15; the user confirmed the same day that DoH/DoT with a client id to their own AdGuard Home
  is a must - and (2026-10-18) **any** DNS server: a plain DoH URL (`https://dns.example/dns-query`, with or
  without a client id in the path), a DoT host name, or a plain IP over DoT; the field takes what the parent has,
  the server works out the kind and validates it. **Design 36** (`docs/design/36-custom-dns.md`, design QA done,
  revised after the user's decisions 2026-10-18): a second field "Backup server" the parent chooses (prefilled
  `1.1.1.3`, required under Custom) instead of a hard-coded fallback; any address allowed (LAN, tailnet, `*.ts.net`),
  the phone connects directly over its own network, never through tsnet. Step 1 (server + wire) merged 2026-10-18
  after code QA (`docs/review/qa-36-s1-code.md`); step 2 (launcher) merged 2026-10-18 after code QA
  (`docs/review/qa-36-s2-code.md`); step 3 (DoH on 443 when 853 is blocked, the bootstrap's disk cache, the backup prefilled as
  `https://1.1.1.3/dns-query`) merged 2026-10-18 after code QA (`docs/review/qa-36-s3-code.md`). All three steps go
  out with server-v0.27.0 and launcher-v0.28.0 (tagged 2026-10-19). Device checks owed: a real
  DoH/DoT server by name and IP, a home-network server on and off the home Wi-Fi, a network blocking 853, AdGuard
  Home's REFUSED (the design note's step 2 status).

- **Missed Element X calls on the contact card** (the user, 2026-10-15): the contact card, the phone book's badge
  and our missed-call notification count only the system call log, so a missed Element X (VoIP) call shows only as
  Element X's own unread badge. Idea: the listener's Element X reader (design 15: it already matches a DM's
  `sender_person` key to a phone-book contact's Matrix ID, reading no text or name) also recognises Element X's
  missed-call notification and counts it for that contact, cleared when the sheet opens, as a phone call's. Unknown:
  the shape of Element X's missed-call notification (category, channel, whether the caller's person is on it) - capture
  it on the Jelly Star first, then a short design note; launcher only. Approved 2026-10-15.

- **Design 59, Android's own lock screen (assessment, 2026-10-21):** recommends the hybrid - a real Android PIN as
  the gate, our glance page as a `showWhenLocked` face over it, Android's lock-screen notifications off
  (`KEYGUARD_DISABLE_SECURE_NOTIFICATIONS`), "Lås opp" -> Android's PIN pad; prototype behind a debug switch first. It
  would make design 57 unnecessary, so **design 57's prep release (branch `worktree-agent-a689b601f638554b8`) is held
  unmerged** until the user decides (it would ask parents for usage access). Five questions to the user (note §10).
  Its code QA (`docs/review/qa-57-prep-code.md`, on that branch, `a8750fb6`): 0 High / 0 Medium / 3 Low / 5 Nit,
  mutations 21/25 - the Lows (Android 14's unfixable Attention row, the Settings row can't revoke, three untested
  paths) wait for the fix round if the branch is ever merged.
  The user's answers (2026-10-22): (1) no Locate/ring/wipe before the first unlock is accepted; make the "type the PIN
  to start the phone" state clear (Android's own restart prompt, plus a device-owner lock-screen line via
  `setDeviceOwnerLockScreenInfo` if it shows BFU [device]). (2) (b): the server keeps the kid's PIN reversibly
  (encrypted, key outside the database; the PWA shows it after re-entering the parent password), so no "Foreldrekode"
  on the lock for now: the parent types the kid's PIN, then the parent code in Kid Settings as today ("åpen for
  begge" - (a) can come later). (4) Android's PIN pad is fine; fingerprint allowed (the parent enrolls in a window).
  (5) not decided yet - before the hybrid reaches a kid's phone; `ClockReader` is tied to Fossify, so another clock
  means adapting it. (3) (2026-10-22) the new PIN goes to the phone as a one-shot command now, deleted on the receipt;
  no wait for design 60. (5) keep Fossify for now; decide before phase C reaches a kid's phone.
  **Phasing (agreed 2026-10-22):** A - the small fixes, two rounds in parallel: the music app (favourites C, sheets
  swipe-down, drag-down player -> music-v0.5.0) and the launcher (the lock glance items, touch sounds ->
  launcher-v0.35.0 with the PIN digit fix); meanwhile the user's Storytel measurement, the Jelly Star clock check and
  the BFU blocked-call check. B - the hybrid prototype behind a debug switch on the emulator (59 §9 step ii) and the
  Keystore wrap (+ design 58 step 2 once Q-A is in). C - design 59 written up with QA, the per-phone switch, the
  server-kept kid PIN, the PIN command, the migration release (the user's Jelly Star first); 58 steps 3 and 5 in
  parallel. D - the deletion round one release after the last phone migrated; design 57 is not merged (the VoIP small
  fixes that still apply go here); then design 60. The security-review items fill free slots.
- **Android's lock is the destination (the user, 2026-10-22):** "our lock and Android's lock" exist side by side only
  for the test phase. Once the RC proves Android's lock works as intended, every phone moves to it ("steam roll
  everything over"), and the own-lock option is removed from the PWA and everywhere else - the per-phone switch is
  temporary; phase D deletes the switch, today's lock code and its texts, not only the lock code.
- **PWA identity, direction D chosen (the user, 2026-10-22; mockup https://claude.ai/artifact/PKc1u9Qo3wdxKUk1Gkh6iX,
  artboard "D"):** B's palette and look (the small rings logo tile + "vibb" wordmark at the top of Devices, peach-dark
  accent #A64B25, B's peach initial avatar, Nunito headings) without B's black welcome card, plus C's floating rounded
  tab bar with the peach pill on every page. Scope (agreed): colour tokens (light and dark), bundled Nunito, the tab
  bar partial, the Devices header, the PWA icons/manifest as the vibb rings; then a small round for an uploaded avatar (the user, 2026-10-22: change it by tapping the big avatar on the phone's page - a picker opens (photo library/camera on a phone), saved at once, plus "remove picture"; works without JS via a plain form; the avatar keeps its status dot)
  per phone (reuse `photos.rs`: resize, EXIF stripped). No big redesign. After the Android-lock RC.
  Implemented 2026-10-22 on branch `pwa-identity-d` (merged; `docs/notes/server/pwa.md`, "The vibb identity",
  browser pass `docs/testing/2026-10-22-pwa-identity-pass.md`); the uploaded avatar round is still to do.
- **Design 59 RC status (2026-10-22):** steps 1-4 merged (server 0.37.0: e8c9013b, f51d9901; launcher: 2a727685,
  f4972ced), each with code QA and its fixes; the end-to-end emulator run `docs/testing/2026-10-22-d59-rc-run.md`
  passed - nothing past the PIN, Recents refused on Android's lock in every way (the leak reproduced on today's lock
  first). Tagged `server-v0.37.0` and `launcher-v0.36.0-rc.1` for the user's emulator; VoIP (Element X, WhatsApp) over
  the lock, the notice wake and the Jelly Star checks (§9) are the user's. Small findings from the run, not blocking:
  F1 (today's lock only: a Recents press leaves a stale BlockedAppActivity that blocks app starts in a later pause until
  Back - goes away with Android's lock; **seen again 2026-10-23 without any Child PIN**, screen-capture run and its code
  QA `qa-screen-capture-code.md` tail: ~14 s after a PWA pause with kiosk on and Overview opened earlier, `wm_finish_activity
  ... HomeActivity, clear-task-all`, then `BlockedAppActivity` "not available right now" over the stock launcher, lock task
  `LOCKED` with `mLockTaskPackages u0:[]`, the task `type=home` not `type=recents`; one Back ends it. Open: why the pause's
  kiosk-off pass finishes our Home, and the "goes away with Android's lock" claim); F2 (ending a pause while the PIN pad is open closes the pad; tap "Lås opp"
  again); F3 (after a reboot the stock Pixel launcher shows ~10 s before our Home on the debug build - fix on branch
  `fix-f3-boot-cover`, design 59 "F3": android mode implies the boot cover, armed also at the locked boot, and it
  waits for our Home; code QA `qa-f3-boot-cover-code.md` fixed (F3C-1..7); the F3 emulator run
  `docs/testing/2026-10-22-f3-run.md` (graceful PASS, `adb reboot`/crash FAIL, B1-B6) fixed in fix round 2 - the
  locked boot decides at once with an alarm retry, one HOME PPA of ours once unlocked, initRest's tail posted; its
  code QA `qa-f3-round2-code.md` fixed in fix round 3 (F3R-1..7: the cover in front starts Home in a call too, the
  locked boot starts it after every boot, the chooser's cause still unknown - a device recipe in the design); the
  server's boot-cover note should gate on the reported `wanted` (F3C-3, a server brief); a second emulator run and the
  release-build timing on the Jelly Star still owed); F4 (design §8 step 15's `get-disabled` expectation is wrong: no Child PIN leaves
  Android's swipe lock, as always); F5 (PWA: "Set" and "Send again" shown together for ~2 s after a new PIN lands).
  The "Lås opp" missing-answer recovery (~2 s) could not be provoked on the emulator - a device check.
- **PWA: the night band on Devices only (the user, 2026-10-22, mockup artboard "E"):** the home tab's top - from the
  very top of the phone screen (status bar area, `theme-color` night on that page) through the 44 px title bar - is
  the night ground `#0C0C14` with the brand tile lifted to `#1B1B28`, "vibb" in white, + and bell in peach; the
  heading "Devices" and everything below stay as D. Only Devices ("den skal føles mer som forside"); the other tabs
  keep the grey top. Server only, small; next server round. **Done 2026-10-22** (branch `pwa-night-band`, merged a1be85d1;
  `docs/notes/server/pwa.md`, "Devices' night band").
- **Several parent accounts (the user, 2026-10-22: "enkelt opprette multi-admins, mor og far, med egen konto"):** build
  design 62 (`docs/design/62-parent-accounts-assessment.md`) in its simple form. Multi-family (design 63) is parked:
  "vi dropper mer tanker om multifamilie". Steps 1-2 (the latent bugs, 0087, the guarded self-service writes; Name,
  Parents, Add with the one-time password, Withdraw) merged; steps 3-5 (Reset/Remove, "by <name>", per-parent Notify
  Me About and inbox reads, migration 0088) built on `design-62-step345`, awaiting code QA; all five ship in one
  `server-v*`.
- Settings saves in the security log (design 62's old step 6, qa-62-design Q62-13): one admin middleware writing a coalesced `settings_saved` event (actor, matched route + ids, merged per page within 10 min) - first decide what counts as saved (a 200 can be a refusal) and keep `/music` in; S.
- **Android mode, kiosk off: the glance's own lock task vs a helper app (rc.2 run R2):** an unlock on SystemUI's pad
  into a lock-task helper (DeskClock) finishes it, because `lockLeave` stops lock task from the root and no API ends
  the glance's lock task without clearing the helper's task. Decision 2026-10-22: as the face with the kiosk off and
  Android locked, the glance takes no lock task of its own (Android's locked keyguard refuses app starts and Recents;
  the shade is the keyguard's locked shade). Launcher, S; next 59b round, with an emulator check of the shade/QS over
  the glance in that mode.
- **Own mode: a cold Emergency SOS start can still be covered** (rc.2 run R1, 1 of 3; pre-existing 25b-A trade-off with
  the 700 ms pause re-front for Recents). Not fixed on purpose: own mode goes away in phase D.
- **rc.2 re-check notes (2026-10-23, `docs/testing/2026-10-22-rc2-recheck.md`):** N1 (Low) the pause-based SOS mark
  logs `probably Emergency SOS` at most android-mode wakes (a 2-5 ms pause/resume at screen-on; the resume clears it,
  no wrong yield) - a misleading field-log line, make the mark log only when it holds past the resume; N2 (Low, look)
  after a `svc power reboot` in android mode SystemUI didn't re-apply its wallpaper colour overlays
  (`mNeedsOverlayCreation=true` 5+ min; the pad in Android's default palette) until a later reboot - cause unknown,
  check on the Jelly Star; the scratch `pwa_setup.py`'s hardening regex misses (turns restrictions off) - fix before
  the next run.
- **Design 59d round (2026-10-23, the user: "fiks gjerne opp i alt nå så tester jeg hybriden vår på emu en gang
  til"):** steps B, C, E, F, G, H and N1 in one round (`59d-android-mode-inventory.md` §8). The user's answers: Q1 (H)
  Home after "Lås opp" as design 32 decision 2, except while a call is live (telephony or VoIP) - then no Home start,
  the call's UI stays; Q2 (E) no `lockNow` at the notice wake's end in android mode. Q3 stays open for phase D. Design
  QA on §8 first, then one implementer and one code QA, then `launcher-v0.36.0-rc.3` for the user's emulator. The
  plain lock with the small clock forced was re-measured first (`proto-plain-lock` 13d32d7d, 59c §7): Android already
  uses the small clock whenever a row or a media session is on the lock, so the room is unchanged - one row at 240
  dpi, no media player; the hybrid stays. Round 1 built (branch `d59d-round1`, 59d §8.10): C, N1, H, B (ASK; the page
  under the pad, hidden only at the leave; Home unanimated on its own ground), E, and K (added by the user: Android's
  lock wallpaper as the face = the glance's ground with its scrim). **G not built**: the mid-round capture
  (`docs/testing/2026-10-23-d59d-g-capture.md`) found no chrome-pass write cancelling the pad - RC F2 was most likely
  the relock's Home start, which step A queues now. Code QA (`qa-59d-round1-code.md`): merge after fixes; the fix round (R1-1..11) is on the branch - R1-1 decided by the user (the photo on Android's face as the glance's ground), R1-8 not built.
  Steps J, L and §8.9's edge swipe (branch `d59d-jl`, 59d §8.11): J built - the swipe up anywhere on the glance asks
  as "Lås opp" did (the button gone, the hint line "Sveip opp for å låse opp" in its place, TalkBack's action on it).
  L built - the glance's clock takes Android's small clock's geometry on a phone with a profile (the emulator's Pixel,
  measured on `handy36`; the Jelly Star's entry a TODO with the emulator's numbers - **measure a frame of its face**),
  else today's centred clock. §8.9 decided (a) from the capture (`docs/testing/2026-10-23-edge-swipe-capture.md`): with
  lock task's flags in sync Quickstep refuses the edge swipe; the user's swipes went through on desynced flags - the
  system's Home while LOCKED now heals them at once (16d's heal, `HealTrigger.SYSTEM_HOME`). Code QA
  (`qa-59d-jl-code.md`): merge after fixes; the fix round (JL-1..8) and §8.13 (Android's gesture handle on the glance
  in the hint line's place, a swipe from it asks; Nunito Light for the profile clock) are on the branch - rc.3 next.
  **On rc.3**: two edge swipes in a row (the second refused?) and the Jelly Star's `ro.product.model` and a frame of its
  face for its clock profile.
- **The edge swipe on the user's AVD (branch `edge-swipe-fix`, 2026-10-23, 59d §8.16):** every swipe from the handle
  went through on `jellystar2`. The cause is the AVD: it was made without a device profile, so `hw.mainKeys = yes`. That
  means no SystemUI navigation bar or taskbar delegate, so Quickstep never gets `home_disabled`. It is not our lock
  task. Proven on `jelly36` by flipping that one line. The user sets `hw.mainKeys = no` and cold boots (`emulator.md`
  §3). A real Jelly Star is unaffected. Built as the safety net for a desync on a phone: §8.9's option (b). The lock
  that the system's Home brings back asks for the unlock at once, once per gesture (no call, no notice or screen-on in
  the last 1 s, at most every 2 s), and asks once more when the keyguard's re-occlusion cancels the first ask. The
  "Lås opp" button stays gone and the swipe stays primary (the user, 2026-10-23). The code QA's fix round (Q-1..Q-9)
  is done: no ask at a process start's own Home start; the mark is dropped by a screen-off and a lock-state change,
  and re-checked at the take. **Device checks**:
  - on the Jelly Star, ten handle swipes across a reboot and a screen-off, all refused (`TYPE_RESET_GESTURE`);
  - an own-mode reboot that comes up on the glance, not our keypad (owed).
  Done on `jelly36` (59d §8.16): the 16d override desync with the net, a swipe with no heal on `hw.mainKeys = yes`,
  and an update's restart that marks no ask. **The user's result (2026-10-24, `jellystar2`)**: with `hw.mainKeys = no`
  the handle swipe went straight to the pad ("perfekt"); before, with it still `yes`, the net unlocked after the flash,
  as designed. The AVD's file was `~/.config/.android/avd/` (`emulator.md` §3 names both places).
- **The rc.3 run's fix round (branch `rc3-fix`, 2026-10-23, 59d §8.14, `docs/testing/2026-10-23-rc3-fix-run.md`):**
  - **Done** (on the branch):
    - RC3-1: the boot cover asks again for an ask that got no answer, at 3, 6 and 12 s, then every 6 s. SystemUI's
      keyguard was ready 26-42 s after the first ask on the emulator. Six boots showed the pad with no key press, and a
      re-ask keeps the typed digits.
    - RC3-2: the glance shows the wallpaper while it asks, so there is no slide (3 moving frames to 0).
    - RC3-3, the narrow part: no Home start of the cover's before the take-over, Back held until it.
    - RC3-6 is a line in 59d §8.4.
  - **Open, RC3-4 (Low, measure first): the unlocked setup on the main thread.**
    - On the emulator's debug build: `Unlocked setup took` 4.5-10.8 s in the fix run's r-boots (19.2 s at e1, a cold
      start right after an emulator wedge; 4.6-9.6 s in the rc.3 run), and the boot's Home 13-22 s after the unlock.
    - With the main thread busy, a Home start - BootHome's, or the kid's Home key on a boot whose PPA order puts
      HomeActivity first - leaves WM with no focused window for 8-9 s. A key left waiting 5 s in that gap is an ANR
      ("Application does not have a focused window"), RC3-3's dialog without the cover's part.
    - Measure on the **release** build on the Jelly Star: `Unlocked setup took`, `Unlocked setup tail took`,
      `Boot Home started N ms after the unlock`, the NO_WINDOW gap (`input_focus`), and the take-over stamp's time
      after the PIN (`Boot cover taken over`; 24 s in the fix run's r2) with its margin to the cover's 30 s hand-over
      (`COVER_LAUNCHER_WAIT_MS`): a stamp later than that brings the hand-over's Home start into the busy main process
      - RC3-3's shape again - and Back stays held until then (code QA Q-5).
    - Only if it is still seconds there: move the parts of `initRest`'s head that can safely leave the main thread
      (none is obviously safe today: the update fence, the PIN lock's init and the time rules feed the lock's first
      decisions), or start BootHome's Home only once the head is done. Launcher, S-M after the measurement.
  - **Later, RC3-8 (note): font 1.0 with music and three rows.** Fit candidate 3 shows the full tile and one row; the
    other two rows sit out of sight in the scroll with no hint (the "more" line counts only rows beyond three). At font
    1.3 the compact tile shows the next row under the fade. Options: a fade or "more" hint whenever a row is out of
    sight, or candidate 4 (the compact tile) before 3 once a row would be hidden. Launcher, S.
- **6-inch / Android 15 check (2026-10-23, `docs/testing/2026-10-23-six-inch-android15.md`, AVD six35 = pixel_7 411x914 dp,
  API 35):** every screen fits at font 1.0 and 1.3, nothing broken or oversized, just more empty space. Findings: **F1
  (High, M)** an UNARMED reboot (a crash, `adb reboot`) never finishes booting on an image whose stock launcher isn't
  direct-boot-aware - no DBA home, Settings hidden, our cover enabled only by the shutdown arm, so `LOCKED_BOOT_COMPLETED`
  never fires; SystemUI's lock is dead to input until an incoming call starts our call screen -> **59d step M** (the
  cover always enabled in android mode, in progress); F2 (note) ~8 light-grey frames after the pad's success are
  SystemUI's light pad fading, not ours; F3 (S) at font 1.3 Home's contact labels touch (`item_kid_contact.xml`,
  `contact_name` has no horizontal padding); F4 (S) glance rows are one line even with 822 dp available - two lines on
  tall screens; F5 (S, optional) the music library's cover cap of 180 dp (`music/.../ui/Fit.kt`) could grow on tall
  screens; F6 (check) Android 15 logs it removed our pinned Home preference at every locked boot and at the cover's
  hand-over - Home still came up; check on a phone (step M's run looks); F7 (note) 4-21 black frames at a wake on 15,
  no leak. Not done there: the timer tile, the blocked/Play screens, QR provisioning.
- **Screen capture switch (the user, 2026-10-23, approved for after rc.3) - the switch DONE 2026-10-23, branch
  `screen-capture` (policy key `hardening.disallow_screen_capture`, default off, migration 0089; emulator check
  `docs/testing/2026-10-23-screen-capture.md`); the per-app camera and permissions rows below stay open:** one hardening switch on Advanced, "Screenshots
  and screen recording off" (labelled "Block screenshots" after the code QA, 2026-10-23) = `setScreenCaptureDisabled` for the whole phone (also blanks every app's Recents card
  from then on; `adb screencap` goes black). Server + launcher in one commit (the `hardening` policy keys are pinned
  on both sides). **No global camera switch** ("en full kill switch for kamera gjør jo fort at videochat ikke
  funker"): the Camera app is removed from the allowlist as today; a per-app "Camera: off" (the device owner denying
  the CAMERA permission for one package, `setPermissionGrantState`) is on the long list ("legg på long list at vi kan vurdere en sånn per-app videobryter senere", 2026-10-23), not ordered. S. Same list, same code (the user, 2026-10-23, "legg de på lista"): a
  **permissions row per catalog app in the PWA** - the ordinary runtime permissions (contacts, SMS, phone, media,
  notifications) granted silently by the device owner before the first start; the sensor ones (camera, microphone,
  background location, body sensors) can't be granted silently since Android 12 - shown as "approve on the phone",
  and deniable per app from the same row. S-M.
- **PWA tab switches feel slow at times (the user, 2026-10-23; the server is a Proxmox VM with plenty of power, so
  the render isn't it):** the time is in the path or the browser - Tailscale on a relay instead of direct (mobile
  data), the first request after iOS froze the installed app (the connection is rebuilt), or the page's assets
  re-fetched after iOS dropped the cache. Measure on the client first: a small timing line per navigation (Navigation
  Timing: connect, TTFB, render) shown behind a debug switch or logged to the server, and the user checks `tailscale
  status` for relay vs direct. Then: prefetch the other tabs after a page settles (same session, no offline caching),
  and/or keep the connection warm after a resume (`reconnect.js` already pings `/ping`). Server, S; after rc.3.
- **An x86_64 emulator build of the release launcher (2026-10-23):** the release APK carries tsnet for arm64 only
  (`.github/actions/build-tsnet`, by design), so it installs on the Play/Google APIs x86_64 images only through their
  ARM translation and NOT on a pure AOSP `default` x86_64 image (`INSTALL_FAILED_NO_MATCHING_ABIS` on the user's
  `jellystar-aosp` AVD). Proposal: `gomobile bind -target=android/arm64,android/amd64`, ABI splits in the launcher's
  release build, the release workflow uploading a second asset `kids-launcher-mdm-x86_64.apk` (same versionCode and
  name, never "latest", never the QR's URL, ignored by the catalog's asset pick), for emulators only. CI, S-M; decide
  before building (APK size, the self-update advertising the arm64 asset to an emulator phone - the launcher should
  refuse an APK with no matching ABI cleanly, or the server should pick by the phone's ABI).
- **Screen time left in the shade (the user, 2026-10-23):** NO persistent launcher notification with status or controls
  ("helst ikke!"). The one thing wanted: when a screen-time budget is set for the phone, an ongoing notification
  showing the time left today, optionally with one action "Be om mer tid" that posts a request to the parent
  (a parent event + push in the PWA, where the parent answers with a lift). Since Android already forces the anchor's
  "Vibb One" notification, that same notification carries the time when a budget exists and stays the quiet "Vibb
  One" otherwise - never two. Nothing else goes in the shade from us; the empty Quick Settings area above it is
  SystemUI's and stays empty under lock task. Launcher (+ server for the request), S-M; after rc.3.
  **Built** 2026-10-25 on `r13-rule-card` (design r13 steps 2 and 3, `docs/design/r13-launcher-rule-card-and-time-left.md`
  "Implementation status"): `POST /api/devices/time-request`, the `extra_time_request` event with its own switch "Asks
  for more time" (migration 0096), Device › Time's "Asked for more time at …", the anchor's time-left title and its one
  action. Code-QA'd (`qa-r13-rule-card-code.md`, fix round done); ships with server 0.50.0; awaiting merge and the
  emulator checks (§2.9).
  **Merged 2026-10-25 (3eb0c025) after code QA (`docs/review/qa-r13-rule-card-code.md`, 0 High / 1 Medium / 7 Low, all fixed). Emulator run 2026-10-25 (`docs/testing/2026-10-25-r13-run.md`): the card under a school rule, the notification's time left, the request end to end (tap → 200 → inbox + push switch → Time page row → a +15 lift clears it) all PASS. Server part in `server-v0.50.0`; launcher in `launcher-v0.37.0-rc.2`.**
- **Design 59d step M (2026-10-23, six-inch run F1/F6): an unarmed boot must never hang.** Built on `d59d-step-m`
  (59d §8.12): in android mode the boot cover stays enabled (a direct-boot-aware HOME at every boot, also after a crash
  or `adb reboot`), the take-over is a device-protected stamp, and `lock/HomePin` keeps HomeActivity the unlocked HOME
  (re-seats our PPAs when the per-boot order puts the cover first). Emulator run on Android 15
  (`docs/testing/2026-10-23-step-m-run.md`): F1 reproduced on `main`, gone with step M; no F6 removals, no chooser.
  Code QA (`qa-59d-m-code.md`): merge after fixes; the fix round (M-1..12) is on the branch - a tripped crash guard
  keeps the cover enabled but inert (SystemUI's face), boot-time relaunches no longer use up the pad's automatic
  asks (step F), Home's resume enables a self-disabled cover again. Owed: 59d §8.4 item 8 on Android 16 and the Jelly
  Star (is its stock launcher direct-boot aware?).
- **Duplicate persistent preferred activities at every boot (qa-59d-m-code M-9; predates step M, not urgent):**
  Android 14+'s policy engine re-enforces every PPA at every boot (`DevicePolicyEngine.reapplyAllPoliciesOnBootLocked`)
  and PMS appends without a dedupe, so `package-restrictions.xml` gains a copy of each of our PPAs a boot until
  something clears them. In android mode step M's re-seat clears them (about every other boot); in own mode or without
  the cover nothing does (+4 entries a boot: HOME, the Play link blocker's two, the call log). Resolution doesn't care
  (a duplicate names the same component); only the file grows (a few hundred bytes a boot). If it ever matters: a
  re-seat at every Nth take-over in every mode bounds it, at one ~0.5 s window per N boots.
- **PWA ⓘ panels are too wordy (the user, 2026-10-23, long list):** the info panels behind the ⓘ buttons explain
  far more than needed ("Off switches location off on the phone. Only when asked takes a fix for ..."); the one-line
  footer above them is usually enough. A pass over every `info-panel` in `server/templates/`: keep the ⓘ only where
  the footer can't carry the one fact a parent needs (a consequence, a number, a "needs the phone online"), cut each
  panel to one or two short sentences, drop the ones that repeat the row's own label. Server, S-M (text only); the
  wording follows the PWA's register (plain, no jargon). **Done 2026-10-24 (branch `pwa-polish-a`)**: 83 panels -> 74,
  9 dropped, the static rest cut (pwa.md, "The ⓘ cut").
- **Safe zones (the user, 2026-10-23, from Xplora): named circles on the Locate map ("Home", "School"), a parent event +
  push when the phone leaves or enters one.** Design 64 (to write after rc.3). Step 1 server-only: zones per phone
  (centre, radius, name) drawn/edited on the Leaflet map; evaluated on every stored fix; a transition counts after two
  consecutive fixes on the same side (no flapping on one bad fix); events "arrived at / left <zone>" in the inbox and
  pushed under each parent's Notify Me About; latency = the locate interval (10 min default -> up to 20 min). Step 2
  optional: the launcher registers the zones as Android proximity alerts and takes an immediate fix on a crossing
  (zones in the policy; `location_policy`), latency under a minute at little battery. M. The user, 2026-10-23: build
  both steps ("we should put on the list to implement this stuff!"); the fences do the watching (the system checks
  by distance and speed, cell/Wi-Fi before GPS), so the Regularly interval can be stretched once they are in;
  fences re-registered at the first sync after a boot; the 100-fence cap is plenty. And piggyback our own locating
  on it (the user, 2026-10-23): crossings take the fixes that matter; the Regularly timer becomes a long heartbeat
  (30-60 min) for the trail and "last seen"; the passive provider's fixes (any other app's or the system's) feed the
  trail free; the heartbeat's fix is skipped while the significant-motion sensor says the phone hasn't moved. Design
  64 covers the battery model as its own section. **Step 1 done 2026-10-24 (branch `design-64-step1`, server only, not merged)**: zones on
  Locate (list, page, map), each fix stored once and judged once, `zone_*` events as key + params, push while news;
  the served interval unchanged until step 2 (`docs/notes/server/zones.md`). **Step 2 done 2026-10-24 (branch
  `design-64-step2`, both dirs, not merged)**: `location_policy.zones`, a fence per zone, a crossing's two fixes,
  `extra_locations`/`zone_watch`, the 30-min floor, the motion-gated heartbeat, the moving series, the balanced/precise
  paths, `provider`/`mock` stored (0093), the release on un-manage, the mock rule, Kid Settings' line; the code QA's
  fix round (Q-1..Q-12: everything gated on zones, the kept slot, `zone_watch.still` + `resent_at` for "Here now",
  Off clears the queue first, zones only to `zones_v1`, the mock count once per rise with its date);
  emulator §6t owed (the coordinator's run after code QA).
- **Keystore for the phone's secrets (approved 2026-10-21, "no-brainer"):** Design r10 (2026-10-22): the user chose TEE-only (not StrongBox) and the "Ny oppsettkode" recovery button "så lenge dette gir god beskyttelse og sikkerhet". The user also confirmed the wrap of the kid PIN hash despite reversing design 24b's QA #7 ("Jeg støtter ditt forslag med innpakning") (1) the device token (and the other
  CE-stored secrets P-L5 names: the kid PIN's hash and salt, and the reset-password token `WindowRelockStore` keeps in
  plain CE prefs - design 59 §7, AOSP `DPM:6363-6369` says never store it so) wrapped with a non-exportable AndroidKeyStore key, so a
  copied data directory (root, a backup) is useless - launcher fix round; (2) a **hardware-bound device token**: at
  enrolment the phone makes a key pair in the Keystore (StrongBox when `android.hardware.strongbox_keystore`, else
  TEE), the server binds the token to its public key and every request is signed (key attestation checks the key is
  in hardware and the app is our signed launcher) - a stolen token is worthless off the phone; closes the "stolen
  token" root of server L1/L3/L4. Its own design (60), server + launcher in one contract change, after design 59's
  keyguard decision (a real Android PIN would let keys be bound to user authentication).
  **Built** (item (1), 2026-10-25, branch `r10-keystore-wrap`; code QA `qa-r10-keystore-code.md` fixed): `SealedPrefs`/`DeviceToken`/
  `Enrollment`, the redacted cache, "Ny oppsettkode" in the deadlock; deviations, the revision check's N4/N5/N8 left
  for code QA, and the §8 emulator checks owed are in the note's "Implementation status".
  **Merged 2026-10-25 (74f5faa5) after code QA (`docs/review/qa-r10-keystore-code.md`, 0 High / 3 Medium / 9 Low, all fixed). Emulator run 2026-10-25 (`docs/testing/2026-10-25-r10-keystore-run.md`): the upgrade from rc.8 seals all four fields with the phone enrolled and both PINs working, total key loss → NEW SETUP CODE → re-enrolled with a lower-case code, the boot cost unchanged; checks 3, 6, 8, 10, 11, 12 and F1 owed. Goes out with `launcher-v0.37.0-rc.1`.** **F1 built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, awaiting merge)**: `glancePrompt(.., androidFace)` - UNUSABLE only in own mode, as Android's face (`AndroidLock.faceOn`) the stand-in shows the normal hint (`LockGlanceTest`); its emulator check is in `docs/testing/2026-10-24-home-round-run.md`, "Owed after launcher-small-a".
- **The lock's glance page, three things** (the user's emulator screenshot, 2026-10-21, 320x569 dp, music tile full,
  one WhatsApp missed-call row): (1) **bug** - the bottom row ("Nødsamtale" and the torch) is cut off at the screen's
  bottom edge: `glanceFit` didn't keep the page inside the screen (check the 2026-10-21 torch spacing, +12 dp, and
  the music tile's time line against `LockGlanceLayoutTest`'s pinned heights); (2) **the bottom block stays put** (the
  user): "Lås opp", "Nødsamtale" and the torch anchored at the bottom whatever the content; the space above (clock,
  date, alarm, tiles, rows) takes what is left, and the notification rows scroll inside it when they don't fit (a
  change to design 25's "the page never scrolls" - the rows only; the tiles keep the fit's sizes); (3) **the time line
  under the music bar**: elapsed at the bar's left, the duration right-aligned at its right end (today "1:26 · 4:04"
  centred-left on one line). Launcher only. (4) **A more compact music tile**, like the iPhone 16's lock player (the
  user's screenshot, 2026-10-21): a small cover (~48 dp) with the title and artist on one line each (ellipsized, no
  wrap); one progress row "elapsed - bar - remaining" (`1:13 ━━━━── −1:50`, replacing (3)'s separate line); the
  controls as plain glyphs on one row without the big discs (each still a >= 48 dp target, play a little larger).
  Saves roughly a third of the tile's height, which also helps (1)-(2). No output/AirPlay button (the lock opens
  nothing). Mockup first. **Done 2026-10-22 (7ddc80a6, branch `r9-glance`; design r9 §1-3, not merged yet)**: (1) the
  fit is keyed on the available height (the bars arrive as padding at the same height); (2) the bottom block is
  measured whole, the top block clips, the rows scroll (glanceFit: every row or one visible, never folded; candidate 7
  the "more" line; the timer now outlives the date); (3)+(4) the compact player (174.8 dp, was 233.8). Device checks:
  the note's §5 checks 1-3.
- **"Modify system settings" without adb** (the user, 2026-10-22: no adb grants beyond what the QR setup does): the
  touch row (r9) and the clock format (design 34 P12) need the WRITE_SETTINGS app-op, which a device owner can't grant
  itself; `docs/setup/android-settings.md` names only adb. Add a row to the launcher's "Pause all restrictions"
  screen that opens Android's "Modify system settings" page for Vibb One (`Settings.ACTION_MANAGE_WRITE_SETTINGS`
  with our package), as "Bakgrunnstilkobling" does for the battery, plus the PWA warning while it's missing; the docs
  lead with that path. Audit the other setup docs for adb-only steps (the power-button gestures in
  `google-account.md` already have an on-phone path). Launcher (+ PWA text). Small; next launcher round.
  **Done 2026-10-24 (branch `write-settings-row`)**: Settings' "Modify system settings" row under "Background
  connection" (`WriteSettingsAccess`, always shown, the battery row's path); new status field `write_settings_granted`
  (server 0090, About This Phone's row; the clock warning's ⓘ names the row); `android-settings.md` leads with it.
  Plus a "Fingerprint" row (Android's biometric/fingerprint enrolment; only with our Android credential and a sensor,
  `FingerprintEnrol`), the on-phone path design 59d §8.4 names.
- **Music: search Spotify tracks** (the user, 2026-10-22): title search works on the emulator (the user). A list-mode
  item's title is already `"<name> — <artist>, <artist>"` (server listing, `docs/notes/server/music.md` list mode),
  so an artist name should match too - the user checks. A context-mode entry has no items on the phone (Spotify picks;
  every reader refused the list), so nothing in it is searchable until a reader may list it (the daily retry, or a
  login of the child's account that owns/collaborates on the playlist). Open only if the artist check fails.
- **Lock glance: the compact music tile could be a little lower** (the user, 2026-10-22, after r9: "fin - jeg tror du
  kunne latt den være enda litt lavere, men er greit"): trim its vertical padding/gaps (174.8 dp now) on the next
  glance round; `LockGlanceLayoutTest`'s pins move with it. Small, not urgent.
- **Music: what a heart means** (the user, 2026-10-21): the heart is per track/episode, but "Favoritter" only filters
  the carousel to entries holding a heart and a cover tap plays the whole entry - so in practice it marks the tile.
  Options put to the user: (A) the heart on the tile/entry instead of per track; (C) keep per-track hearts, and under
  "Favoritter" a cover tap plays only that entry's hearted items (in the entry's order; one source, no mixing);
  (B) a "Favoritter" mix tile playing every hearted item across entries (shuffled) - mixes sources (local files, NRK,
  Spotify, Storytel), hard on the Sonos and Spotify paths. **Decided 2026-10-21: (C) first**; (B) later as its own
  design, NRK/RSS and Storytel first. The user's point on (B): items
  do carry their own play links - NRK/RSS episodes are direct URLs the app already sends one at a time to a room and
  advances itself, a Storytel book is a per-book mint (design 58); the exceptions are Spotify (a speaker-side queue
  from a share link on a room, the Web Playback SDK on the phone - a single track can be a share link, but every
  switch between it and a URL item replaces the transport) and own files (phone-only: "the phone never serves", 26a
  drops them on a room). So (B) is buildable as a per-item dispatcher; the cost is a multi-source advance that vibb
  doesn't have. **(C) done 2026-10-22 (branch `r8-music`, 1e17fcf9; design r8 §1)**: the scope is the hearts at the
  tap, kept with the queue (Room 7 `now_playing.favourites`); a pick inside it stays in it; the tile describes the
  hearted run. Emulator checks: design r8 §4.1-§4.7. (B) stays open.
- **Music: the output and sleep sheets don't swipe down** (the user, 2026-10-21): `ui/Widgets.kt` `Sheet` is our own
  sheet - a scrim tap and Back close it, the handle is only drawn. Add a drag down on the handle/sheet (past a
  threshold or a fling) that closes it, as Android's sheets do; keep the scrim tap and Back. **Done 2026-10-22
  (branch `r8-music`, 1e17fcf9; design r8 §2)**: `Modifier.dragDownToClose` (`ui/DragDown.kt`), plus TalkBack's
  dismiss; emulator checks r8 §4.8-§4.9.
  Same round (the user, 2026-10-21, "swipe tilbake"): Android's back gesture already works in the app
  (`MusicRoot.kt:97` `BackHandler`: sheet -> closed, list -> player, player -> library, search -> library); add the
  music-app standard on top - a drag down on the player screen returns to the library, as Spotify's and Apple
  Music's do. Check on the Jelly Star (gesture navigation) that the edge swipe isn't eaten by a horizontal pager.
  **Done 2026-10-22 (branch `r8-music`, 1e17fcf9; design r8 §3)**: the same modifier on the player's root; nothing
  removed, no `systemGestureExclusion`. The Jelly Star check is r8 §4.10-§4.11 (owed by the user).

- **Touch sounds and vibration on Kid Settings** (the user, 2026-10-16): a row on the child's Settings
  (swipe left) to turn key/touch sounds on and off, and vibration on touch. Android's switches are
  `Settings.System.SOUND_EFFECTS_ENABLED` (touch sounds), `HAPTIC_FEEDBACK_ENABLED` (touch vibration) and
  `DTMF_TONE_WHEN_DIALING` (dial-pad tones) - writable with the WRITE_SETTINGS app-op design 34 P12 already
  grants (`ClockFormatSetting`; without it the row is read-only and says so). The keyboard's own key sound
  and vibration are the keyboard app's settings (HeliBoard's since 2026-10-21, Gboard's elsewhere), not Android's -
  say so, or open nothing. Our own
  keys (the PIN lock, the phone book) follow the system switches. A Quick controls bit like design 18's
  sound row (the parent decides whether the row shows); never on the PIN lock. Launcher + the server's
  switch. Approved 2026-10-16; the user asked for it again 2026-10-21 ("tastelyd" on Kid Settings). **Done
  2026-10-22 (7ddc80a6, branch `r9-glance`; design r9 §4, not merged yet)**: bit 16, off on a new phone (the parent
  grants the app-op first), sounds + vibration only (`DTMF_TONE_WHEN_DIALING` left alone), read-only without the
  app-op; Touch feedback stays on in Android's Settings (`docs/setup/android-settings.md`). Device check: §5 check 4.

- **Notifications to the parent, phase 1: Web Push and an inbox in the PWA** (the user, 2026-10-16: "la oss
  begynne med web push", and "the PWA needs a clear place where we see notifications"):
  - **An inbox**: one clear place in the PWA - "Notifications" (a bell in the large-title bar of every tab
    page with an unread count, and its own page) - listing every event newest first, per phone, read/unread,
    each opening the page that shows it. Events are stored on the server (kept e.g. 90 days), so nothing
    depends on a push arriving. Mark read when opened, "Mark all as read".
  - **Web Push** to the installed PWA: VAPID keys made and kept in `data/keys/`, the payload encrypted end to
    end (RFC 8291) so Apple's and Google's services see nothing; iOS 16.4+ only once added to the Home Screen
    (the page says how, and whether this device is subscribed); subscribe per browser/device on the inbox
    page; a subscription the push service refuses (404/410) is dropped. The service worker gets a push and a
    click handler (still no offline caching); it posts a receipt when it shows a notification, so the server
    knows which pushes arrived (shown in the inbox). From Apple's guide (the user pasted it, 2026-10-16:
    developer.apple.com/documentation/usernotifications/sending-web-push-notifications-in-web-apps-and-browsers):
    standard Push/Notifications/Badging APIs and a service worker, iOS 16.4+ Home Screen web apps and macOS
    Safari 16+, no Apple Developer Program; subscribe from a tap's handler at once; **no invisible push** -
    show every push at once or Safari revokes the permission; a VAPID key pair; RFC 8030 requests over
    HTTP/1.1 or 2 with SNI and ALPN to `https://*.push.apple.com` (the server must be allowed to reach it);
    headers `TTL` (required, positive; stored at most 30 days while the device is away, a limited number),
    `Authorization` (the VAPID JWT + public key, the key the subscription used; refresh the JWT at most
    hourly, `exp` at most a day ahead, `sub` a URL or `mailto:`, `aud` the push service's origin),
    `Content-Encoding` (`aes128gcm`), `Topic` (optional, at most 32 base64url characters, coalesces) and
    `Urgency` (`high` to deliver at once); payload at most 4 KB; at most 100 unacknowledged requests on an
    HTTP/1.1 connection. Answers: 201 sent; 410 = the subscription expired (drop it); 400/403/413/429 with a
    JSON `reason` (BadTtl, BadJwtToken, VapidPkHashMismatch, PayloadTooLarge, TooManyRequests, ...) - log
    the reason and the `apns-id`; 429/500/503 retry later. The app badge: `navigator.setAppBadge` /
    `clearAppBadge` for the unread count. Also check whether Safari's declarative Web Push (Safari/iOS 18.4)
    fits: Safari shows it without the service worker running, which would make iPhone delivery less fragile;
    the standard push stays for other browsers.
  - **Delivery health per device** (the user, 2026-10-16: catch pushes that are sent but never shown): each
    subscription keeps its last push accepted (201), its last receipt and how many accepted pushes have no
    receipt. Accepted pushes without a receipt for a while (e.g. 3 in a row, or none for a day after one
    was sent) mark that device "Notifications may not be showing" on the inbox page and as an Attention row,
    with what to check (added to the Home Screen? notifications allowed for the app? Focus?) and "Send a test"
    - a visible test push on a tap, never an invisible one. A 410 marks it "Turned off on that device - turn it
    on again there". The same counts feed phase 2's second channel.
  - **Events** (each switchable on the inbox page): an emergency call made (always on), the parent PIN used
    or locked out, foreign access or a start without Vibb One, **battery low** (the user, 2026-10-18, instead of
    "offline for X hours", which was dropped); later a new blocked app or an extra-time request. Titles only,
    never message content.
  - **Design 35** (`docs/design/35-parent-notifications.md`, design QA done, revised after the user's decisions
    2026-10-18). Step 1 (events, inbox, the `battery` wire field) merged 2026-10-18 after code QA
    (`docs/review/qa-35-s1-code.md`); step 2 (Web Push) merged 2026-10-18 after code QA (`docs/review/qa-35-s2-code.md`;
    device checks owed: iPhone 18.4+ and older, macOS Safari, Android Chrome, Firefox, a 410 after removing the app, a Pi
    with a wrong clock); step 3 (launcher: the battery reading and receiver, a sync right after an emergency call) merged
    2026-10-18 after code QA (`docs/review/qa-35-s3-code.md`). All three steps go out with server-v0.27.0 and
    launcher-v0.28.0 (tagged 2026-10-19); device checks owed: the Jelly Star's BATTERY_LOW threshold and `EXTRA_BATTERY_LOW`, the receiver in Doze, the
    emergency sync over mobile data, and the Web Push checks above.
  - **Phase 2, later**: on no receipt for an important event within ~2 minutes, a second channel - ntfy
    (top priority, "time-sensitive" on iOS) or Matrix through a webhook - and an emergency call to both at
    once. Web Push on Safari can't break through Focus and iOS may drop a subscription silently, so phase 2
    is what makes the important ones reliable.
  Approved 2026-10-16 (phase 1).

- **Home starts at the top after an unlock** (the user, 2026-10-24: "når jeg slår av og på skjerm og taster pin så
  tror jeg at jeg vil at home starter på null"): Home's grid scroll resets to the top whenever the lock is left for
  Home - screen off/on + PIN, the handle swipe, the boot - and never when the unlock's target is an app (a notification
  row, a call: the app opens, Home isn't shown). Launcher, small; one Home round with the black-flash entry below
  (same activity, both need the emulator). **Done 2026-10-24 (branch `home-round`; emulator check owed)**: Home learns
  why it is shown from the typed HOME start that brought it - `HomeFront.EXTRA_ARRIVAL` next to `EXTRA_BROUGHT`, no
  new broadcast: the lock's leave through Home (`PinLockActivity.go`, every mode: own keypad, the handle's swipe,
  Android's pad in front) says `LOCK_LEFT_FOR_HOME`, or `LOCK_LEFT_FOR_APP` when a glance row's target opens over Home
  (`lockLeaveArrival`; as the kiosk's root the opener opens nothing, so that is Home again); the boot's `BootHome`
  says `BOOT`; every other start of ours (dark lock-up, update, relock, role change, install mode, rooting lock task)
  is `OURS`. Pure `ui/home/HomeReset.kt` (`homeResetDue`, `homeResetPending`, `HomeResetTest`): only a leave for Home
  and the boot reset; the reset is taken at the content's next resume (`resetToTopIfDue`, the one unanimated
  `scrollToPositionWithOffset(0, 0)` plus the contacts row's start) and dropped at Home's stop. A call over the
  unlock brings no Home (`lockLeave`), so nothing resets; an unlock on SystemUI's own face or pad (the glance stopped
  under an app, design 59b step 0) brings no Home either. Unchanged: the Home button or Back from an app keeps the
  kid's place (no extra, Home was stopped: `homePressToTop` needs Home up), and the kid's press on Home itself still
  scrolls smoothly to the top (round 7 §4). Fix round (qa-home-round-code Q-3): `go()` asks
  `GlanceOpener.opens` (the opener's own facts and step, synchronously, before the bring) - a target that opens nothing
  (not installed, suspended or hidden by then, no launch intent, the kiosk without lock task, the kiosk root) resets
  Home like a plain unlock; a refused send still falls back to the launch intent, so it counts as an app. Residual:
  only a target that changes between that check and the open (a few ms) - Home is then left where it was.
- **Provisioning storm: debounce the app-list reload** (qa-home-round-code Q-4, 2026-10-24; pre-existing, RC3-4
  family; the coordinator's two "no focused window" ANRs right after `set-device-owner`): the enforcer's hide/suspend
  storm fires one `LauncherApps` `onPackageChanged` per package on the main thread - each evicts icons (a cache scan),
  requests a load, checks the clock app and launches an IO coroutine whose `WindowRelock.onPackageInstalled` can run
  a full `AppEnforcer.apply`; the conflated `appLoads` channel then loads back to back for the whole storm, each load
  posting a list Home renders on the main thread. Fix: `appLoads`' consumer waits for a quiet period after a receive
  (drain until 300 ms pass with no request, or 2 s since the first), then loads once (`iconsToEvict` already
  accumulates the packages); coalesce `WindowRelock.onPackageInstalled` per burst the same way, so a storm can't
  queue one `apply` per package; pin the quiet period in `HomeRound7Test` next to the conflated-channel pin. Launcher,
  S. **Done** (branch `appload-debounce`, 2026-10-24): `ui/home/AppLoads.kt` (`appLoadDue`, 300 ms / 2 s,
  `runAppLoads`; the process's first request loads at once), pinned in `HomeRound7Test`. `onPackageInstalled` stays
  per package: on IO, one or two PackageManager reads, and `apply` + a sync only when a block changes - at most once
  per package installed in the last 10 min from elsewhere than Play or us (its first callback, whatever it was for,
  the storm's own hide or suspend included); never for an older app's hide, unhide or suspend (qa-appload-debounce-code
  Q-1). `evictPackage` skips keys without the quoted package
  name before the decode (Q-4). The emulator run (jelly36): 0 ANRs of ours, but 59 loads in two minutes - the
  enforcer's calls came more than 300 ms apart. **Follow-up done** (branch `appload-hold`): `AppEnforcer.apply` holds
  the loads for its pass (`AppLoadHold`, `appLoadHeld`; a burst waits at most 10 s, chained passes included) and one
  load comes 300 ms after its end, the trailing callbacks with it (qa-appload-hold-code Q-1 to Q-5). **Measured 2026-10-25 on a wiped `jelly36`**
  (`docs/testing/2026-10-24-home-round-run.md`, second follow-up): 0 ANRs, 31 loads in two minutes (59 before), none during
  the enforcer's passes (07:30:03-07:30:20) - the rest are the system's PACKAGE_CHANGED broadcasts straggling in for a minute
  after the last pass (1-3 s apart on the loaded emulator), each 4-60 ms with the icons cached. Goes out with
  `launcher-v0.36.0-rc.8`.
- **An F-Droid repo as a release source; Etar first** (the user, 2026-10-24: Fossify Calendar opens the editor on a
  tap - FossifyOrg/Calendar #171/#693 open - "jeg ønsker etar, og å kunne tracke updates"; Etar's GitHub releases carry
  no APK): a tracked app of source `fdroid` = a repo URL (default `https://f-droid.org/repo`) + package id. Versions
  from the repo's per-package API (`/api/v1/packages/<id>`: `suggestedVersionCode`, `packages[{versionName,
  versionCode}]`), the APK at `<repo>/<id>_<versionCode>.apk`, its SHA-256 from the repo's signed index (`entry.json`
  names `index-v2.json`, 67 MB with daily diffs, ETag) - the hash is verified before the file is served, which GitHub
  can't give. F-Droid signs with its own key: an app installed from GitHub or Play can't switch to F-Droid without a
  reinstall, so one source per app (the PWA says so on the row). The same protocol covers IzzyOnDroid (dev-signed APKs)
  as another repo URL. No F-Droid client on the kid's phone (an app store breaks the model). Behind the same release
  interface as GitHub, so GitLab/Forgejo can follow later if ever needed. Server, S-M; next server round after design 64
  step 1. Earlier parked text (GitLab/Codeberg; Nexus looked at: no calls, UnifiedPush only; Element X stays) folded in.
  **Done 2026-10-24 (branch `fdroid-source`, server only, migration 0094)**: source `fdroid` = `fdroid_repo` (https,
  default f-droid.org's) + `fdroid_package`; f-droid.org takes its per-package API, any other repo (IzzyOnDroid) the
  index alone; the SHA-256 always from `index-v2.json` (fetched only when entry.json's hash changed, checked against
  it, cached under `data/tracked_apps/fdroid/`, at most 100 MB, no diffs); the APK checked before it is stored and
  stored as a GitHub release (tag `v<versionName>`). Add App's F-Droid choice, "F-Droid · ws.xsoh.etar" on the app's
  page with the one-source line, a source change refused (worded by a cached release), the launcher's and music's
  rows stay GitHub. `docs/notes/server/play-and-updates.md`, "F-Droid source".
- **F-Droid: verify the repo's signature (`entry.jar`)** (follow-up of the F-Droid source, 2026-10-24): today the index
  and the APK come over HTTPS and the APK's hash is checked against the index, but the repo's own JAR signature over
  entry.json (`entry.jar`, signed by the repo's key - F-Droid's, IzzyOnDroid's) isn't verified, so the repo's TLS
  certificate is the trust anchor. Verify it and pin the repo's signing certificate per repo (first use, or the known
  fingerprints of f-droid.org and IzzyOnDroid), refusing an entry.json whose signature fails. Not done in the first
  round: a JAR signature verifier in Rust (PKCS#7/CMS over the manifest's digests) is its own piece of work. Server, M.
- **F-Droid: the signer the index lists at the first pin** (qa-fdroid-source-code N-14, 2026-10-24): index-v2's
  `manifest.signer.sha256` lists each APK's signing certificate; compare it with the APK's (`ApkIdentity.signer_sha256`)
  before the first pin and refuse a mismatch. No trust against a tampered index (it could change both), but it catches
  a mixed-up mirror; a natural part of the `entry.jar` round. Server, S.
- **F-Droid: the ABI filter** (qa-fdroid-source-code Q-4, 2026-10-24): the pick ignores `manifest.nativecode` and
  `usesSdk.minSdkVersion`; an app the repo builds per ABI gets one versionCode per ABI, often the highest an x86_64
  build. Drop versions whose `nativecode` is non-empty without `arm64-v8a` or `armeabi-v7a` (and a minSdk above the
  phones'). Waits for the first such app; Add App's ⓘ says the source suits apps with one APK for every phone. Server, S.
- **GitHub asset digests** (qa-fdroid-source-code (e), 2026-10-24): GitHub's Releases API lists `digest:
  "sha256:<hex>"` on assets (2025). Parse a well-formed one into `ReleaseAsset.sha256` (today `#[serde(skip)]`), so
  `stream_to_file` checks a GitHub download before the rename as it does F-Droid's; an asset without one (older
  releases) keeps today's behaviour. Server, S.
- **PWA: reload on return after 1 minute, not 10** (the user, 2026-10-24: "blir ett minutt for kort?" - no): the
  return reload in `static/reconnect.js` (pwa.md "Connecting") fires after more than 10 minutes hidden; make it 1
  minute. One constant + `jstest/reconnect.test.js`'s timeline; the save/details/typed-field/POST-answer rules stay.
  **Done 2026-10-24 on main** (`reconnect.js?v=3`, `AWAY_MS` = 60 s; jstests 25, `shell::` 22).
- **Google's developer verification (the user, 2026-10-24, keepandroidopen.org: "har jeg nettopp kastet bort mange
  uker"):** no. Google's enterprise FAQ (as summarised by bayton.org, 2025-09): apps installed by a DPC/EMM agent are
  exempt indefinitely - Vibb One is the device owner and installs every app from the catalog; Play apps on fully
  managed devices have until September 2027; installed apps are never removed; ADB installs and the "advanced flow"
  stay. Enforcement: Brazil, Indonesia, Singapore, Thailand from 2026-09-30; global "2027 and beyond" (Norway not yet).
  The open question nobody answers: the DPC itself at QR provisioning (the setup wizard downloads our APK from
  GitHub). Fallbacks: `dpm set-device-owner` over adb (documented), or registering the user as a verified developer
  with the release signing key (~25 USD + ID, a one-time job, no code change) - recommended when verification opens
  for Norway. Watch Google's Android Enterprise FAQ for the provisioning case; re-read before stable 1.0.
- **Zone page map grey on the iPhone** (the user, 2026-10-24, screenshot: zoom buttons and "Leaflet" shown, no
  tiles, no circle) - **fixed the same day on main**: `zones.js` fitted the map to `circle.getBounds()` before the
  map had a view; Leaflet projects the circle only once a view exists, so Safari threw `undefined is not an object
  (evaluating 'this._map.layerPointToLatLng')` right after `L.map` (Chromium tolerated it). Reproduced in Playwright's
  WebKit; the fit now uses a pure `zoneBounds(lat, lng, r)` (metres to degrees) with a jstest, `zones.js?v=2`. Lesson:
  a PWA page with Leaflet gets a WebKit pass before release (the Locate page was fine because it sets the view first).
- **Hold the app-list loads while `AppEnforcer.apply` runs** (2026-10-24, from the debounce's emulator check: 0 ANRs
  of ours in the provisioning storm, but ~1 load per restricted package - the enforcer's per-package DPM calls are
  spaced over 300 ms on a loaded phone, so the 300 ms debounce rarely merges them): `apply` tells the app-list
  consumer "held" at its start and "released" at its end (one load then; a cap of 10 s in case apply hangs), pinned
  in `HomeRound7Test`; nothing else changes. Launcher, XS; next launcher round. **Done** (branch `appload-hold`, merged
  2026-10-24, measured 2026-10-25 - see the debounce entry above). Follow-up, XS, not ordered: after a release, a longer
  quiet window (about 3 s quiet / 30 s cap) for callbacks about the packages the pass touched would fold the stragglers
  into one or two loads.
  **Follow-up built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, awaiting merge)**: the pass names each package it
  changes (`AppLoadHold.touch`), its release keeps them 2 min (`APP_LOAD_TRAIL_WINDOW_MS`, a chain of passes
  joined); a `LauncherApps` callback about packages all touched is the trail (`AppLoadHold.noteRequest`, pure
  `appLoadTrails`), and a burst of the trail alone loads after 3 s of quiet, at most 30 s (`appLoadBurstDue`); any
  other request in it keeps 300 ms / 2 s from its arrival, and the hold's end is one (code QA Q-2: the pass's own load
  stays 300 ms after it); a pass that touched nothing leaves the trail's window as it was (Q-3). `HomeRound7Test` (the jelly36 stragglers: 30 callbacks
  2 s apart -> 2 loads, 31 before). The storm run again is owed (`docs/testing/2026-10-24-home-round-run.md`,
  "Owed after launcher-small-a").
- **Jelly Star "App blocker"** (the user, 2026-10-24, dontkillmyapp.com/unihertz; two of three on the site's scale):
  Settings › Intelligent assistance › App blocker - Boot blocker, Start blocker, Background blocker, Background
  cleanup; on the Jelly Star only found through Settings' search. Design 19b §4 foresaw an OEM manager but had no name.
  (1) A setup-runbook step before enrolling: switch it off (or untick everything for Vibb One, Play services, Element X,
  the music app); (2) better, no manual step: find its package on the user's phone (`adb shell pm list packages |
  grep -iE 'block|unihertz|intelli|dura'`, the user owes the dump) and have the device owner hide/suspend it at
  enrolment like the relock blocks; re-check after an OTA. A MediaTek DuraSpeed phone (the Bluefox NX1, below) gets
  the same treatment if it comes. Launcher + docs, S.
- **Design r12, the music favourites tile + two small items** (the user, 2026-10-24; note
  `docs/design/r12-music-favourites-tile.md`, branch `r12-music-design`, awaiting the user's three confirmations: the
  tile's cover, last-hearted-first order, the bookmark rule): (1) a row without its own picture in a pictured list
  shows the series cover plain, no number (design 39 rule 1 changed: "tallet gir mer usikkerhet enn det tilfører
  verdi"); (2) skip 15 s both ways (was 15/30, design 50's pick); (3) one "Favoritter" tile playing every hearted item
  across entries - option B, all sources at once ("vi holder køen selv ... jeg sier vi tar alt i 1"), replacing r8's
  option C; Spotify on a room waits for 54-a2. Music, items 1-2 XS, item 3 L.
  - **Built** on branch `r12-music` (2026-10-24, the user's three defaults; Room 8: `favourites.heartedAt`,
    `now_playing.mix`; the note's "As built"); code QA + two fix rounds (E-1, E-1-Q-1) and the jelly36 run `docs/testing/2026-10-24-r12-music-run.md` done 2026-10-24; merged.
- **Android's lock screen shows the media card** (the user, 2026-10-24: "ødelegger litt illusjonen vi har skapt nå"):
  Android 13+'s "Show media on lock screen" (Settings › Sound & vibration › Media; `Settings.Secure.media_controls_lock_screen`)
  puts the media carousel on SystemUI's own face whatever our keyguard features say. Neither the device owner nor the
  music app may write it. (1) A step in `docs/setup/android-settings.md` (before enrolling, or during a pause while
  Settings is visible); (2) a pause-screen row "Media on the lock screen" opening `ACTION_SOUND_SETTINGS`, the same
  mechanism as the write-settings and fingerprint rows (rc.5). The user confirmed the switch on their emulator
  2026-10-24 ("den fiksen funket"); (1) done the same day in `android-settings.md`; (2) launcher, S; next launcher round.
  **(2) done 2026-10-24 (branch `home-round`)**: Settings' "Media on the lock screen" ("Medier på låseskjermen") row
  under "Fingerprint", always shown (`server/MediaLockScreen.kt`, `MediaLockScreenTest`): `ACTION_SOUND_SETTINGS`
  through the battery row's `systemPageIntent` (so only during the pause on a managed phone; `systemPageAccess`'s
  states, re-read after the pause's apply); no public action opens the Media sub-page, so the summary names the path
  ("Lyd og vibrasjon › Medier › Vis medier på låseskjermen av"). No state line (qa-home-round-code Q-1):
  `media_controls_lock_screen` has no `@Readable`, so SettingsProvider refuses it to an app targeting S+ (a device
  owner too) - the summary is the action line alone, a tap always opens the page, and the check is the switch on
  Android's page. `android-settings.md` names the row first.
- **A black flash behind Home after the Home button** (the user, 2026-10-16, emulator video): from an app
  (Element X), the Home button (the emulator's circle) shows Home's icons on solid black for ~150 ms
  (four frames at 30 fps) while the app slides away, and only then the wallpaper; Back has a clean
  transition (Home is already behind the app). Home's window is transparent with `windowShowWallpaper`
  (`launcherBaseTheme`), so the black is the compositor's: the system wallpaper isn't shown yet during the
  app-close transition - likely because our Home isn't the recents provider (quickstep/the stock launcher
  owns the gesture's go-home animation, so Shell runs a generic transition in which the wallpaper becomes
  visible only once Home is the wallpaper target), or design 32's black stand-ins (Home's black
  `TaskDescription`, `setRecentsScreenshotEnabled(false)`). Find which on the emulator (both navigation
  modes, kiosk on and off, a photo and a colour wallpaper) and fix without bringing design 32's lock flash
  back - e.g. Home draws its own `WallpaperGround` copy under the grid while a transition runs (the store
  already holds the one decoded photo). Launcher only. A second video (2026-10-16) shows the same black frame
  with a built-in gradient wallpaper (not only a photo), and Back's predictive animation clean; power
  off/on over the PIN lock showed no flash of content (design 32 holds).
  **Fixed 2026-10-24 (branch `home-round`, measured on `jelly36`, `docs/testing/2026-10-24-home-round-run.md`)**:
  the cause is Android's wallpaper not yet shown under Home during the app-close transition - before 2-3 black
  frames at 30 fps (one fully black), after 0 in three valid runs (a gradient, gesture navigation, kiosk off), Back
  clean on both, no seam seen when the copy goes. Home draws its own copy (`WallpaperGround`'s FULL ground: the
  store's colour, gradient or the one decoded photo, then the scrim - no new memory, Android's placement) after a
  HOME intent that brings it from behind (the Home button or gesture, every `HomeFront` start), for at least 700 ms
  and until `onEnterAnimationComplete` plus a frame, at most 2.5 s; ended when Home leaves the front (posted, so a
  second Home press during the transition keeps it - qa-home-round-code Q-2) and at the stop; never for Back, never
  at a lock-up (the content isn't on the window then; the black snapshot stays). Pure `ui/home/HomeGround.kt`
  (`homeGroundDue`, `HomeGroundTest`); design 59d step B's face leave folded in unchanged. Still owed (design 08's
  last section): 3-button navigation (with a photo: no zoom step when the copy goes - Q-7), kiosk on, a photo and a
  plain colour, the lock's leave on both pads, screen off/on over the lock (design 32).
  **Follow-up built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, awaiting merge)** - the one-frame zoom step at the
  hand-over (queue above): Home keeps its copy permanently, `homeGroundDue(contentShown)` (the content on the window,
  so never at a lock-up); the 2026-10-24 arrival window (700 ms floor, 2.5 s cap, the enter animation's end, the
  posted pause end, the rechecks) and design 59d step B's face-leave mark in `HomeFront` are gone (both covered).
  Back has no special case; since the code QA's fix round (Q-1) the phone book and kid Settings draw the same copy.
  Design 08's "The copy stays"; `HomeGroundTest`, `HomeFrontTest`. Emulator checks owed (Back's seam, Home -> phone
  book/kid Settings with no step, gfxinfo): `docs/testing/2026-10-24-home-round-run.md`, "Owed after
  launcher-small-a".

- **The last app still flashes for one frame at screen-on** (design 32's lock flash isn't gone; the user,
  2026-10-16, three emulator videos read frame by frame): turning the screen on over the PIN lock shows, for
  exactly one frame (~33 ms) before the lock, a stale picture of what was in front before the screen went off
  - an incoming-call screen "Ringer deg ... Pappa" with its status bar at 08:00 (the clock 13:3x; no
  `InCallActivity` alive afterwards), and after the parent opened Android Settings › Developer options during a
  pause, that page with its status bar at 14:18 on every power cycle (the lock at 14:23). Between, power
  cycles with Home or our own screens in front were clean. So design 32's dark lock-up (Home first, then the
  lock) doesn't keep the previous app's picture from the first frame; the old frame comes either from the
  task snapshot WM shows until the lock's first frame, or from the display's last buffer before the off
  animation. Next: (1) check the Jelly Star with a slow-motion phone camera - an emulator's display may
  behave differently; (1b) the emulator's own check, 2026-10-16: `dumpsys activity activities` right after a
  flashing cycle had the order right (PinLockActivity top, Home directly under it, Settings further down and on
  its search page, not Developer options) and the lock-up logged `NONE` (the lock already on top), so no task
  under the lock holds the flashed picture - likely the emulator's host-GPU frame buffer (a stale frame from
  hours before); test with `-gpu swiftshader_indirect` (a flash that goes away = the emulator, not the phone);
  `adb shell screenrecord` can't follow a screen-off (INVALID_LAYER_STACK, an unusable file); (2) on the emulator, `dumpsys activity activities` right after a screen-off (which task
  is top, did Home's dark start land) and `adb shell screenrecord` at the screen-on; (3) the user's idea - draw the lock while the screen is still on: an app never sees the power key and
  `ACTION_SCREEN_OFF` came after the off animation in design 32's measurements, but the ~300 ms colour fade is
  a window in which the display still draws; measure, on the Jelly Star and the emulator, when
  `PowerManager.isInteractive` turns false (no callback - poll only if cheap), a `DisplayManager.DisplayListener`
  state change and `ACTION_SCREEN_OFF` arrive relative to the fade's start, and if one comes early enough start
  the lock then, so it has drawn before the panel goes dark; (4) design 32 §7's ruled-out
  options stay out (no turn-screen-on on the lock, no cover overlay, no accessibility service). Our own call
  screen also gets `finishAndRemoveTask()` and `setRecentsScreenshotEnabled(false)` (a caller's name must
  never show on a lock screen), and the same for our other own-task screens. A design note (design 32b)
  before code.


- **A time rule shows as a card on the PIN lock** (the user, 2026-10-16, clarified: "vanlig låseskjerm med et
  kort à la musikkspillerkortet som forteller deg at skolemodus er aktivert"): while a time rule locks, the
  lock's glance page stays as it is (clock, date, notification rows, the music tile, "Lås opp", "Nødsamtale") and
  gets **one more card in the music tile's style**: the kind's glyph and "Skolemodus til 14:30" (school; bedtime,
  custom and the used-up budget the same way with their own glyph and name; `ClockFormat.formatUntil`, the end
  only when it is certain, as on the time-rule screen). Unlocking still leads to today's time-rule screen
  (design 34 P6) with what the rule allows. The card is part of `glanceFit` (it may push rows to "+ N varsler
  til" on a small screen; it never scrolls), follows the lock re-checks (`TimeRulesRuntime`, the boundary
  alarm) and goes when the rule ends or is lifted. Decided by the user (2026-10-17): for **every** lock
  reason (school, bedtime, custom rules and the used-up screen time), and the card is **information only** (no
  tap action; TalkBack reads it as text).
  Launcher only; a mockup first, then the usual process.
  **Built** 2026-10-25 on `r13-rule-card` (design r13 step 1, "Implementation status"): `lock/RuleCard.kt`, the card
  under the alarm line, the certain end shared with the time-rule screen. Code-QA'd (`qa-r13-rule-card-code.md`, fix
  round done); awaiting merge and the emulator checks (§1.7).
  **Merged 2026-10-25 (3eb0c025) after code QA (`docs/review/qa-r13-rule-card-code.md`, 0 High / 1 Medium / 7 Low, all fixed). Emulator run 2026-10-25 (`docs/testing/2026-10-25-r13-run.md`): the card under a school rule, the notification's time left, the request end to end (tap → 200 → inbox + push switch → Time page row → a +15 lift clears it) all PASS. Server part in `server-v0.50.0`; launcher in `launcher-v0.37.0-rc.2`.**
- **Enrolment before Home's first start** (glance-topinset run, qa-glance-topinset-code Q-3): a phone enrolled from
  Vibb One's Settings before Home ever started on screen resets its preferences at the next process start
  (`Application.initRest`: `internal.started_before` unset -> `resetPreferences`). The cached policy is dropped, so
  the kiosk stays off and Android's lock isn't requested until the next sync. Check whether any real provisioning path
  enrols before that start (QR provisioning -> `ProvisioningModeActivity` -> `PolicyComplianceActivity` -> Home: in
  which order, and can the QR's enrolment code be applied first?). If one can, mark `started_before` at enrolment or
  make the first-launch reset skip an enrolled phone. Launcher only.
  **Done 2026-10-24 (branch `write-settings-row`)**: QR provisioning and an exported-Settings enrolment can hit it (and
  the reset drops the token too - a de-enrolment); the reset now skips a phone with a token or `enrolled`
  (`firstLaunchResetDue`); design 59d §8.17.
- **QR enrolment asks for no sync** (qa-write-settings-row-code Q-8): `applyProvisioningExtras` succeeding should
  `SyncRunner.request(.., "enrolled")` as Settings' "Enroll now" does, so the first policy doesn't wait for the backstop. Small.
  **Built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, awaiting merge)**: `applyProvisioningExtras` asks for the sync on
  the enrolment's success - both QR paths (Android's provisioning and the in-app scanner) come through it;
  `ProvisioningModeTest` pins it.

### Before stable `music-v0.4.0`
- **A context-mode playlist doesn't play on the real SDK - CLOSED 2026-10-20** (the user: a second parent-owned
  playlist also plays fine on the release app; "da trenger vi ikke feilsøke mer"). Left: the wording fix alone (a 4xx
  before the play must never read "Trenger internett"), in the next music round. History: the release music app
  (0.4.0-rc.4 era) on the emulator played a playlist the parent's account owns and the kid's only follows
  ("RÜFÜS DU SOL – SOLACE", context mode, the screenshot) from the first tap. So the 2026-10-16 failure was either
  intermittent (a 403 that comes and goes, the web player not yet Spotify's active device, a settings call before
  the play) or fixed indirectly between rc.1 and rc.4. Before stable 0.4.0: three more cold tries (app killed,
  after a reboot, on mobile data) on that playlist and one other not-owned one; if all play, close this with the
  wording fix alone (a 4xx before the play must never read "Trenger internett"); if one fails, the debug app and
  `adb logcat -s VibbSpotify` on that tap. Original report: (the user, 2026-10-16, emulator, server 0.26.0,
  music 0.4.0-rc.1): a playlist the child's account neither owns nor collaborates on (context mode, `spotify:
  "context"`) shows "Trenger internett" at once and never plays, while NRK and the rest play; made a
  collaborator (list mode), the same playlist plays fine. So the list path works with the real SDK and the
  context path doesn't. Suspects: the `context_uri` start (no live track yet: from Spotify's start, else
  `offset.uri`), how its answer maps to a problem (a 403/404 from the play call landing as `UNREACHABLE`), or
  Spotify's development-mode limits on playlists the app may not read (Spotify-owned/editorial ones). Rule out a host
  the web player refuses (a debug build counts them) - list mode works with the same list. Find it
  with the debug music app on the emulator (`spotifyLog`, the state recorder) and the real account; fix with a
  test that replays the recorded answer. Must be fixed before stable music-v0.4.0. **What the spike did that worked
  (2026-10-20 comparison, `docs/testing/spotify-spike.md` item 10, branch `spotify-spike`)**: one bare
  `PUT /v1/me/player/play?device_id=…` with `{"context_uri": "spotify:playlist:…"}` on a playlist the parent owned
  and the kid only followed - no `offset`, no shuffle/repeat calls first, no transfer - and it played (HTTP 204).
  Our start (`SpotifyApi.start`) sends shuffle off and repeat off before the first play of a device session, then
  the play, in context mode with `offset.uri` = the stored live track when there is one; a failure before the play
  (`playSentAt` still unset) can't become `CONTEXT_REFUSED` and falls to `UNREACHABLE` = "Trenger internett" - which
  is also the wrong word for any 4xx. So the editorial-playlist theory is out (the kid could be made a collaborator,
  so it is a user's playlist), and the cheap bisect is: a debug switch that starts a context like the spike (no
  settings, no offset) - plays? then add the settings calls back, then the offset; whichever step breaks it is the
  bug. Either way `adb logcat -s VibbSpotify` on the failing tap names the call and status in one run. Also: a 4xx
  before the play should read as Spotify's refusal, never as "no internet".

- **R1 (Medium)**: after 3 of 14 short calls Spotify stays paused (the page reports paused ~5 ms
  after our resume; the launcher's HOLD/RELEASE are right).
  `docs/testing/2026-10-14-emulator-recheck.md`.

### Parked

- **A faster Spotify start on the phone** (asked 2026-10-25, parked the same day - the user: "vi lar det ligge mtp spotify, ikke implementer noe mer der nå"): measure the web player's cold start, then pre-warm page/SDK/token/Connect device (a visible "Vibb" device while the app is open) and 1 s polls on a room after 54-a2. Nothing Spotify-related is built until the user reopens it.
- **Aiphor Bluefox NX1 as a second phone** (the user, 2026-10-24, romobil.no, 2 590 kr): 4" 540x1168 LCD, Helio G81,
  Android 15, 3000 mAh, 4G, no NFC, no fingerprint (PIN/pattern/face); Vipps works per the seller (so Play Integrity
  passes: Play-certified). Before buying: VoLTE with Telenor/Telia (2G/3G are going; the one deciding question), the
  ROM's background killer (DuraSpeed), and the density - at 320 dpi it is 270x584 dp, narrower than our 320 dp floor
  (the Jelly Star is 320x569 dp): read `wm density` first; narrower than 320 dp means a pass over the glance, the pad
  and the Home grid. Otherwise the Jelly Star's device checklist. RAM: 4 GB is plenty (the emulators run everything
  on 2-2.5 GB); 128 GB storage is the reason for the bigger variant.
- **Crash reports group by versionCode only** (qa-rc-to-rc-code R2R-6, 2026-10-23): `crash/CrashReports.kt` records
  `BuildConfig.VERSION_CODE` and the server's `device_crashes.app_version_code` shows it, so rc.1 and rc.2 crashes
  (one code) read as one build on About This Phone. Moot from `launcher-v0.36.0-rc.4` on (every build its own
  versionCode, the "own versionCode" entry under Done); only the old-rule RCs still share one. Not changed with the RC-to-RC fix; a fix sends the versionName too
  (a new field in `POST /api/devices/crashes`, both sides in one commit) or shows the report's `app_version` beside it.
- **The Spotify host list is good enough** (the user, 2026-10-16: list-mode playback works on the emulator
  with every blocklist on, so the DNS capture is dropped): mark it final in one commit - the "PROVISIONAL"
  wording out of the three `spotify_hosts.json` copies, the six constants' doc comments, design 26c and the
  CLAUDE.md files. If a host ever turns out missing, it shows as a blocked request in the phone's DNS log
  (or the web player's refused count in a debug build) and is a one-line fix in all three copies.
  **Dropped 2026-10-25** (the user: "Glem spotify vertslisten"): the PROVISIONAL wording stays as it is; nothing to do.

- **§6l part B and the 26c QA checklist** on the Jelly Star with the real account (the user's).
- **Emulator re-check leftovers**: R2 the debug proxy stalls after a seek (debug only); R3 no
  now-playing bar after a list→context flip; R4 the VPN session name and launcher notifications
  in English before the language arrives from the server. Not yet checked (time-box): Home in en
  with 4 columns, the phone book, the keypad after a font-scale change, the time-rule
  "Til … p.m.", the PWA's About row, a VoIP ring while paused.
- **Remove the unused androidx `palette` dependency** (unused since design 34 step 1), with its
  verification-metadata entries.
- **Design 46's queue view: a context entry's list shows the playing track and the next 20 from Spotify's queue**
  (the user, 2026-10-20: "Legg køvisning for neste 20 på longlisten vår, så husker vi på den til senere"). Designed and
  QA'd in `docs/design/46-context-queue-and-play-marks.md` §0b/§1.1-§1.3 (+ `qa-music-round-design.md`): the server
  adds the scope `user-read-playback-state`, every Spotify account logs in again, the music app reads
  `GET /me/player/queue` after each start/track change, a tap on a playlist/album row starts there. Only worth it for
  playlists no logged-in account owns, and for artists; 46b (a reader account) covers the family's own lists.

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
- **Investigate: does one bad episode picture URL hold a listing's other pictures?** (from qa-r5-design.md r5-3,
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
- **`PHONE_SERVER_URL` in `.env`**: the phones' server address as an env default; the DB value
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

### Tooling and docs

- **Emulator AVD "jellystar"**: the "Nexus S" profile (no display cutout) at 480×854 @240 instead
  of the Pixel 7 profile; make it the default in `docs/testing/emulator.md`. Check the real
  Jelly Star's `wm density` first (the docs assume 240).
- **Emulator speed**: a lighter image (Android 16 ATD / google_apis) for most checks, and a
  `systemd-run --user --scope -p MemoryHigh=4G -p MemoryMax=5G` around the emulator.

### Done (not released yet)

- **CI: arm64 image on a native arm runner (the user, 2026-10-24, branch `ci-native-arm64`)**: done - no QEMU; the
  release and CI image jobs are an amd64/arm64 matrix (`ubuntu-24.04`, `ubuntu-24.04-arm`), each built and smoked
  natively, then `server-release-image-merge` joins the two digests and tags `X.Y.Z` (and `latest`) as before.

- **Back from Apps › a phone goes back to Apps (2026-10-24, branch `pwa-polish-a`)**: the user: "på apps, om jeg går
  inn på feks Sverre sin tlf og så trykker tilbake - så kommer jeg til device taben for Sverre? Føltes unaturlig". The
  Apps tab's links carry `?from=apps` / `?from=app:<id>`; the phone's Apps page then lights Apps and its back link
  returns there, kept through every save (pwa.md, "Opened from the Apps tab").
- **The PWA's start-up splash (2026-10-24, branch `pwa-polish-a`)**: the user: "en forvokst utgave av ikonet ... uten
  tekst; bare en stor logo og natt bakgrunnen". The launcher's resting Vibb mark on night, 13 iPhone portrait PNGs
  from `scripts/pwa-startup-images.py`; Android's manifest background night (pwa.md, "Start-up splash"). The real
  iPhone cold start is the user's check.
  **The hold reverted 2026-10-24** (the user: "den flakker! Revert til første"): the one-second overlay from
  `pwa-splash-hold` (server 0.44.0) is gone again - `splash-hold.js`, the partial, the CSS, its tests and the
  script's SVG output; the iOS start-up images and the manifest's night stay as `pwa-polish-a` built them. The review
  `docs/review/qa-pwa-splash-hold-code.md` stays as history.

- **The lock glance round (2026-10-24, branch `r11-glance`, design r11; merged 2026-10-24; emulator run `docs/testing/2026-10-24-r11-glance-run.md`, all PASS)**: the user's
  three asks on `jellystar2` - (1) the whole page scrolls (clock, date, tiles and rows as one; r9's fit is gone, at
  most eight rows then "+ N varsler til"), (2) the music tile is compact from two rows, (3) a row swiped sideways is
  dismissed (a missed call keeps the phone book's badge). The PWA's lock-page line says so. Code QA's fix round
  (Q-1..Q-10) done, plus the user's two additions: the page back at its top at every return to the glance, and a
  tappable "more" line (the keypad without a target).

- **Every release build has its own versionCode (2026-10-23, branch `versioncode-rc`)**: the old rule
  (`X*1_000_000 + Y*1_000 + Z`, an RC one less) gave every RC of a version one code (0.36.0-rc.1..3 are all 35999), so
  Android refused rc.2 over rc.1 as the same version and the RC-to-RC fix had to compare versionNames on both sides.
  The real fix, the user's: `versionCode = X*10_000_000 + Y*10_000 + Z*10 + N`, N = the RC number (1..8) or 9 for the
  release (X <= 199, Y and Z <= 999), so Android's own rule orders every build. One script for both apps,
  `scripts/release-version.sh` (`--selftest`: the examples, the ranges, malformed tags, the shared contract), used by
  `launcher.yml` and `music.yml` and self-tested in their always-run changes jobs. Every new code is above every old
  one (old max 0.36.0-rc.3 = 35999; music 0.5.0-rc.3 = 4999), so Android installs any new build over any old one.
  But an old build decides with its own parser, which reads `launcher-v0.36.0-rc.4` to rc.8 as 35999 - its own code:
  an installed rc.3 takes rc.4 only through the legacy name rule and `same_code_update_v1`; rc.1/rc.2 never take
  rc.4 to rc.8 by themselves (they move at 0.36.0, which their parser reads as 36000, or by hand - below), and the
  server, reading a tag the way the reporting build does (`install_now::tag_code_as_read_by`, qa-versioncode-rc-code
  Q-1), shows them TooOld rather than an Install Now that can only answer "Already on".
  **First tags under the new rule: `launcher-v0.36.0-rc.4` (360004) and `music-v0.5.0-rc.4` (50004)**; the script
  refuses a tag below them, and the launcher's `launcherTagVersionCode` and the server's
  `install_now::launcher_tag_version_code` read the older (already built) tags by the old rule - else the server would
  show a phone on 0.34.0 "behind" the 0.34.0 row - pinned by the new shared contract
  `server/testdata/launcher_tag_codes.json` (byte-identical in the launcher's test resources). The server's About This
  Phone reads the music app's version by the same cutover ("0.5.0-rc.4"). `launcherBuildAhead`/`build_ahead`'s name
  rule and the capability `same_code_update_v1` stay as they are, documented as the old rule's legacy (for phones on
  0.36.0-rc.1..3; both stay load-bearing until no phone runs one of those - never remove them before). Nothing
  already tagged is rebuilt.

- **An RC over an RC of the same version installs (2026-10-23, branch `rc-to-rc-update`; code QA
  `docs/review/qa-rc-to-rc-code.md`, 0 / 2 Should / 4 Nit, all fixed)**: every RC of one version shares a versionCode
  (`launcher.yml`; an RC of X.Y.Z+1 has X.Y.Z's), and both sides refused a same-code install, so 0.36.0-rc.1 never took
  rc.2. The rule, both sides (launcher `launcherBuildAhead` in `SelfUpdatePlan.kt`, server `install_now::build_ahead`):
  ahead = a higher code, or the same code under a newer versionName (X.Y.Z, then an RC before its release, RC numbers
  in order; two names that can't be ordered count as ahead, an unknown name never does) - for the hourly self-update
  step, the pending-APK check (the archive's `versionName`; signer and package checks unchanged) and Install Now's
  settle (the record keeps `fromVersionName`). The server offers a same-code release (Install Now) only to a launcher
  whose report claims the new capability **`same_code_update_v1`**; without it the row is TooOld ("Install Now works
  from this update on"). **Owed once by hand**: phones on a pre-fix build (0.36.0-rc.1/rc.2, code 35999) decide by the
  code alone and never pull the first fixed RC of the same version by themselves - step the test phones onto it once
  with `adb install -r` (same code, same key; `apksigner verify` against `RELEASE_CERT_SHA256` first), or wait for the
  next stable release (a higher code). **Still owed after the own-versionCode rule** (the entry above): an
  rc.1/rc.2 phone's own parser reads `launcher-v0.36.0-rc.4` to rc.8 as 35999, its own code, so it still needs the
  one-time `adb install -r` (now onto rc.4 or later: 360004 > 35999, an upgrade) or waits for 0.36.0 (36000 to its
  parser); an rc.3 phone takes rc.4 by itself (the name rule). The boot cover's "one more try after an update" counts
  the name too (R2R-6).

- **Launcher round 7 (2026-10-20, merges 5248fa4e l7-a and e1569747 l7-b; design QA `docs/design/qa-r7-launcher-design.md`
  + one revision check; code QA `docs/review/qa-l7-a-code.md` (0 / 0 / 2, fixed) and `qa-l7-b-code.md` (1 High - the
  force-stop protection, dropped - / 1 Medium / 4 Low, fixed); server 1102, launcher 1464, music 970 green)**: any
  allowed app rings over the lock (no budget; `lock_state.voip_rang` -> "rang over the lock" on the Lock page); the
  per-phone "Wake the screen for a notification" switch (migration 0084; a notice wakes the lock's glance page, off
  again after 15 s untouched via `lockNow()`, never with a lock dialog open or TalkBack on); catalog apps'
  `battery_exempt` and the Apps page's "Background:" line (**both removed again the same day**, design r7 §2.5; the force-stop protection NOT built: a protected package
  can't be restricted by the device owner either); the Fossify items (icon eviction on update, Home press to the top,
  one-tile grid updates, coalesced `loadApps()`, the inherited Settings escape closed). Server 0.32.0 also carries the
  0.31.1 content (Now gone, one clock per Activity row, Install Now's value on one line). **Device checks owed**: the
  design note's §1.3/§1.4, §2.3/§2.4, §3.3/§3.4 and PLAN's §4 lines. Goes out with launcher-v0.32.0 + server-v0.32.0
  (with music-v0.4.0).
- **The music round (2026-10-20, merge 9a7017a0 of `music-r-b`, which holds `music-r-a`; design QA
  `docs/design/qa-music-round-design.md` + one revision check; code QA `docs/review/qa-music-a-code.md` (1 High / 2
  Medium / 5 Low, all fixed) and `qa-music-b-code.md` (0 / 3 / 5, all fixed); music suite 970, launcher 1431, server
  1096 green)**: **design 49** shuffle and a three-state repeat in the player's control row for Spotify music and
  non-resuming own files (`controlSlots`), remembered per entry (Room 6 `repeat`), the phone's ExoPlayer repeat mode
  with a one-lap guard on the error move-on, Sonos' `advance()` wrapping/replaying, Spotify's own shuffle/repeat set to
  the entry's want (list mode: our shuffle, `repeat=track`, a gated restart for repeat-all; the end rules never read a
  repeating track as the end), `KidPlayer` refusing a controller's set-mode commands; **design 50** ±15/30 s for what
  resumes (the lock's `now_playing` gets `controls: skip|music`), "Til slutten" in the sleep sheet (each renderer pauses
  at the item's end by its own means, survives queue loads and output switches, beats repeat, Spotify's repeat off
  while it is on), the carousel opening once on the last played tile, dimmed unplayable tiles with one line and no
  arrow, favourites per track/episode (Room 6 `favourites`, the "Favoritter" category tile that only filters);
  **design 46 §1.4** the play arrow on every non-dimmed tile (46's queue view parked). **The user's calls pending**:
  the sleep bar reads "Sov · til slutten" without the remaining time (the seek bar shows it). **Device checks owed
  (the user, emulator)**: 49 §3 and 50 §3 as written in the notes' Implementation status (own music shuffle/repeat
  remembered across a restart, the flip hidden while shuffled, a Spotify playlist shuffled keeps the cover tap's
  current item first, repeat one/all with the fakes; a podcast's ±15/30 on the phone and a fake room; "Til slutten"
  after a manual step, with repeat one on the phone (one pause, no second play) and with `fake-spotify.py --repeat
  track`; the app opening on the last played tile; dimmed tiles on cell with streaming off; a heart in a list showing
  under Favoritter; the heart at 320 dp). Goes out with music-v0.4.0 (the user's go).
- **Server 0.31.1 content (2026-10-20, merge daff2443 of `r7-c`)**: the summary's Now section is gone (the user: "kan
  vi ikke bare ta vekk hele den now biten?"), End now for a running pause sits under Quick Actions, ended lifts show
  on Device › Time only, each Activity event row shows its clock once; the running version beside Install Now never
  wraps. Goes out with server-v0.32.0 (0.31.1 skipped).
- **Server round 6 (2026-10-20, merges d2b6563b r7-a and 5b382da5 r7-b; design QA `docs/review/qa-r6-server-design.md`
  + one revision check; code QA `docs/review/qa-r7-a-code.md` (0 High / 0 Medium / 4 Low, all fixed) and
  `qa-r7-b-code.md` (0 / 1 / 3, all fixed))**: **design 48** the summary's Today and Recent become **Now** (the lifts
  with End now, the PIN notices) and an **Activity** log at the bottom (commands and events of the last 7 days, 10
  rows, newest first, a cancellable command at any age, "Everything for this phone ›"); Locate's Recent becomes
  **Requests** (what waits, what failed or expired in the last 24 h, the newest real result of ring/lock/wipe - a
  parent's Cancel never hides it); every command row carries the clock words; `device_commands` pruned after 30 days,
  never one that may still reach its phone (`48-activity-log.md`); **design 51** "Show message text" per ticked app
  on the lock page (`POST /devices/{id}/lock-screen/apps/text`, migration 0083 with the backfill from the old global
  switch, policy `lock_glance = {"apps":[{"package","show_text"}]}` pinned on both sides, the launcher's
  `GlancePolicy.apps: Map<String, Boolean>` reading both shapes) (`51-glance-text-per-app.md`); **the PWA's bell** in
  the top right corner on every tab (the + before it), Music's Edit down by the Library header; **the library holds
  500 entries** (a sizing guard, 200 before); **the music notes** 46/49/50 revised for the music round. **Release
  notes**: an older launcher shows missed calls only on its lock until it updates to 0.31.0; a new launcher on an
  older server shows the sender only. **Device checks owed (the user)**: iPhone - the summary reads Quick actions,
  Now, then Activity after Blocked Apps and Locked Again; Unlock for 2 h shows its row with the clock and Done; a PIN
  event sits between command rows in time order; End now and Cancel never jump; Locate shows Requests only while
  something waits or just failed; the bell in the corner on all five tabs with the + beside it on Devices and Apps;
  Music's Edit by Library; emulator - tick two apps on the lock page and turn the text on for one, a message from each:
  the sender for both, the text for one, neither switch jumps the page. Goes out with server-v0.31.0 and
  launcher-v0.31.0 (tagged together). Then (r7-c, the user after 0.31.0) Now went: the pauses End now can still end sit at the
  end of Quick Actions (`#quick`), the PIN notices are gone from the summary, and an Activity row shows its clock once.
- **Launcher round 6 (2026-10-20, merges 8d17d549 r6-a and c282bb0c r6-b; design QA `docs/design/qa-r6-launcher-design.md`
  + one revision check; code QA `docs/review/qa-r6-a-code.md` (0 High / 1 Medium / 3 Low, all fixed) and
  `qa-r6-b-code.md` (0 / 3 / 7, all fixed))**: **a parent-PIN event asks for a sync at once** (`SyncTriggers.pin`
  after the final verdict, never during a running lockout; foreign access, battery and boots already synced);
  **design 41 the flashlight** - `Torch`/`TorchRules`, the Home tile before Settings (lit = a filled peach disc),
  the round button beside Nødsamtale on the lock, off after 10 min (`setExactAndAllowWhileIdle`, re-armed when a
  switch-off is refused) and at a call's begin (MusicCalls' edges), never at screen-off, no camera-policy lift (a
  refused ON under the lock hides the lock's button) (`docs/design/41-flashlight.md`); **the lock's music tile
  rendered alone** (a session callback renders the tile, the page refits only on presence/height change, callbacks
  coalesce per frame; "4:48 · 25:36" under the bar on both tiles - the compact tile's line was dropped again on the user's call - "det holder med tiden på den
  fulle" - so the notification row stays) (`r6-launcher-small-items.md`); **design 42**
  - Fossify Clock (`org.fossify.clock`, the catalog's, signer `affdb124…` pinned and verified) as the clock app:
  the alarm exemption widened by it, a next-alarm line ("Alarm tor 07:00"), a timer tile counting down from its
  notification's title and a stopwatch tile counting up, "Tiden er ute" with one Stopp button firing Fossify's own
  Dismiss broadcast (background starts denied), nothing for any other channel, the tap = keypad then Fossify, the
  lock never steps aside for a timer (`42-lock-clock.md`, Fossify's source read). **Device checks owed (the
  user)**: emulator - a parent-PIN use/wrong/lockout reaches the inbox within seconds and a correct entry uploads no
  `wrong`; a 2-minute Fossify timer ending with the screen LOCKED rings, the tile says "Tiden er ute" with Stopp,
  Stopp silences it, the lock never moved; the stopwatch counting up on the lock; an alarm 2 minutes ahead with the
  screen locked brings Fossify's AlarmActivity up (the `USE_FULL_SCREEN_INTENT` question - if not, Fossify's own
  prompt at first open is the setup step); "Alarm tor 07:00" for a far alarm; a ringing alarm shows no tile; Google's
  clock timer shows nothing; the lock tile recording (ffmpeg scene changes: one change per tap) and the music app's
  player screen recorded the same way; Jelly Star - design 41 §4 (the torch under the camera policy, the camera
  gesture while lit, 10 min, a call, screen off/on, the camera app afterwards). Goes out with launcher-v0.31.0
  (tagged with server-v0.31.0 once server round 6 was in - the user: no tags until everything is in).
- **Round 5 (2026-10-20, server 0.30.0; merges 6cf45ae4 r5-a and the r5-b merge after it; design QA
  `docs/design/qa-r5-design.md` + one revision check; code QA `docs/review/qa-r5-a-code.md` (0 High / 0 Medium / 3 Low,
  all fixed) and `qa-r5-b-code.md` (0 / 1 / 2, all fixed))**: **design 43** the PWA's app shell - the document never
  scrolls, `main` does, the tab bar a plain flex child (no `position: fixed`), `touch-action: manipulation` on `main`
  and the bar (no double-tap zoom), `scroll-restore.js` keeps the place in `main` and restores it on a history Back and
  on reload (the place record beats the fragment there), `tab-bar.js` gone (`docs/design/43-pwa-app-shell.md`);
  **design 44** Locate's Recent shows only its own commands, the override commands' Recent sits on the phone's summary
  under Quick actions (rows Today's card already shows are left out), a Locations page behind a row with a span pick
  (12 h default, remembered per browser), dots on the trail, `locations.json?since=`, the map script in
  `static/locate.js` with data- attributes (`44-locate-history.md`); **design 45** `/notifications` is the inbox alone
  with a gear to `/notifications/settings` (This Device, Getting Notifications, Notify Me About), opening the inbox
  marks the shown rows read (the dots stay for that visit), the gear carries `data-unread` so the Home Screen badge
  follows (`45-notifications-inbox-first.md`); **design 47** `/music/spotify` opens on the add form, the Spotify setup
  lives in Music › Settings as its own group with `spotify-` ids, each account row has a "Plays on" picker
  (`POST /music/spotify/accounts/{id}/plays-on`), Storytel's "On phones" opens per-phone switches
  (`POST /music/storytel/phones/{id}`), the Settings hub's row lost its "N unread" (`47-spotify-add-first.md`); **the
  small items** (`r5-server-small-items.md`): the push's `navigate` carries `?push=<delivery>` and an admin-router layer
  marks the delivery opened (+ the event read) with a 303 that drops the key - "Opened on iPhone · Safari"; the device
  row's quiet "· N since one was opened" from `push_subscriptions.last_opened_at`; `command stream opened/closed` log
  lines with the age; migration 0082 (`art_more` backfill with `art_due`'s condition + `last_opened_at`; next free
  0083); Send a Test's rows poll `subscriptions.json` for 30 s. **Device checks owed (the user)**: iPhone - the bar
  still after > 10 min in the background on Device › Time, no double-tap zoom on a tab or a deep row (pinch still
  zooms), a save never jumps (Home's switches, Lock › Parent PIN, a low group on Time), a history Back to the long
  Devices list and a reload land at the place (the page's "‹" opens at the top by design), the editor bar right under
  the status bar on Device › Time with Save not covered (also landscape), login with the keyboard up, Time rules' last
  field with the keyboard up and down with no offset bar, the Locate map drags without the page scrolling, a bell of 3
  → inbox dots → bell and badge 0, Turn On/Send a Test/Remove from the gear's page back to their groups, a tap on a
  notification lands without `?push` and the inbox says "Opened on", Music › From Spotify opens on the form, Music ›
  Settings' Spotify group saves/logs in (the 303 through the CSP)/removes without a jump, "Plays on" and the Storytel
  switches save in place; desktop (the Pi's Chromium) - the scrollbar at the window's edge, the wheel over the margins,
  the column steady; emulator - two `geo fix` positions: trail, dots, the row's count, the Locations page, a tap pans,
  the spans; after 0082 the old entries fetch their pictures at the next sweep; `docker compose logs` shows the stream
  lines. Goes out with server-v0.30.0.
- **Round 1 (2026-10-18, main 5911178a; QA: `docs/review/qa-r1-server-code.md`, `qa-r1-apps-code.md`)**:
  the Locate marker; the tab bar still in a narrow window (`html { overflow-y: scroll }`) and inset on an iPhone
  (`viewport-fit=cover`, `tab-bar.js` - **the user verifies on the iPhone**: the bar above the home indicator, and
  back after the app was in the background > 1 min; if the resume still fails, the app-shell fallback); the
  older-phone warning names only the old app; phones nudged on a new catalog release; the music app syncs after its
  own update; no self-update download for the running version; the Safety Center nag cancelled (Safety Center itself
  can still show in the kiosk - no clean signal exists to send Home to the front; ChooseLock stays blocked); the
  contacts-row swipe decided from its real DOWN (new: a swipe up from a contact opens the drawer, approved
  2026-10-18); Spotify track pictures ahead of play (50 a list-mode entry, 100 a check on the server, at most half
  of the day's budget). Goes out with server-v0.26.1, launcher-v0.27.0, music-v0.4.0-rc.2.
- **Round 3 (2026-10-19, tagged on the user's standing go "push og tag når du er der"): server-v0.28.0 (migration 0080;
  designs 37, 38, 39's art pass; the PWA fixes: the switch knob, the Device › Apps toggle jump, the Notifications
  flashes), launcher-v0.29.0 (designs 37 and 38), music-v0.4.0-rc.3 (design 39).** Device checks owed per design.
- **Hotfix round (2026-10-19 evening, the user's go): server-v0.28.1 (the iPhone push receipt: Safari's declarative
  `pushnotification` event confirms the push, the receipt route outside the CSRF guard) and launcher-v0.29.1 (the web
  filter answered no DNS at all in 0.28.0/0.29.0: `protect()` on an unbound socket - `docs/review/qa-vpn-protect-code.md`).**
  No live phone ran the broken builds (the user tests on emulators only).
- **Round 4 (2026-10-19 evening, tagged on the user's go "Er vi klare for 0.29.0 nå da?"): server-v0.29.0 (migration 0081; every episode picture
  fetched with no source caps - the user's decision -; the iPhone push receipt: `PUSH_PAYLOAD` declarative by default
  with `classic` as the switch, `pushnotification` handled, the receipt route outside the CSRF guard, a log line per
  receipt naming the worker event and version, the page asks for a worker update at open), launcher-v0.30.0 (the
  playing item's pictures at once wherever streaming is allowed, every ticked entry's pictures on Wi-Fi, the courier
  paced; `docs/review/qa-art-asap-code.md`), music-v0.4.0-rc.4 (the want set, the 1000-hash pages, the Spotify
  look-ahead for the playing entry only).** Suites on main 92147f08+: server 1067, music 922, launcher 1400.
- **Hotfix server-v0.29.1 (2026-10-19 night, the user's go "tag når du er klar"): the iPhone receipt found - shipping
  Safari fires `push` with `data` null and its proposed notification on the event for a mutable declarative payload
  (WWDC25 session 235), never the explainer's `pushnotification`; the worker shows and confirms from it
  (`e=push-declarative`; `PUSH_PAYLOAD=classic` had confirmed at once, the proof). `jmt17.google.com` on the push
  service list (Chromium without Google's API keys - the user's Pi); a log line per accepted push and the host of a
  refused push service at Turn On.** Owed: the iPhone test on 0.29.1 with `PUSH_PAYLOAD` unset.
- **Hotfixes server-v0.29.2/3/4 (2026-10-19 night, each on the user's go): the declarative receipt found and fixed by
  a probe in the worker.** 0.29.2: the worker posts a probe of every push event (`push probe` in the log) - on the
  user's iPhone (iOS 26) the worker runs and gets `push` with `event.notification` carrying `d`/`t`/`b`, as WebKit's
  source says, and still no receipt. 0.29.3: the declarative receipt goes before the show; the probe's second step
  reports `showNotification` failing with a TypeError. 0.29.4: WebKit's declarative path refuses a replacement
  without `navigate` as an option (ServiceWorkerRegistration::showNotification: "did not include NotificationOptions
  that specify a valid defaultAction url") - the option added, absolute and same-origin; `shown: ok`, receipt
  `event=push-declarative`, the replacement shown with the badge. Also `jmt17.google.com` confirmed end to end from
  the Pi's Chromium (a dead subscription there came back 410 once; Turn On again). **0.29.5 (tagged 2026-10-20 on
  4852bb62)**: the replacement the worker showed was accepted and never displayed on iOS 26 (six tests), so the
  declarative path posts the receipt and sets the badge and shows nothing - Safari shows its own notification; the
  payload carries `app_badge`; the probes are out; a tap on Safari's notification opens its `navigate`. Owed: the
  iPhone test (banner, badge, `event=push-declarative worker=v0.29.5`) - **failed: nothing displayed** (the worker ran
  and confirmed, iOS 26 showed nothing). **0.29.6 (tagged 2026-10-20 on 69e18d05)**: the declarative payload is
  immutable - WebKit's `WebPushDaemon` shows an immutable one itself at once, no worker (the acrobid demo the user ran
  on the iPhone, immutable, "funker 100%"); with `mutable` the worker's result went back through the host app and
  that step displayed nothing on iOS 26 whatever the worker did. No receipt comes from a Safari device now: Apple's
  acceptance is the delivery (`reason = 'declarative'`, "Delivered to iPhone · Safari", "Last delivered …", never
  "May not be showing" - the user, 2026-10-20: "da velger vi å stole på declarative web push"). **Verified on the
  iPhone 2026-10-20 07:20 UTC**: Send a Test shows its banner (deliveries 50-52, no receipt lines), a parent-PIN use
  on the emulator reached the inbox and the badge on the Home Screen icon carried the unread count (a test push
  carries `app_badge` = the unread count, 0 when the inbox is read - by design, no badge for a test alone). **Done in round 5 (0.30.0, see "Done (not released yet)")**: the tap on a declarative notification goes
  straight to `navigate` without the worker (WebKit skips it when the notification has one), so the link carries the
  delivery (`?push=<delivery id>`) and the page handler of a logged-in parent sets `clicked_at` from it - "Opened on
  iPhone · Safari" (the user's yes, 2026-10-20; a missing tap never changes a thing - delivered stays delivered);
  with it a quiet count on the device row under Getting Notifications, plain tone, never a warning: "Last delivered
  2 min ago · 7 since one was opened" (delivered pushes after the device's last tap or receipt; the user,
  2026-10-20: "en stillere notis på at det er x meldinger siden det er mottatt en receipt"); ("Shown on …" stays passive; the user: 201 = delivered
  for declarative, no repeat-until-opened); a log line per stream open/close (the emulator's "seen" resetting every
  ~5 min is SOLVED 2026-10-20: Caddy 2.11's new `write_idle` default of 1 minute reset every stream 60 s after each
  240 s keepalive - `StreamResetException INTERNAL_ERROR` in logcat; the user's Caddyfile gets `write_idle 5m` (60 s above the 240 s keepalive), and
  DEPLOY.md + Caddyfile.example carry it; no `X-Accel-Buffering` needed for Caddy, nginx users get it in the same
  note if ever asked); migration 0082 + the `failures == 0` art rule; the Send-a-Test row poll.

- The server seeds the launcher's catalog row itself (merged 1b0a888d; goes out with the next
  `server-v*`).

- **The lock's timer tile stays as an empty "Timer" card after the timer ended and was dismissed** (the user's
  jellystar2, 2026-10-21, screenshots): `ClockReader.read` (launcher `badges/ClockReader.kt`) keeps any notification on
  the clock's timer channels, a group summary or an update with no title and no text included, and the tile then
  shows its header only. Likely the clock app's group summary (ClockReader is the one reader that doesn't skip
  `FLAG_GROUP_SUMMARY`) or a blank ended-timer update; to confirm, `adb shell dumpsys notification --noredact` on the
  phone while the empty card shows. Fix: skip group summaries and blank tiles (neither a time nor a title/text) in the
  reader, with a ClockTile/ClockReader test. **The stopwatch never shows either** (the user, same day): its
  notification is exactly what design 42 reads (dumpsys 2026-10-21: `org.fossify.clock` id 10001, channel
  `simple_alarm_stopwatch`, title "30:55", text "Stoppeklokke", ONGOING|NO_CLEAR|FOREGROUND_SERVICE), and NMS held no
  other Fossify notification then - so the reader's `entries` kept a stale timer entry that NMS no longer has, and
  `clockShown` ranks ENDED/TIMER above STOPWATCH: one stale blank entry explains both. Fix also: reconcile `entries`
  with the listener's active notifications (Fossify's keys) on each Fossify post/removal and at the glance page's
  show, so a missed removal can't stick; a blank entry never outranks a tile with content. Later the same day the
  stopwatch did show (the user's screenshot, 10:43) - consistent with the stale entry living until the next
  `BadgeListenerService.sweep` (`ClockReader.connected` rebuilds `entries`; a sweep runs after each `apply()` with the
  notification rule active), so it shows late rather than never. Also: the stopwatch tile shows "Stoppeklokke" twice
  (the label and Fossify's text) - drop the small text when it equals the label. **Done 2026-10-21 (branch
  `lfix-clock-torch`)**: `ClockReader.read` skips `FLAG_GROUP_SUMMARY` (before the extras) and `ClockTile.blank` tiles;
  `clockShown` takes the tiles and never shows a blank one; `ClockReader.reconcile` (on each of Fossify's posts and
  removals, and in `GlancePanel.resumeNow`) asks the listener's `getActiveNotifications(keys)` for exactly the kept keys
  (`ClockReader.attach` at connect, cleared at disconnect; pure `clockStale`) and drops what is gone; `clockSmallText`
  drops the small line when it repeats the label (case/spacing aside). The likely source (AOSP read, android14/16
  `NotificationManagerService`/`GroupHelper`): Android 16's `config_autoGroupAtCount` is 2, so Fossify's running timer
  and its ended notification (or the stopwatch) get a system autogroup summary - posted under `org.fossify.clock`,
  tag `ranker_group`, id 2147483647, on the triggering child's channel (a timer channel), no title, no text, ongoing
  while any child is - exactly the empty "Timer" card, ranked above the stopwatch; it lives as long as any ungrouped
  Fossify notification does. NMS reports a posted record's removal to every listener in order, so a summary that NMS
  no longer held is not explained by AOSP; the reconcile covers a missed removal either way. Tests: `ClockTileTest`
  (blank, the order with blanks, `clockStale`, `clockSmallText`), `LockGlanceGuardTest` (the summary skip before the
  extras, the keyed lookup, every entry point guarded, the wiring). Owed on the phone: a timer to its end with the
  stopwatch running - no empty card, the stopwatch shows at once after Stopp, "Stoppeklokke" once; and
  `adb shell dumpsys notification --noredact | grep -A3 "pkg=org.fossify.clock"` while both run, to see the
  `ranker_group` summary (confirms the source). Code QA `docs/review/qa-lfix-clock-torch-code.md` (0 High, 1 Medium,
  3 Low, 1 Nit), all fixed: the air 12 dp (Medium 1, below); a null `getActiveNotifications(keys)` stays "no answer"
  (Low 2); `onDestroy` drops the lookup too (Low 3); `LockGlanceGuardTest` now catches its surviving mutations 6, 7, 9
  (Low 4); the Nit (one binder call per Fossify post next to `recount`'s) needs no change.
- **The lock's flashlight sits tight under "Lås opp"** (the user, 2026-10-21: "litt for tett på lås-opp knappen? Ser
  grafisk litt snålt ut"): the Nødsamtale/torch row is 2 dp under the 56 dp button (`view_lock_glance.xml`); proposed
  12-16 dp of air and a quieter torch (the music controls' dark round ground, peach only while lit), keeping
  `lockGlanceFit`'s row height. **Done 2026-10-21 (branch `lfix-clock-torch`)**: `lock_glance_bottom`'s top margin
  2 -> 12 dp (the low end, after qa-lfix-clock-torch-code 1: 3.2 dp of slack left for the full music tile with the big
  clock at 473 dp); 8 of the 10 new dp from the page's bottom padding (16 -> 8 dp), so the fixed part is 116 dp and every fit
  `LockGlanceLayoutTest` pins at 473 dp (the Jelly Star's 3-button navigation) and 497 dp is unchanged (a plain
  +12 would have cost the big clock at 473 dp for the full music tile alone and for 4 rows, and with a morning alarm
  a row); the pure model's tests re-derived. The torch already was the dark disc (`bg_music_control`, `kid_core`
  glyph) while off and peach only while lit since round 6 - the screenshot likely showed it lit; now pinned by
  `KidPaletteTest` (glyph on its ground >= 4.5:1 both ways, the disc a quiet field < 1.5:1 on the night). Owed: a
  look on the Jelly Star (the emulator has no flash unit, so no button there).

- **A Google setup nag got past the notification filter** (the user's jellystar2, 2026-10-21: "Fullfør
  Google-konfigurering · Du er nesten ferdig", NEI TAKK/OK, from "Google Play …"; the filter switch has long been on).
  Likely not clearable (FLAG_NO_CLEAR - the rule keeps those, `NOT_CLEARABLE`), else the Play Store allowed or an
  alert-like channel. Waiting for the evidence: the user dismissed it, so the next time a Google nag shows, ask for
  `adb shell dumpsys notification --noredact | grep -B4 -A16 "<its text>"` and `adb logcat -d -s NotificationRule`
  before changing the rule (a snooze for non-clearable nags is the candidate fix).

- **Sonos polling round (53-d)** (approved 2026-10-21, "skal vi bare starte den"): after 53-c merges - a confirm poll
  every 1 s after any socket event we didn't cause until UPnP agrees with it (up to ~3-4 s, generalising the IDLE
  recheck), the metadata's `objectId: "vibb-1"` as an early "ours" hint, a slow UPnP check (60-120 s) while the socket is
  healthy, today's 5/15 s cadence only without a healthy socket. **Built on branch `d53-d`** (design 53 "53-d status"):
  confirm polls every second up to 4 s after a change we didn't cause (a state or a new `itemId`), a lagging confirm
  poll no longer flipping a Sonos-app pause back (found while building: 53-a did); `playbackMetadata:1` subscribed for
  the container's object id, `vibb-1` + the new item's parked event re-basing the bar and showing READY while our play
  settles (display only); the slow check **60 s** while the socket is healthy (cruise and paused; the 5 s tail, verb
  window and the rest unchanged; the extrapolation cap 70 s; a frozen position back to 15 s; health lost wakes a poll;
  a disagreeing poll looks again in 3 s) - an hour of a podcast **542 -> 162 UPnP calls**. Code QA `qa-53d-code.md`
  (0 High, 2 Medium, 3 Low, 3 Nit) and its fix round done: a step, a new pick and the stop read the old item's place
  live when the last measurement is over 15 s old; the keys and the sleep fade read the volume first when no own set
  is the base and the last read is over 60 s old; the note's "53-d fix round". Owed on the speakers: the note's 53-d
  (a)-(f) with the fix round's additions. **Design 54 takes 53-d's renamed constants at its next revision**
  (`IDLE_RECHECK_MS`/`IDLE_RECHECK_UNTIL_MS` -> `CONFIRM_EVERY_MS`/`CONFIRM_UNTIL_MS`, 4 s; `54-spotify-on-sonos.md`
  308-310 and 790).
- **The notification filter on by default for new phones** (the user, 2026-10-21: "Varselfilter kan gjerne være på som
  standard, alt som er viktig for funksjon/hindre ting utenfor launcher bør vel gjerne bare være med"): server
  migration so a new `device_policy` row gets `notification_auto_cancel = 1`; existing phones keep their setting.
  **Done 2026-10-21 (branch `srv-sweep-resume`)**: no migration needed - `insert_device_with_policy` inserts it as 1
  (the `quick_controls_mask` pattern; the column default stays 0, so phones created before keep their value).
- **The Spotify checks touch Spotify less** (approved 2026-10-21). **Done (branch `srv-sweep-resume`)**: an unchanged
  playlist `snapshot_id` never reads the items again (the user: "om snapshot ID er lik, så er det vel ingen grunn til å
  sjekke alle spor?"; only a moved snapshot or Check now); a context-mode playlist tries its items at most daily (at once
  for Check now or a newer login); a show's gate reads `limit=1` and stops on the same newest episode and count.
- **The PWA "does nothing" on a tap after being away** (the user, 2026-10-21; the network over Tailscale not up yet,
  no loading indicator in an installed PWA; "En subtil reconnecting hadde vært bra!"). **Done (branch
  `srv-sweep-resume`)**: `static/reconnect.js` - the tapped link marked and a bar at once, a calm "Connecting…" chip
  after 3 s that pings `GET /ping` and starts the navigation again by itself, "Try again" after 20 s; back after 10
  minutes hidden: a ping and a reload in place keeping the scroll place. Owed: the iPhone PWA after being away (and
  the Android one) - a tab tapped before the network is up shows the chip and the page arrives without another tap.
- **One select per app on the lock** (approved 2026-10-21, the mockup's variant B). **Done (branch
  `srv-sweep-resume`)**: Lock Screen & PINs' app list is one row per app with a native select - Off, Name only, Full
  text - replacing the tick and the "Show message text" switch ("Sender's name only" was wrong for non-chat apps);
  `POST /devices/{id}/lock-screen/apps` takes `show`, the `/text` route is gone; stored data and policy unchanged.
- **A quiet NRK/RSS entry is checked daily** (approved 2026-10-21). **Done (branch `srv-sweep-resume`)**: no new item
  in 30 days -> once a day instead of the interval, back on it as soon as a check finds a new item.
- **Design 55 (to write): controls on the lock's clock tile** (the user, 2026-10-21, iPhone-like): stopwatch running -
  pause and lap; paused - play and X (stop, reset, the notification goes); timer running - pause and X; paused - play
  and X; ended - restart and X. Needs research of what Fossify Clock exposes (today: only the ended timer's single
  Dismiss broadcast, design 42) - its receivers/intents for pause, resume, reset, lap, restart - and what the lock may
  send without unlocking (broadcasts only, never an activity). **Fossify checked 2026-10-21** (FossifyOrg/Clock
  `AndroidManifest.xml`): the only exported entry is `SplashActivity` (the app's UI) for `TOGGLE_STOPWATCH`,
  `SET_TIMER`, `DISMISS_TIMER` and the alarm actions - an activity, so never from the lock; `TimerService`,
  `StopwatchService` and `HideTimerReceiver` are not exported; the running timer's and the stopwatch's notifications
  carry no actions (the user's dumpsys: only the ended timer's Dismiss). So no pause/resume/reset/lap/restart is
  reachable from the lock today. Options: (a) a contribution to Fossify adding notification actions as broadcast
  PendingIntents (stopwatch: pause/resume/lap/reset; timer: pause/resume/stop; ended: restart/dismiss) - the lock then
  sends them like today's Stopp; (b) Vibb One's own timer/stopwatch on the lock (alarms stay Fossify's); (c) as today,
  the tile opens Fossify after the unlock. **Parked** (the user, 2026-10-21: "da får vi bare vente"): Fossify already
  has the requests open - FossifyOrg/Clock #245 (stopwatch: pause/resume, lap, reset) and #246 (timer: pause/resume,
  reset), since 2025-07, labels "feature request"/"needs triage"; their rules allow AI contributions but take PRs only
  for issues labelled `help wanted` and ask for a 👍 instead of "+1" comments. When either gets `help wanted` or ships,
  pick this up (the lock reads the actions as it reads today's Stopp; ask for an action extra to tell them apart).

- **Design 54's measuring run done early** (the user's Pi, 2026-10-21, `sonos-play-test.py --spotify` + the probe's
  `--save`; raw files in the session scratchpad, to be scrubbed into fixtures by 54-a1): sn 2311 took the ShareLink in
  0.8 s (first=1, 30 added, NrTracks 30; TrackURIs carry `sn=6`); TRACK_NR jumps work playing and paused; the speaker's
  own advance is gapless and the socket saw it 0.5 s before UPnP; REPEAT_ALL/NORMAL set and show at once. **On a Spotify
  queue the socket's `metadataStatus` carries the full track** (the Spotify track id, name, artist, album, image,
  duration), the `nextItem`, and the container's playlist id, and `canSkip`/`canSkipToPrevious` are true - so the app
  can tell the playing track (and whether it is ours) from the event itself. **`/status/accounts` is empty on firmware
  86.10** (`<ZPSupportInfo></ZPSupportInfo>`) although Spotify plays: design 54's household check can't use it - the
  add's answer decides - and `NrkService.serial` works only through its `sn=14` fallback there.
- **Design 54 round 54-a1 built (branch `d54-a1`, 2026-10-21; nothing changes for the kid)**: SoCo's ShareLink request
  (`ShareLink.kt`, goldens `sharelink_*.xml` - written by the script's transcription, checked byte-identical against SoCo
  master's plugin; the Pi run with vibb's SoCo owed), the pure rules (`logic/SonosQueue.kt`), `Kind.SPOTIFY_SHARELINK`,
  ContentDirectory Browse, the queue verbs, `playShare`/`queuePlay`/`readQueue`/`learnQueue`, the park's clear (every
  kind), the speaker's sleep timer as SOAP, the session's classify and probe for the kind, the socket's new fields, the
  54-0 capture scrubbed into `ws/spotify_queue_2026-10-21.json` (the probe's Spotify-id rule), `fake-sonos.py`'s queue
  and `sonos-play-test.py --more`/`--add-only`, NOTICE + `MIT-SoCo.txt`. Design 54 "54-a1 status". Next: its code QA,
  then 54-a2. Owed on the Pi: `sonos-play-test.py --golden` with SoCo, `--spotify ... --more [--listing]`, `--add-only`.

- **A sturdier NRK series serial** (the user, 2026-10-21: "hadde vært fint om det serieoppslaget ble mer robust. men
  alt nytt content kommer ut som podcasts istedet" - low priority): `/status/accounts` is empty on firmware 86.10, so
  `NrkService.serial` always falls back to `sn=14`. Candidates: learn the serial from any `x-sonos-http:...sid=277...sn=N`
  URI the household's speakers report (their favourites via ContentDirectory `FV:2`, or a TrackURI seen while NRK
  plays), else try 14; a refused play could retry once with a learned one. New NRK content comes as podcasts (plain
  mp3, no account), so only the older series depend on it.

- **Fewer persistent notifications** (the user, 2026-10-21, a screenshot of the shade and the status bar: "virker for
  meg som om disse varslene er der ganske permanent? mulig å unngå det?" ... "det er et vibb varsel i statuslinja. Det
  er ikke krise, men det er der"): two foreground-service notifications ("Klar for kommandoer fra forelderen" -
  `CommandListenerService`; "Innholdsfilteret er på" - `KidVpnService`), both on MIN channels, which NMS raises to LOW
  for a foreground service (hence the status-bar icon). Proposal: the VPN drops its foreground state once `establish()`
  succeeded (the always-on VPN's system binding keeps it alive; restore the notification if the filter is ever found
  down), the anchor keeps one short notification ("Vibb One", no body). The icon stays while the anchor runs - Android
  doesn't allow a hidden foreground service. Device check: the filter survives a night in Doze without its own
  notification. Approved 2026-10-21 ("Do it"). **Built 2026-10-21 on `lfix-notifs`** (code QA `qa-lfix-notifs-code.md`, all six
  findings fixed): `KidVpnService` goes foreground first in `onCreate` as before and drops it
  (`stopForeground(STOP_FOREGROUND_REMOVE)`) once the tunnel is up and `isAlwaysOn` (pure `VpnTunnelState` /
  `vpnHoldsForeground`); a failed establish or a VPN that isn't always-on yet holds the notification; a read loop that
  ends (thrown or end of stream) closes the tunnel, takes the notification back and retries in the service (5 s
  doubling to 5 min, reset after a tunnel that held 10 min). That notification now says "Content filter connecting…" /
  "Innholdsfilteret kobler til …" (no body; the coordinator's call). AOSP (`Vpn.establish`): system_server binds an
  established VpnService with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE` - `PROCESS_STATE_BOUND_FOREGROUND_SERVICE`,
  the Doze network threshold; the anchor's FGS is in the same process anyway. `KidVpnService.start` (every `apply()`)
  is a plain `startService` whenever an instance runs, `startForegroundService` with `EXTRA_FOREGROUND` (answered by
  `startForeground` first) only when none does; `onRevoke` posts its stop to the main thread. The anchor's
  notification is "Vibb One" with no body (`notification_listener_text` gone). Owed on the Jelly Star: (1) the shade
  shows one notification, "Vibb One", and the status bar one Vibb icon (plus Android's VPN key); (2) the filter
  survives a night in Doze without its notification - in the morning a blocked name still fails and the parent's DNS
  log has entries from the night, `logcat -s KidVpnService` shows no restart loop; (3) after a reboot the filter comes
  up without its notification ("Filter up and always-on: foreground state dropped" in logcat, the DNS log fills); (4)
  the pause/override on and off: the filter goes and comes back, no notification left; (5) no
  `ForegroundServiceDidNotStartInTimeException` in a day's logcat (every sync calls `start`).

- **The speaker's own Next/Previous on a podcast (measured 2026-10-21, the user's Pi, Sverre, `sonos-play-test.py
  --press`)**: a double press (Next) did nothing at all - no socket event, UPnP kept counting (`canSkip: false` for our
  one-URI stream); a triple press (Previous) restarted the same episode at 0 (socket BUFFERING position 0 with
  `previousPositionMillis` 12248, same `itemId`, then PLAYING; UPnP TRANSITIONING 0:00:00), which matches the app's own
  "previous more than 5 s in restarts the episode"; a PAUSED at 4.75 s followed 5 s later (unexplained - possibly a
  press read as a single tap). So the speaker's Next can't drive the app (nothing to observe) without a Sonos queue, which
  the user ruled out for podcasts; Previous is visible as an outside seek to 0 that the app follows (device check: the
  app's bar and bookmark follow it).

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
