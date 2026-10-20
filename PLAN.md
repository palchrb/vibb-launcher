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

### Approved for the next fix batch

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

- **Touch sounds and vibration on Kid Settings** (the user, 2026-10-16): a row on the child's Settings
  (swipe left) to turn key/touch sounds on and off, and vibration on touch. Android's switches are
  `Settings.System.SOUND_EFFECTS_ENABLED` (touch sounds), `HAPTIC_FEEDBACK_ENABLED` (touch vibration) and
  `DTMF_TONE_WHEN_DIALING` (dial-pad tones) - writable with the WRITE_SETTINGS app-op design 34 P12 already
  grants (`ClockFormatSetting`; without it the row is read-only and says so). The keyboard's own key sound
  and vibration are the keyboard app's settings (Gboard's), not Android's - say so, or open nothing. Our own
  keys (the PIN lock, the phone book) follow the system switches. A Quick controls bit like design 18's
  sound row (the parent decides whether the row shows); never on the PIN lock. Launcher + the server's
  switch. Approved 2026-10-16.

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

### Before stable `music-v0.4.0`
- **A context-mode playlist doesn't play on the real SDK** (the user, 2026-10-16, emulator, server 0.26.0,
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

- **The Spotify host list is good enough** (the user, 2026-10-16: list-mode playback works on the emulator
  with every blocklist on, so the DNS capture is dropped): mark it final in one commit - the "PROVISIONAL"
  wording out of the three `spotify_hosts.json` copies, the six constants' doc comments, design 26c and the
  CLAUDE.md files. If a host ever turns out missing, it shows as a blocked request in the phone's DNS log
  (or the web player's refused count in a debug build) and is a one-line fix in all three copies.
- **§6l part B and the 26c QA checklist** on the Jelly Star with the real account (the user's).
- **Emulator re-check leftovers**: R2 the debug proxy stalls after a seek (debug only); R3 no
  now-playing bar after a list→context flip; R4 the VPN session name and launcher notifications
  in English before the language arrives from the server. Not yet checked (time-box): Home in en
  with 4 columns, the phone book, the keypad after a font-scale change, the time-rule
  "Til … p.m.", the PWA's About row, a VoIP ring while paused.
- **Remove the unused androidx `palette` dependency** (unused since design 34 step 1), with its
  verification-metadata entries.

### Asked for, scope not settled yet

- **Design 41: a flashlight on Home and on the lock** (the user, 2026-10-19: "lommelykt ... kanskje tilogmed på låst
  skjerm, som for iphone"; placement decided: "Home og lås", not Kid Settings). Android has no torch app - the Quick
  Settings tile is out of reach in the kiosk - so the launcher owns it: `CameraManager.setTorchMode` on the camera with
  `FLASH_INFO_AVAILABLE` (no camera permission needed; the Jelly Star has a flash). A Home tile beside Settings (lit
  while on) and a round button on the lock's glance page beside Emergency call. Rules: off by itself after 10 min, off
  when a call comes in (the camera), never off at screen-off (as iPhone). Device check first: whether `setTorchMode`
  works while the PIN lock has the cameras disabled by policy (design 25b's `CameraLock`); if not, the lock lifts the
  camera policy while the torch is on, or exempts the torch. Calm look (no yellow flash icon): the Vibb tones. After the
  picture migration (0082) and the receipt rule.
- **Design 43: the PWA's app shell - the tab bar no longer `position: fixed`** (the user, 2026-10-20, the installed
  app on the iPhone, iOS 26: after a while in the background, not every time, the tab bar "floats up the screen" as
  the page scrolls). The cause is the stale layout viewport a standalone app keeps after a resume: a `fixed; bottom: 0`
  box is placed against it, so it moves with the scroll. `tab-bar.js`'s relayout (round 1's hypothesis) doesn't hold
  on the device, so round 1's named fallback goes in: the document stops scrolling (`html, body { height: 100%;
  overflow: hidden }`, body a flex column, `100dvh` where known), `main` is the scroller (`flex: 1; overflow-y: auto;
  overscroll-behavior: contain`), the tab bar a plain flex child at the bottom with the safe-area padding it has, no
  fixed box anywhere; `tab-bar.js` and the container's 96 px bottom padding go; the desktop's always-there scrollbar
  (`overflow-y: scroll`) moves from `html` to the scroller. Consequences to carry: `scroll-restore.js`,
  `auto-reload.js` and `install-now.js` read and set the scroller instead of the window (a `handyScroller()` helper
  in scroll-restore.js, falling back to the window when no `main` scrolls - the login page keeps its own layout and
  has no tab bar); a browser restores no inner scroller on Back/reload, so scroll-restore.js keeps the place itself
  (per path, on `pagehide` and throttled on scroll, restored when the navigation type is `back_forward` or
  `reload` - qa-browser-fixes-code M1 must still pass); the `#fragment` anchors scroll the nearest scrollable ancestor,
  so forms' redirects still land right; `.editor-bar`'s sticky top works inside the scroller; the Locate map and the
  music cards' horizontal scrollers are children of the scroller (check the map's touch handling). Known trade-off:
  with an inner scroller iOS may leave a focused field behind the keyboard (Safari usually scrolls it in; check the
  long forms - Time, Filter - on the iPhone). Tests: jstest for the restore paths (window and scroller, back_forward),
  the tab-bar test deleted. A server round of its own (design QA + code QA), after the 0.29.5 verification; the user
  verifies on the iPhone: the bar still after > 10 min in the background, Back to a long page at its place, a saved
  form not jumping (the standing rule).
- **Design 45: the Notifications page split - the inbox first, the settings behind a gear, opening the inbox
  reads it** (the user, 2026-10-20: "burde man ikke heller bare komme til en slags notification inbox? ... en
  tannhjul ... for å stille inn ... notification counter bør nullstilles når man har åpnet den innboxen; nå må man
  trykke på hvert varsel"). Today `/notifications` is one page: This Device, Inbox, Getting Notifications, Notify Me
  About; the bell leads there; an event is read only when tapped (its row goes to the phone's page) or by Mark All as
  Read. Decided: (1) `/notifications` = the inbox alone (the phone filter, the rows, older/newer), with a gear
  top-right (`title-action`, like the bell) to `/notifications/settings` = This Device, Getting Notifications, Notify
  Me About, with their forms and `#fragments` unchanged (every redirect target moves with them - the no-jump rule);
  (2) opening the inbox marks every event it shows as read (`read_at` on the page's rows, not older pages), so the
  bell count and the app badge drop to what is not shown yet; a row keeps a quiet "new" dot for that visit (the
  unread state rendered before the marking) so the parent still sees what was new; Mark All as Read stays for the
  rest; a tap on a row still opens the phone's page (and still counts as `clicked` for the delivery). (3) The push's
  `navigate` keeps pointing at the phone's page (the user: a tap should land on the phone), and the Notifications
  warning rows keep linking to `/notifications/settings#devices`. Tests: the inbox read-on-open, the bell after it,
  the settings forms' redirects, the links from Devices/Settings hubs. Server round, with designs 43/44.
- **Design 44: the Locate page shows its history, and only its own Recent** (the user, 2026-10-20: "hvor kan jeg
  vise location history ... ser jo ikke den på noe vis i kartet"; the Recent list "viser egentlig feil ting" - Install
  Now and Unlock rows; "1 og 2 er bra", plus a span choice for the trail). Today the history is the blue trail alone
  (all stored points, `locations.json`; invisible when the points coincide, as on the emulator) and Recent lists the
  phone's last 10 commands of every kind. Decided: (1) Recent on Locate keeps only the page's own actions (Update
  Location, Ring, Stop Ringing, Lock, Erase); the other commands get a Recent of their own where they belong - Unlock
  and Lock again on the Lock page, Install Now on Home (the Time page has its own already) - one shared partial and
  one query taking a kind list, the same cancel. (2) The history list behind a row, not on the page (the user: "en
  detaljer man må trykke seg inn på ... så de ikke blir masse støy på locate siden"): under the map one row
  "Locations" with a count in its value ("14 in the last 12 hours ›") opening `/devices/{id}/locations` - the same
  map at the top with the trail, then every stored fix with its time (the zone words the page uses) and accuracy
  ("± 25 m"), newest first, a tap pans that page's map to it (JS-only enhancement, the list reads without it); the
  trail gets a small dot per point on both pages so a short history shows. (3) A span pick above the map on both pages, "Show: 12 hours (default) · 24 hours · 7 days · everything
  kept", applied to the trail, the row's count and the list alike, remembered per browser (localStorage, a view preference - no POST, so nothing to
  save and nothing that can jump) and sent as `?since=` to `locations.json`, which filters by `captured_at`; the
  newest fix is always shown whatever the span, so the marker never disappears. Server tests: the filtered JSON, the
  kind-filtered Recent on each page, the cancel still right; jstest for the pick's storage and the list's pan. A PWA
  server round, together with design 43 or right after it; the user verifies on the iPhone and the emulator (a
  moving emulator: `geo fix` twice, §5 of docs/testing/emulator.md).
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

- **Launcher: an inbox event asks for a sync at once** (the user's yes, 2026-10-20: a parent-PIN use reached the
  inbox and the iPhone minutes later). Today only the emergency call requests a sync (`EmergencyConnects`); the
  parent-PIN events (design 24: "no send of its own", decided before push existed), foreign access, an unseen boot and
  low battery ride the next sync (screen-on, an SSE nudge, else the 30-min backstop). Change: `ParentPin` (used,
  wrong, lockout), the foreign-access and unseen-boot detectors and the low-battery edge call
  `CommandListenerService.requestSync(context, reason)` like the emergency call, debounced by the existing sync
  mutex; the power events already go at start. Next launcher round (one commit, tests on the request).
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
  carries `app_badge` = the unread count, 0 when the inbox is read - by design, no badge for a test alone). **Next server round**: the tap on a declarative notification goes
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
