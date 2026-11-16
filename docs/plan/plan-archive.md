# PLAN archive: handy

Moved from PLAN.md on 2026-11-01 (verbatim; the live plan, the queue and the open questions stay in `PLAN.md`).

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

**Status (2026-10-12; corrected 2026-10-26: phases 1b, 2 and 3 are merged)**: phases 1b (Sonos), 2 (Storytel) and 3
(Spotify) went to `main` in one `--no-ff` merge, `36cabb48` (2026-10-14), and were released as `music-v0.4.0-rc.1`
(2026-10-14) and the stable `music-v0.4.0` (2026-10-20). The release plans below for server 0.24-0.26 and
`music-v0.2.0`/`music-v0.3.0` were never used as written (the music tags go from `music-v0.0.2` to `music-v0.4.0-rc.1`;
there is no `server-v0.25.0` tag); the text stays as history. The player is the Vibb music app in this repo (`music/`,
designs 20/21/21b, ported from vibb). Phase 1 (NRK, RSS, own files, the launcher bridge, Home card, lock view, volume cap) is on `main`, released as
`music-v0.0.2` (`music-v0.0.1` before it); its Jelly Star checks still to come. **Phase 1b, Sonos** (design 26a: rooms in "Spill av på", the phone as
UPnP controller, adopt after a restart, bedtime stops the speaker, calls pause it, the parent's volume cap and room
scope) was built and QA'd on branch `music-1b-sonos` (merged with phase 3, `36cabb48`), main merged in (last 2026-10-12, `620e578`: design 27's
episode art and carousel, the swipe routed through the renderer; before it `444ca58`, main's instant switch adapted
to the rooms); owed: the emulator checks against
`scripts/fake-sonos.py` and the device checks with real speakers. Release after the user's merge: server (migration
0071, in 0.24.0 unless that is tagged first), a launcher release, then `music-v0.2.0-rc.1` on the merge commit
(26a "Release"). **Phase 2, Storytel** (design 26b: the server reads the shelf and lists it, the phone logs in,
mints and downloads, a book's own cover through design 27's art pass, Storytel on Sonos) was built and QA'd on branch
`music-2-storytel` (merged with phase 3, `36cabb48`; steps 1-5 done, 2026-10-12; music-1b-sonos with main and design 27 merged in, so merging
`music-2-storytel` brings 1b too); owed: the emulator checks against `scripts/fake-storytel.py` (with the fake
speakers) and the device checks with the family's Storytel account. Its server migration is **0072**
(`0072_music_storytel.sql`, after design 27's 0070 and 1b's 0071, applied in that order). Release after the user's
merge (26b "Release"): the server (a version bump past 0.24.0 if that is tagged first), the launcher, then
`music-v0.3.0-rc.1` (versionCode 2999) on the merge commit - instead of 1b's `music-v0.2.0-rc.1` when both land
together. **Phase 3, Spotify** (design 26c, `docs/design/26c-spotify.md`, architect draft 2026-10-13 on branch
`music-3-spotify`, merged `36cabb48`, 2026-10-14): the spike (`docs/testing/spotify-spike.md`, branch `spotify-spike`, archived as tag `archive/spotify-spike`) passed item 10 on the emulator,
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
Spotify ›" the Music tab's fifth Add row). The DNS capture that was owed for the provisional host list was dropped
(2026-10-25, "Glem spotify vertslisten"; see "Parked"). `docs/testing/emulator.md` §6l part A ran 2026-10-14; part B
(the user's account) and the Jelly Star checks (26c's QA checklist, the 2.5-hour Doze run) are parked with Spotify
(see "Parked"). Release after the user's merge (`--no-ff`; 26c "Release"): the launcher (`launcher-v0.26.0`, above
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
stored; reused ids reset; own-file copies re-fetched after an id reuse) was built on branch `design-29-impl`
(2026-10-13, music app only, Room 3; merged `5c1f40f6`, 2026-10-13); owed: its emulator checks (`docs/testing/emulator.md` §6f) and a
`music-v*` release. **Design 30** (two mobile-data switches, streaming and downloads; a stream saved to the cache as
it plays; played extras kept within a per-phone "Music storage limit" and a free floor) is built in four steps,
each on its own branch (`design-30-s1` server, `design-30-s2` launcher, `design-30-s3a` the music app's switches and
the report fields, `design-30-s3b` cache-through, extras and the limit, Room 4; 2026-10-13); merged 2026-10-13 with
their QA fix rounds (steps 1-2 `584f260a`, step 3 `d4f1732e`); owed: the emulator and device checks (design 30 §8, `docs/testing/emulator.md` §6f, `scripts/fake-audio.py`), and the
release order of its step 1 status (no `server-v*` with step 1 before the `launcher-v*` and `music-v*` that read
the two switches).
Phase 3 (Spotify) is design 26c above (branch `music-3-spotify`, steps 1-8 built, merged to `main` as `36cabb48`
on 2026-10-14, after it merged this main at `5584772` - its migration is `0074_music_spotify.sql`, after design 30's 0073).

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
testing on the Jelly Star. Not built yet (as of 2026-10-05; updated
2026-10-26): full Tailscale - the Tailscale-app mode 2 (design 03 / design 22 S6) is parked, "the tailnet-only setup is
the product" (see "Parked"); Vibb integration - built since, the Vibb music app (`music/`); AdGuard Home - not built
and not planned as a component, the server has its own DNS filter and a parent-set custom upstream (design 36: a
DoH/DoT address such as a parent's own AdGuard Home, `server/src/dns_server.rs`); the launcher UI redesign - built
(design 34). Play account: decided 2026-10-05 to start WITHOUT one (apps via the
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

**Server 0.24.0 (2026-10-11; tagged `server-v0.24.0` 2026-10-13)**: design 22's server side (two listeners, the admin gate, hashed
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
  Android account; "block account changes" (`DISALLOW_MODIFY_ACCOUNTS`) does NOT
  prevent it - `addAccountExplicitly` from the app's own authenticator passes
  (design 69 §6.2; the user verified ICSx5 on the managed phone 2026-10-26), only
  the Settings UI is blocked. Superseded for the view by design 69 "Dagen". Share calendars read-only on the
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
- **Back buttons on full pages** (was "Pending user decision" since 2026-10-06): decided 2026-10-26 (the plan's decision 6) -
  the buttons stay, the `KidHeader.SHOW_BACK_BUTTON` flag is gone (round 0b, merged 0a43ed3d).
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

### Approved for the next fix batch

**Checked against the code 2026-11-01:** everything here is done, dropped or superseded except what now has a queue line (Element X missed calls, F-Droid entry.jar/signer/ABI, the App blocker, RC3-4, phase D/design 71, notifications phase 2, and - moved up today - the R2 lock task, the PWA small things (2)-(3), the ring card's caller name, the x86_64 release build). Superseded: the screen-on flash → design 71; "Stuck in Recents" never reproduced; Recents from the lock → design 59 (own mode until phase D). Open but parked: duplicate preferred activities in own mode (not urgent).
- **The wordmark right under the rings on the splash** (the user, 2026-10-25: "Flytt vibb music litt høyere opp mot logoen. Men så bra ut!"): Android pins `windowSplashScreenBrandingImage` to the foot of the screen and masks the icon to a circle, so the wordmark cannot sit under the logo in the system splash. Do it in the app's own first frame instead: the system splash shows the rings alone (no branding image), and the held first frame (`MusicRoot` before `contentReady`) draws the same night ground, the rings at the splash's position and size, and "vibb music" 24 dp under them; the system's cross-fade then only reveals the wordmark. Pin the rings' position to the splash's (icon 240 dp centred, inner 160 dp) so nothing jumps. **And the rings breathe there** (the user, 2026-10-25: "burde vi ikke kanskje bruke den animerte versjonen av logoen? den som heter pust"): the held first frame plays the launcher's `splash_vibb_breathe.xml` (the same rings as vibb pi's), looping until the content is ready; the system splash itself stays static (Android caps its animated icon at 1 s). The breathing is optional (the user: "om det blir krøll å la den puste så kan vi droppe det") - any stutter, jump at the hand-over or added time means still rings; the wordmark under the rings is the fixed part. Music, S.
    **Built** (2026-10-25): the theme's `windowSplashScreenBackground` (night), `...AnimatedIcon` = `splash_vibb_mark` (the launcher's Home splash at rest, static) and `...BrandingImage` = `splash_wordmark` ("vibb" cream, "music" peach, Nunito ExtraBold as paths), both written by the new `scripts/music-splash.py` (`--selftest`, not in CI); `MainActivity.holdSplash` holds the first frame until `MusicRoot` composes with the first snapshot, at most 3 s (`logic/Splash.kt` `keepSplash`, `SplashTest`) - the platform's `OnPreDrawListener`, what `androidx.core.splashscreen` does on API 31+, so no new dependency (minSdk 34). Emulator check: emulator.md §6u steps 1 and 6-8. Fix round: the cap pinned, the first frame waits for the carousel's start tile too (`contentReady`, Q-10), NOTICE.md, `--selftest` in `music.yml`.
    **Built** (2026-10-25; design 54 "Hand-over timing (2026-10-25)"): a Spotify tile on a room is refused at once (54-a2 unbuilt); the user's wait was most likely the phone's web player - covered now too ("Kobler til…" from the tap until its first playing state, `SpotifyRenderer.starting`; code QA Q-3, the user's decision). (a) `sonos/HandOverTiming.kt`: one `VibbHandover` line per step, `+N ms` since the tap (the command reaching the service, the actor job, the description read, each SOAP answer, the share link's own steps in `playShare` ready for 54-a2, the answer on main, each settle poll, the first PLAYING poll and socket event, the now-playing update, the line's start and end); the spike comparison and what to cut once measured are in the design (the likely cut: 1 s polls instead of 5 s while our play settles/transitions; nothing cut yet). (b) "Kobler til høyttaleren…" / "Connecting to the speaker…" in the seek line's times' place from the tap until the room reports PLAYING (`SonosController.connecting` from `pending`/TRANSITIONING, `PlayerHub.State.connecting`, `logic/HandOver.kt` `timeRow`, `HandOverTest`), the room's own line winning, no new timeout; **one wording for every source**: "Spotify" can't be in a string (26c step 8, `SourceScanTest`) - the user's call if the Spotify wording should come back. Emulator check: emulator.md §6u steps 2-5 and 9-11 (the fake room with `--transition-s 4`; the web player against `fake-spotify.py`; Spotify on a room only after 54-a2). Code QA `docs/review/qa-music-r13-code.md` (0 High, 3 Medium, 7 Low, 2 Nit) and its fix round done: numbered timelines, `connecting()` tested, the privacy scan, SKIP_TO/RESTORE timelines.
    **Built** (2026-10-26, branch `music-splash-b`): the system splash shows the rings alone (no branding image) and goes at the app's first frame, the app's own splash (`ui/LaunchSplash.kt`, edge to edge): the night ground, `splash_vibb_mark` at the splash's place and size, `splash_wordmark` 24 dp under the rings. Geometry (an icon with no icon background): WMShell's 160 dp x 1.2 = a 192 dp view centred on the window, its drawable drawn 1.5x = the mark's 288 dp canvas unscaled (not "240/160", which is an icon with a background); the rings' outer radius 84.6 dp (inside every icon mask), their lowest ink 83.24 dp below the centre, the wordmark's ink 24 dp under that, from 107.2 dp (`logic/Splash.kt`, `SplashTest` against the theme and both drawables). The system splash's exit is our own 250 ms fade (`setOnExitAnimationListener`): Android's default slides the app's window up 20 dp, which would move the rings. The app's splash fades over the first content frame (`contentReady` and the 3 s cap unchanged) once the system splash's fade has ended, so the wordmark is seen whole; it is owed by `MusicViewModel.contentShown` (a configuration change shows none, a restored task after a process death does); `holdSplash`/`keepSplash` are gone. **No breathing**: the launcher's `splash_vibb_breathe.xml` is one inhale with no exhale and `AnimatedVectorDrawable.reverse()` is hidden API, so a loop without a new dependency snaps back to rest every breath (and the frame lasts well under a second after the fade); it needs a drawable with its exhale or `animation-graphics`. **Q22**: `LocalRenderer.onPlayerError` logs `VibbPlayer` "Playback failed: <code name> (<code>): <class>: <message> <- ..." (URL queries cut); an `UnknownHostException` in the cause chain says "Trenger internett" / "Needs the internet" instead of "Kan ikke spilles nå", everything else - the guard's private-address refusal included - keeps today's words (`logic/PlayFailure.kt`, `PlayFailureTest`); accepted residual: a host the parent's Web Filter blocks reads "Trenger internett" too (the parent blocked it; the kid can do nothing either way). Code QA `docs/review/qa-music-splash-b-code.md` (0 High, 2 Medium, 5 Low, 5 Nit) and its fix round done. Emulator check: emulator.md §6u steps 12-18.

- **A last position at shutdown and at low battery** (the user, 2026-10-05, asked again 2026-10-25). **Merged 2026-10-25 (f379971e, branch `last-position`)** after code QA (`docs/review/qa-last-position-code.md`, 0 High / 3 Medium / 4 Low, all fixed): the `off` report carries the newest stored fix (`power_report.json` `off.location`, both copies; CE `power_off_location`, never a mock or a fresh fix, never under "Off"), the server stores it through the zones intake and marks `device_locations.shutdown_at` (migration 0097), Locate shows "Last position before the phone went off" with the device page's "Off since" exclusions, the `phone_off` event's params carry it (no coordinates in any text), retention clears it with the location window; at `ACTION_BATTERY_LOW` one balanced fix per episode (30 s cap, Location on, not "Off", a fix under 2 min old reused), the episode ends at BATTERY_OKAY or a discharging-not-low report. Design 38 "Last position (2026-10-25)"; emulator checks §6p 5-12 done 2026-10-25 (`docs/testing/2026-10-25-evening-run.md`: one fix per episode, the shutdown's position in the inbox, Locate's line and "Off since" after the hold); the Jelly Star half stays the user's. Ships with `server-v0.51.0` + `launcher-v0.37.0-rc.3`.

- **Shorter web push texts** (the user, 2026-10-25, queue above). **Built** (2026-10-25, branch `pwa-fixes-b`,
  merged `f074d842`, server 0.51.0, 2026-10-25): every push "<name>: <fact>" / the time and at most one number ("Anna: turned on" / "07:02 · battery 64 %");
  the inbox and Activity keep the long words. Stored-words kinds keep both forms (`parent_events.push_title`/
  `push_body`, migration 0098 - 0097 is `last-position`'s), the parameter kinds are worded short where pushed
  (`parent_events::pushed`); summary and test pushes shorter too. The before/after table for every kind is
  `docs/notes/server/notifications.md` "Push wording (2026-10-25)" - **the user reads it before it ships**; open there:
  whether the PIN's place and a request's rule should come back as a word.
- **App update notices** (the user, 2026-10-25, queue above). **Built** (2026-10-25, branch `pwa-fixes-b`, merged `f074d842`, server 0.51.0, 2026-10-25):
  `app_update_available` (the catalog's, no phone, one per release: key `<app id>:<tag>@<asset id>`, raised where the
  sweep and Check Now store a release over a previous one) and `app_updated` (per phone and version, from the status
  report: a higher versionCode that is the catalog's stored release), key + params like design 64; always in the inbox,
  pushed only under Notify Me About's new "App updates", off by default per parent (migration 0099, which also makes
  `parent_events.device_id` nullable by rebuilding it with its two children). Left out: `app_updated` for catalog apps
  other than Vibb One and the music app - the phone's report names no other app's installed versionCode (a launcher
  change in both dirs, not built). `docs/notes/server/notifications.md` "App update notices".
  **That follow-up built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, merged `04b56192`, 2026-10-25)**: `installed_apps` entries carry
  `version_code`/`version_name` (optional; the name trimmed, at most 60), stored with the list's JSON (no migration:
  `device_status.installed_apps_json` holds it), and `note_app_updates` raises `app_updated` for every catalog app
  checked for the phone by its listed code (`listed_app_updates`, `catalog_release_installed`: F-Droid by the
  versionCode, else the versionName against the tag's version - an RC spelled differently from the tag raises
  nothing). The status body is the shared contract `server/testdata/installed_apps_report.json` (code QA Q-5; the
  launcher's `SyncScheduleTest`, the server's `app_updates` tests, root CLAUDE.md's "Shared" paragraph, both CI
  filters).
- **Pull down to refresh on Devices and a phone's summary** (the user, 2026-10-25, queue above). **Built** (2026-10-25,
  branch `pwa-fixes-b`, merged `f074d842`, server 0.51.0, 2026-10-25): `static/pull-refresh.js` on `devices_list.html` and `device_detail.html` only; at
  the very top a downward drag shows a small peach arrow in the title bar's area, past 70 px it turns over, the release
  spins and reloads through reconnect.js's reload in place (the new `window.handyReconnect`: the bar, the chip, the
  ping, the kept place); refused while a save is on its way, a `<details>` is open or a field holds typed text (the
  shared `handyReconnect.mayReload`). Owed: the iPhone and Android PWA by hand. `docs/notes/server/pwa.md` "Pull down
  to refresh".
- **Uploaded avatar per phone; the avatar as the push icon** (the user, 2026-10-25, queue above). **Built** (2026-10-25,
  branch `pwa-fixes-c`, merged `3acf4f85`, server 0.52.0, 2026-10-25; code QA `qa-pwa-fixes-c-code.md`, 0 High / 1 Medium / 6 Low, all fixed): the summary's big avatar is the picker (`accept="image/*"`, no
  `capture`, so a phone offers the library and the camera), saved at once back to `#avatar`, through the contact
  photos' pipeline (square JPEG <= 512 px, no EXIF), `devices.avatar_hash` (migration 0100), `data/device_avatars/`
  in the backups; shown with the dot on every phone row; Remove goes back to the letter. Served without a session at
  `/phone-avatars/<hash>` (an unguessable content hash, only while a phone names it, behind the admin gate) because a
  push's icon is fetched for the service worker without the cookie; the push carries it as `data.icon` (legacy path;
  Safari shows the app icon anyway). Never sent to a phone. `docs/notes/server/pwa.md` "A phone's own picture",
  `notifications.md` "The push icon".
- **PWA accent: field focus A, primary button 3** (the user, 2026-10-25, queue above). **Built** (2026-10-25, branch
  `pwa-fixes-c`, merged `3acf4f85`, server 0.52.0, 2026-10-25; code QA `qa-pwa-fixes-c-code.md`, all fixed; the fix round added the borderless fields' underline and the navigation keys): a focused field's own border in `--tint` 1.5 px and a white fill (dark:
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
  **Server M1 and M2 done on branch `pwa-fixes-a` (2026-10-25, merged `a502bd58`, 2026-10-25):** M1 - Account's new password and
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
  **Done on branch `pwa-fixes-a` (2026-10-25, merged `a502bd58`, 2026-10-25):** the summary loads `auto-reload.js` while
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
  code; (2) "Kan ikke spilles nå" shows for a name/network failure - say "no network" instead when that is the cause
  ((1) and (2) built 2026-10-26, branch `music-splash-b` - Round M1's Built note);
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
  would make design 57 unnecessary, so **design 57's prep release (branch `worktree-agent-a689b601f638554b8`, archived as tag `archive/design-57-prep`) is held
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
  Parents, Add with the one-time password, Withdraw) merged `b27316a8`; steps 3-5 (Reset/Remove, "by <name>", per-parent
  Notify Me About and inbox reads, migration 0088) merged `e4ed9877` (both 2026-10-22, code QA'd with their fixes); all
  five ship in one `server-v*`.
- Settings saves in the security log (design 62's old step 6, qa-62-design Q62-13) - **done 2026-10-26** (round S1, f20cc7f5): one admin middleware writing a coalesced `settings_saved` event (actor, matched route + ids, merged per page within 10 min) - first decide what counts as saved (a 200 can be a refusal) and keep `/music` in; S.
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
  plain lock with the small clock forced was re-measured first (`proto-plain-lock` 13d32d7d, archived as tag `archive/proto-plain-lock`, 59c §7): Android already
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
    **Built** (2026-10-26, branch `launcher-glance-c`): no code change was needed, verified against the code - r11
    (merged 2026-10-24, after the rc.3 run) replaced the fit and its candidates with a rule: the compact tile from two
    rows (`glanceLayout`), so music + 3 rows at font 1.0 is the compact tile and all three rows, every row at least
    partly in sight. On the Jelly Star (and `jelly36`) the page runs the profile clock in both lock modes (code QA M-1):
    414.4 dp - with gesture navigation it fits (425 dp scroller), with 3-button navigation (401) row 3 shows 42.6 dp with
    its bottom 13.4 dp under the fade; with an alarm set row 3 shows 22.4 dp (3-button) / 46.4 dp (gestures). Without a
    clock profile it is 392 dp and fits either bar. The whole page scrolls with the ScrollView's own fade at an edge
    with more, since the fix round Home's bottom fade, 32 dp (it was 16: when the fold falls in the gap above a wholly
    hidden row, 16 dp faded only the gap and the card's empty padding - code QA M-2). The hint is that fade, not the
    "more" line: the "more" line is the list's last child, so it is below the fold exactly when rows are, and counts
    only the rows beyond the eight bound. Pinned in `LockGlanceLayoutTest` (`music + 3 rows at font 1_0 ... (RC3-8)`:
    the layout chosen, both clocks, the fade = `FADE_BOTTOM_DP` and longer than a row's gap + bottom padding + half
    its text line, the "more" count). Emulator check: `docs/testing/2026-10-25-evening-run.md` "Owed after
    launcher-glance-c".
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
  **F3 built** (2026-10-26, branch `launcher-glance-c`): `contact_name` has 4 dp `paddingStart`/`paddingEnd` inside
  its cell (Home's `maxWidth = itemPx` is the cell), so two neighbours' names are at least 8 dp apart; the line is
  88 dp on the Jelly Star's 96 dp cells (Home and the phone book alike), so at font 1.0 a name up to ~88 dp fits as
  before, a single word between 88 and 96 dp now takes a step smaller (code QA N-4; at 1.3 "Kristoffer" does - the
  intended trade). Pinned in `HomeEdgesTest` (`neighbouring contact names are at least 8 dp apart`) and
  `WholeWordsTest` (the names' line is the cell less the label's sides; the phone book's 88 dp line, code QA L-4).
  **F4 built** (2026-10-26, branch `launcher-glance-c`): a glance row's text (`glance_row_subtitle`) is
  `maxLines="@integer/glance_row_text_lines"` - 1 in `values/`, 2 in `values-h720dp/`; the title stays one line. 720 dp
  is the first round screen height where the compact tile and three two-line rows still fit the scroller at font 1.3
  with a 48 dp status bar and 3-button navigation (533.2 dp, from 701.2 dp tall); the Jelly Star (569 dp) keeps one
  line, the six-inch (914 dp) gets two. The qualifier reads `screenHeightDp` (code QA N-1): with `targetSdk` 36 on
  Android 15+ the whole window, bars included (what the derivation assumes); on Android 14 the height less both bars,
  so two lines need about 816 dp of screen there - stricter, the fit still holds. In a two-line row the icon sits at
  the top beside the title, where centring put it in a one-line row (code QA N-2). Since r11 nothing measures (no fit copies to count the lines); the page scrolls,
  and `LockGlanceLayoutTest`'s model counts the two lines (a two-line row 73.4 dp at 1.0). Pinned in
  `LockGlanceLayoutTest` (`a row's text takes two lines only from a 720 dp tall screen`, and the order test's title 1 /
  text by the integer); the Jelly Star's pins are unchanged.
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
  action. Code-QA'd (`qa-r13-rule-card-code.md`, fix round done); ships with server 0.50.0; merged `3eb0c025` (launcher; the music app's part `85cc9dc7`), 2026-10-25, the
  emulator checks (§2.9) ran (below).
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
  64 covers the battery model as its own section. **Step 1 done 2026-10-24 (branch `design-64-step1`, server only, merged `70b6b32c`, 2026-10-24)**: zones on
  Locate (list, page, map), each fix stored once and judged once, `zone_*` events as key + params, push while news;
  the served interval unchanged until step 2 (`docs/notes/server/zones.md`). **Step 2 done 2026-10-24 (branch
  `design-64-step2`, both dirs, merged `e91aa0db`, 2026-10-24)**: `location_policy.zones`, a fence per zone, a crossing's two fixes,
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
  **Merged 2026-10-25 (74f5faa5) after code QA (`docs/review/qa-r10-keystore-code.md`, 0 High / 3 Medium / 9 Low, all fixed). Emulator run 2026-10-25 (`docs/testing/2026-10-25-r10-keystore-run.md`): the upgrade from rc.8 seals all four fields with the phone enrolled and both PINs working, total key loss → NEW SETUP CODE → re-enrolled with a lower-case code, the boot cost unchanged; checks 3, 6, 8, 10, 11, 12 and F1 owed. Goes out with `launcher-v0.37.0-rc.1`.** **F1 built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, merged `04b56192`, 2026-10-25)**: `glancePrompt(.., androidFace)` - UNUSABLE only in own mode, as Android's face (`AndroidLock.faceOn`) the stand-in shows the normal hint (`LockGlanceTest`); its emulator check is in `docs/testing/2026-10-24-home-round-run.md`, "Owed after launcher-small-a".
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
  nothing). Mockup first. **Done 2026-10-22 (7ddc80a6, branch `r9-glance`; design r9 §1-3, merged `ae92be18`, 2026-10-22)**: (1) the
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
  **Built** (2026-10-26, branch `launcher-glance-c`): the tile the user saw after r9 is r9's player, `lock_media_tile`
  (the code calls it the full tile; 174.8 dp is its number, the one-row compact tile is 90 dp and unchanged). 12 dp
  lower, **162.8 dp** with its margin: the bottom padding 12 -> 4 dp (the controls' own targets keep 11-15 dp under
  their glyphs) and the gap above the progress row 10 -> 6 dp; the cover, texts, bar and 48/56/48 dp controls
  untouched. `LockGlanceLayoutTest`'s tables moved with it (full tile alone 266.8, music + 1 row 336.8, the rule card +
  full music 348.8 / + 1 row 418.8 - scrolls 17.8 / 0 dp -, with the alarm line 439 - 38 / 14 dp). The three controls'
  press is a 28 dp borderless ripple (`ripple_lock_media_control.xml`), so it stays inside the tile's 4 dp bottom
  padding instead of being cut flat at the tile's edge (Android's borderless one is half the diagonal, 39.6 dp on the
  56 dp play - code QA L-2).
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
  2026-10-22 (7ddc80a6, branch `r9-glance`; design r9 §4, merged `ae92be18`, 2026-10-22)**: bit 16, off on a new phone (the parent
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
- **GitHub asset digests** (qa-fdroid-source-code (e), 2026-10-24) - **done 2026-10-26** (round S1, f20cc7f5): GitHub's Releases API lists `digest:
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
  **Follow-up built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, merged `04b56192`, 2026-10-25)**: the pass names each package it
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
  `docs/design/r12-music-favourites-tile.md`, branch `r12-music-design`; the user's three confirmations were taken as the defaults 2026-10-24, built below: the
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
  **Follow-up built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, merged `04b56192`, 2026-10-25)** - the one-frame zoom step at the
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
  round done); merged `3eb0c025` (launcher; the music app's part `85cc9dc7`), 2026-10-25, the emulator checks (§1.7)
  ran (below).
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
  **Built 2026-10-25 (branch `launcher-small-a`, code QA'd and fixed, merged `04b56192`, 2026-10-25)**: `applyProvisioningExtras` asks for the sync on
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

- **A Play-free Vibb phone** (the user thinking aloud, 2026-10-26: "burde jeg vurdere å rive ut all gms greia og gå for ntfy og up istedet?" - "Nei jeg bare tenker høyt"): our own apps need no GMS already (the launcher and the music app have none; zones use `LocationManager`; the nudge is SSE). What hangs on Google: Play as the installer/updater of third-party apps (F-Droid covers Element X and Immich; WhatsApp would need a direct-APK catalog source), FCM for Element X's incoming calls (the F-Droid flavour uses UnifiedPush - an ntfy server must be reachable from the phone's ordinary network, since only the launcher has tailnet access (tsnet SOCKS); public next to Synapse is realistic), network location (GPS only without GMS - zones indoors and battery), the Google account and the Play nags (the win: the Play window, install mode, auto-update and `docs/setup/google-account.md` could go). GMS can't be uninstalled on the Jelly Star, only hidden; the realistic goal is Play-free, not GMS-free. A staged path if wanted: (1) a Play-free catalog with Play hidden, (2) Element X's F-Droid flavour + ntfy in the compose file, proven on the emulator and the Jelly Star (incoming calls with the screen off for an hour), (3) only then weigh disabling GMS with the location loss measured. No round; no note yet.
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
- **Remove the unused androidx `palette` dependency** - **done 2026-10-26** (round 0b, 0a43ed3d).
  Was: **Remove the unused androidx `palette` dependency** (unused since design 34 step 1), with its
  verification-metadata entries.
- **Design 46's queue view: a context entry's list shows the playing track and the next 20 from Spotify's queue**
  (the user, 2026-10-20: "Legg køvisning for neste 20 på longlisten vår, så husker vi på den til senere"). Designed and
  QA'd in `docs/design/46-context-queue-and-play-marks.md` §0b/§1.1-§1.3 (+ `qa-music-round-design.md`): the server
  adds the scope `user-read-playback-state`, every Spotify account logs in again, the music app reads
  `GET /me/player/queue` after each start/track change, a tap on a playlist/album row starts there. Only worth it for
  playlists no logged-in account owns, and for artists; 46b (a reader account) covers the family's own lists.

- **Design 56, Android 13 and 17** (assessment, `docs/design/56-android-13-and-17.md`): parked until Android 17 is
  announced for the Jelly Star, then the API 37 emulator round plus §3.4's four items.
- **Design 61, vibb boxes** (assessment, `docs/design/61-vibb-boxes-assessment.md`): option (c), parked.
- **FluffyChat instead of Element X** (the user, 2026-10-26): assessed against the code, `docs/review/fluffychat-vs-element-x-2026-10-26.md` - stay on Element X for now (the cryptography no weaker, the stability and defaults are: skippable backup/verification, open key-backup bugs; MatrixRTC calls three weeks old with open audio/hang-up bugs; its call runs inside the chat-list activity, so only variant E of S-M4 could keep the kid out of the chats; per-room nick/avatar works there, not in Element X). Revisit about January 2027 with a Jelly Star test.
- **Design 09 part A, an SMS allowlist** (`docs/design/09-sms-and-fixes.md`): postponed since step 9; no code exists.
- **Design 22 S5/L1 and S6/L2, and the Tailscale-app mode 2 (design 03)**: parked - "the tailnet-only setup is the
  product" (S7, the CSP, moves to server round S2). L3 (the strict network-security config) is not built and has no
  entry: parked too (no decision yet).

### Tooling and docs

- **Emulator AVD "jellystar"**: the "Nexus S" profile (no display cutout) at 480×854 @240 instead
  of the Pixel 7 profile; make it the default in `docs/testing/emulator.md`. Check the real
  Jelly Star's `wm density` first (the docs assume 240).
- **Emulator speed**: a lighter image (Android 16 ATD / google_apis) for most checks, and a
  `systemd-run --user --scope -p MemoryHigh=4G -p MemoryMax=5G` around the emulator.

### Left the queue 2026-11-01 (shipped by `launcher-v0.37.0-rc.8` / `server-v0.60.0` or earlier; verified against the code)

  - **Round 0b - dead code in the apps** (`cleanup-apps`, launcher + music; `docs/plan/2026-10-25-queue-rounds.md` Round 0b; **merged 2026-10-26**, 0a43ed3d, code QA `docs/review/qa-0b-cleanup-code.md` 0/0/3/3 + fix round; 41 files, -940 lines; `color/kid_icon_fallback` kept - it gained callers; the COVER glue stays for design 59 phase D): design 32's debug experiments (`LockUpDebug`, `KeyguardCoverDebug` + receivers, the §6k text), `palette-ktx`, the dead-code review's launcher list, D3's `FirebaseCleanup`/`FirebaseLeftovers`, D5's minimalist leftovers, `KidHeader.SHOW_BACK_BUTTON` (decision 6: the buttons stay, the flag goes), music `SpeakerCache.scanNow`. Deletions only; ships in the next launcher/music RC.
  - **Home's fold at four rows: the last tile's label disappears** (the user, 2026-10-26, `jellystar2` screenshot, 320x569 dp, 11 tiles = 4 rows: the fourth row's icon is whole at the bottom but its label "Innstillinger" is under the 32 dp bottom fade and the gesture bar, so the tile reads as a missing label rather than as "more below"): the grid scrolls (design 34 P4, `FadingRecyclerView`, `HomeEdges.kt`), and the fold happens to land between the icon and its label. Fix direction (pure, `HomeEdges.kt` + `HomeEdgesTest`): the fold must never cut between an icon and its label - either the grid's row metrics tighten so a fourth row fits when it nearly does (the label's gap and the tile's badge room are the only slack), or the grid's bottom padding is set from the row height so the last partly visible row shows at most half its icon (the cut through the icon reads as more below, as glance C's rule). Decide by measuring the 569 dp case at font 1.0 and 1.3 and the six-inch AVD; E2 (the screen-size matrix) covers the rest. Launcher, S; after L2. **Built 2026-10-27** (67ec3950 on `home-fold`; (a) measured and dropped - four rows need 22 dp more than the Jelly Star has at 1.0 with gestures, 46 dp with 3-button; built (b): pure `gridFoldPaddingPx` in `HomeEdges.kt` - the first row whose label's first line isn't legible above the fade shows at most the top half of its icon (`clipToPadding`, bottom padding = H − icon middle, never under 16 dp), glue `foldGrid` on a layout change at the grid's top; `HomeEdgesTest` +3 incl. a property sweep; emulator.md §6 step 12; 1879 tests green). **Code QA 2026-10-27 `docs/review/qa-home-fold-code.md` 0/0/3/3 Merge; MERGED 2026-10-27 via `launcher-small-b`** (the fix round: the fold is applied in a one-shot pre-draw listener, never inside the layout pass; a scroll back to the top re-folds after a content change; 1888 tests); emulator §6 step 12 owed in the next session; ships in rc.8.
    - VoIP small fixes - **done/dropped 2026-10-26**: READ_PHONE_STATE for allowlisted apps built (round L2: granted by policy while calls are managed to the allowlisted apps whose Phone group the denial greys, DEFAULT when calls go unmanaged - never revoked, so a running WhatsApp call isn't killed; a new allowlisted install gets it at once); "Tilbake til samtalen", the SOS guess and the BlockedAppActivity flash dropped (own-mode only; design 59 phase D deletes that code). Design 57 itself stays held.
  - **Round L3 - DNS: the secure-DNS bypass and the upstream line** (`dns-bypass`, launcher + server, one branch; design 67 `docs/design/67-dns-bypass.md` written, QA'd (2/4/7/5) and revised 2026-10-26 (09354cbf; §6 answered on the defaults); **merged 2026-10-26**, cc2b0732 (design 67, code QA `qa-67-code.md` 0/1/6/6 + fix round; the contract commit 1614edb8: `dns_upstream_status.json` + `doh_hosts.txt` byte-identical on both sides); **E3 passed 2026-10-26** (`docs/testing/2026-10-26-e3-run.md`: the hosts, the canary, own upstreams by name, the outage window with the 5-min floor, offline, provider mode, the rev-change survival; browser screens and WhatsApp's READ_PHONE_STATE are device checks); **tagged 2026-10-26: `server-v0.56.0` and `launcher-v0.37.0-rc.6` at a82ae749** (rc.7-rc.8 left for 0.37.0; stable after the user's J1 items)):
    - Browser "secure DNS" bypass - **built** (design 67): `BrowserDoh.kt` sets `{"DnsOverHttpsMode":"off"}` on the listed browsers while the Web Filter is on; the built-in secure-DNS host list (`assets/dns/doh_hosts.txt`, 57 suffixes, category "Secure DNS server", judged only for tunnel queries, never our own upstreams); Firefox's canary answered NXDOMAIN; the Web Filter page's honest footer and per-phone lines (Firefox/Cromite/Opera may still be set by hand).
    - Diagnostics: Web Filter line "upstream can't be reached" - **built** (design 67): `UpstreamReach` (a failed query = dropped with no server bytes, counted only on a validated network; 3 failures over >= 30 s; a 60 s gap restarts; losing the network or a rekey ends the outage), reported in `dns_upstream` (`unreachable_since_ms`/`unreachable_until_ms`, `dns_bypass_v1`); the page shows an ongoing outage, and an ended one for 24 h only if it lasted >= 5 min.
  - **Round B1 - per-app permissions** (`app-permissions`, launcher + server; design 68 `docs/design/68-app-permissions.md` written, QA'd (0/4/10/7) and revised 2026-10-26 - **merged 2026-10-26** (1b833958, branch `app-permissions`, code QA `docs/review/qa-b1-app-permissions-code.md` 0/0/5/7 + fix round: migration 0102, `app_permissions` policy list + `installed_apps[].access` (third-party targets only, the camera's rule), `cameraGrants`/`AppAccess.kt`, the "App access" rows ("Data om appbruk" as Android 16 says), the PWA Permissions details (approved items in the details only); server 0.58.0; launcher 1831 / server 1401 green). **E4 run 2026-10-27** (`docs/testing/2026-10-27-e4-e5-run.md`): the app-op probe PASSED for all five ops (three readings agree, granted and denied), 23 PASS / 2 FAIL / 5 device checks; fix round merged (ff82e523: M3 no App access rows for system apps, M2 Etar's first start outside a pause crashes on its own battery page - wording "then open it once while restrictions are paused", L3 the Settings gate stays open across a page we launched - a stop within 3 s of our open, a return within 10 min, once). Owed on the Jelly Star: the probe repeat, a failed read as `unknown`, the camera change during a live call. Shipped 2026-10-27 in `launcher-v0.37.0-rc.7` + `server-v0.59.0` (0.58.0 was never tagged); the user's §7 defaults apply unless answered (a pause lifts camera off; full-screen notifications as a fifth access; per phone only)):
    - **"Alarms & reminders" (exact alarms) for an allowlisted app** (the user, 2026-10-26, Etar from F-Droid on `jellystar2`: "prøver å starte etar nå men får beskjed om at den feiler ... jeg satt restrictions på pause og da gikk det, da ble jeg ført til en settings meny for hvem som får styre alarmer"): Etar asks for `SCHEDULE_EXACT_ALARM` at first start and sends the user to Settings › Alarms & reminders; under restrictions Settings is blocked, so the start "fails"; the pause let it through and the grant persists. The same class as B1's Media management / Modify system settings / usage access: a special-access app-op no device owner can grant - so B1's row list gets "Alarms & reminders" (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` + the package, the `systemPageIntent` pattern), shown for an allowlisted app that declares the permission and hasn't got it (how the launcher can read another app's app-op without GET_APP_OPS_STATS is the design note's question; else the row shows for every declaring app until tapped once). Workaround until then: a pause, as found.
    - Camera off per app + a permissions row per catalog app in the PWA. Added 2026-10-25 (the user, Immich: "kunne tenke meg å kunne slå den på for å rydde opp etter lokal opplasting"): the row also lists the special-access app-ops a device owner can't grant - Media management (`MANAGE_MEDIA`, Immich's delete-after-upload), Modify system settings, usage access - each as "approve on the phone" with a pause-screen row that opens the exact page for that app (`Settings.ACTION_REQUEST_MANAGE_MEDIA` + the package, the `systemPageIntent` pattern of the write-settings row), never adb.
    - **Bedtime as its own setting; day rules inside one day** (the user, 2026-10-26: "Start må være før slutt" and "burde leggetid være en egen input-greie ... leggetid som 1 faktor, ikke 7"): a short design note first. Proposed shape: one "Leggetid" setting per phone with an evening time and a morning time (and, proposed, a second pair for nights before a free day), stored and enforced as one rule that may cross midnight; the ordinary day rules then stay within one day (the form refuses end <= start, `00:00`-`23:59` the widest) and the help "An end before the start ends the next morning ..." goes. Existing rules that cross midnight migrate into the bedtime setting (or are refused until edited - the note decides). Both sides (the server's rules + the launcher's time_state), the policy contract pinned on both.
      **Built** (2026-10-26, design 66 `docs/design/66-bedtime.md`, branch `bedtime`, merged 0994b862; design QA `qa-66-design.md` 0/4/5/5 applied, code QA `qa-66-code.md` 0/1/2/3 + fix round): migration 0101 (`time_rules.bedtime`, a partial unique index per phone), `migrate_crossing` at every start (idempotent, one transaction per unit; a night rule becomes the phone's Bedtime, everyone's copied per follower with lifts repointed, the rest split at midnight with a "(morning)" row; proven minute by minute over a week), the Bedtime page `/devices/{id}/bedtime` (Sun-Thu / Fri-Sat rows, calls, exceptions; saves land on `#bedtime`), the day-rule form refuses a crossing (00:00 as an end = midnight, stored 23:59), `served_days` 23:59 -> 0 for the phone and the badge, the rule editor refuses the bedtime row, no policy contract change, the launcher untouched (it names the empty-named bedtime rule "Leggetid" itself). Accepted: a split rule shows no "Til" between the evening and midnight. Ships in `server-v0.54.0`.
  - **Round L7 - "Dagen": a read-only day view with the parent's title rules** (`day-view`, launcher + server; design 69 `docs/design/69-dagen.md` written 2026-10-26 (d603fb3a), design QA 1/7/8/4, revised 2026-10-26; **built 2026-10-26 (branch `day-view`, 8 commits fb211dd2..ddc8c97e on 8e78dc96: the contract with migration 0103 and the shared vectors `calendar_title_rules.json` (32 rows) + `calendar_status_report.json`, `app_display.on_home`, step 1 server with `server/src/i18n.rs` + `src/i18n/{en,nb}.json`, step 1 launcher (day model, pager, `DayActivity`/`EventActivity`, Home tile), step 2 (line A + row C), step 3 ("+", "Ny avtale", own events only), docs; launcher 1857 / server 1406 / jstest 193 green), code QA `docs/review/qa-l7-dagen-code.md` 0/1/6/9 + fix round (M1: a kid-calendar pick the last report lacks stays selected; L1: the kid's events' UID is `vibb-<per-phone tag>-<uuid>` so siblings sharing a calendar can't edit each other's; every calendar route pinned behind a session; the vectors 35 rows incl. æøå/unknown-field/empty-pattern); **merged 2026-10-27** (42afa038; migration 0103 after B1's 0102, Dagen's emulator section §6w; server 0.59.0; launcher 1871 / server 1418 / jstest 193 green on the branch). **E5 run 2026-10-27** (`docs/testing/2026-10-27-e4-e5-run.md`): 35 PASS / 3 FAIL / 6 device checks; every [verify] item of §6w step 5 passed with ICSx5 2.4.3 from the catalog; fix round merged (2cff5047: H1 `DayActivity.glanceIntent` with `FLAG_ACTIVITY_NEW_TASK`, pinned with the missed-calls flag; M1 the header wraps, an all-day row reads "Hele"/"dagen" on two lines and the time column widens per page for 12-hour times; L1 the peek real (`clipToPadding=false`); L2 `SwipeMode.HELD` - a sideways swipe on any non-dismissable row does nothing; L4 `ClockFormat.formatClock` AM/PM and the locale's day pattern, `DayTextTest` en-US + nb-NO). Re-check on the next emulator run: §6w's "fixed after the run" line. **Shipped 2026-10-27: `launcher-v0.37.0-rc.7` + `server-v0.59.0`** (092f8e13; both approved and published by the user, with `server-v0.57.0`). Dagen is complete; Round S-i18n may start (its design note first). Release note: with `kid_adds` on by default every managed phone gets the Dagen tile at the update (hide it per phone with "Dagen on Home"); a calendar the parent hides takes away the kid's "+"** (the user: "Da er det bare å kjøre i gang med dagen i dag!"; B1 merges first, Dagen merges main before its fix round; **the sketches that are the design** (the user, 2026-10-26: "vær spesifikk på hvilke av mockup sketches som skal brukes"): mockup §1 screens 1-3 (screen 4 "Uten regler" is the before-picture only), §1b both, §2 both PWA pages, §3 A and C only (B declined), §5's three feed days + the event page + the rules list) (step 1 ships starts-with/contains only: Android's regex engine differs from the server's and can't be budgeted; regex later with RE2/J if wanted; the calendar key = a hash of the feed/collection URL, not the display name; the "Neste" line yields while a reminder row is up; the master switch; step 3's room); builds after B1; the user, 2026-10-26: "ville egentlig bare hatt noe som viser events per dag, og at man enkelt kan scrolle fra dag til dag ... lage noe eget, veldig enkelt selv" and "regexe bort ting fra abonnerte kalender events og legge til noe ... bytte det ut med Skole: og så resten"; decided: in the launcher, not a standalone APK): (1) a "Dagen" page in Vibb One that reads Android's calendar provider (`READ_CALENDAR`, self-granted as device owner) and shows one day as a list, swipe between days, an "I dag" button, no editing; the sync apps (ICSx5 for subscribed feeds, DAVx5 for a shared CalDAV calendar, both F-Droid) feed the provider and need no screen for the kid; Etar may go or stay as the editor; (2) **title rules per phone** set in the PWA and sent in the policy (a new key - both dirs, one commit): "starts with / contains → replace with / hide", optionally one calendar only, first match wins; regex as an advanced option with a length cap and a preview in the PWA; applied on the phone when the day is drawn - the calendar never goes to the server; (3) step two on the glance, decided 2026-10-26 from the mockups (`docs/design/69-mockups/dagen.html`, artifact https://claude.ai/artifact/N7VLeudRASW1Ji1eCQZVnC; the user: "mockups ser bra ut! enig i å la B ligge"): **A** the one line "Neste: Fotball 17:30" whenever a calendar is chosen, and **C** a reminder as a per-calendar choice (off / the calendar's own instant / X minutes before) - always the calm glance row, never a sound, vibration or alarm-style notification (the user: "av eller rolig alert er mest innafor"), and every reminder follows the title rules (rewritten title, a hidden event or a calendar switched off gives none - "alle notifications må følge alle regler på kalenderen"); **B** (a today card) is out. The user, later the same day: "man bør få velge om det skal komme den rolige reminderen eller absolutt ingenting" - so a per-phone master switch "reminders on the lock screen: off / the calm row" overrides every calendar's choice, the "Neste: …" line is its own per-phone switch, and every calendar's reminder defaults to off: after setup the day view alone, nothing on the lock screen until chosen. And a **step 3, "the kid's own events"** (the user, 2026-10-26: "burde barnet ha mulighet til å legge til ting i enten kalendere hvor den har skriverettigheter"): the parent picks ONE writable calendar as the kid's (a local one, or a DAVx5 calendar the kid may write to, e.g. the shared family calendar); then Dagen gets "Legg til" (what, day, time, length; no guests, no recurrence, reminders follow the calendar's rule), and the kid may edit or delete only the events Vibb One created (marked as its own); feeds and the parent's events stay untouched. No calendar picked = no plus button. "Calendars shown" lists every calendar the provider knows (ICSx5 feeds are read-only, DAVx5 follows the server's rights, a local calendar is writable). The model allows for it from step 1; the build comes after the read-only view and the rules. From the user's real itslearning feed (2026-10-26, mockups §5): the feed has no locations but descriptions with the important words ("Viktig at man er klar på skolen 08.30"), so the row's second line is the location when present, else the description's first sentence, and a tap opens the whole description; a hide rule may match the description too ("For 6. trinn" sits only there); imported titles may carry a space mid-word ("R omerriket") - shown as the feed has it. Decided 2026-10-26 (the user): the title and the second line get at most two lines each, the rest cut with "…" and read in full behind the tap (the second line = the location, else the description's first sentence, at most ~120 characters); the day pager is endless both ways, one day per swipe, and "I dag" shows whenever the page is not today. A row whose text was cut shows "…" and a small "›" at its right edge (the user: a visual hint that there is more); rows that fit show none. The jump-back button reads "← I dag" / "I dag →" (the arrow towards today), never a bare "I dag" that could pass for a heading (the user). Also decided 2026-10-26: the kid's writable calendar defaults to a LOCAL "Min kalender" Vibb One creates on the phone (so "+" exists from day one; the parent may pick a DAVx5 calendar instead or switch "the kid may add events" off), and the phone's Calendar page gets "Dagen on Home" (default on; off hides the tile, the glance line and the reminders, the rules stay) - both to carry into design 69 at the build (§3.1 switches, §4.5 the local calendar's creation via the LOCAL account type). **Verified by the user 2026-10-26 on `jellystar2`: ICSx5 adds its account and syncs on the managed phone, and both Etar and Fossify Calendar show its feeds** - so design 69's [verify] items on ICSx5 accounts/sync are done; only DAVx5 (a shared family calendar, step 3's round trip) and the glance geometry remain to prove. Launcher S-M + server S. Design note: the provider query (which calendars - all visible ones, or the parent picks), the day model (all-day events, multi-day, time zone), the rules' grammar and tests, the look per design 34.
  - **Round M1 - the splash and the "no network" words** (merged 2026-10-26, `music-splash-b`: the wordmark under the rings and the no-network words built; the entry moved to "Approved for the next fix batch"):
  - **Round M1b - the wordmark held 400 ms** (merged 2026-10-26, `music-splash-c`; the user, 2026-10-26, after E1: "sikkert greit at man kan se vibb music i minst 400ms"): E1 measured the app splash at full strength for only 50-70 ms when the content is ready at once (the empty state, or a library from the snapshot) - the wordmark fades in over the system splash's 250 ms exit and fades out again almost at once. `logic/Splash.kt` `splashFadeDue` gets a minimum: the app splash's fade starts no earlier than 400 ms after the system splash's exit fade ended (or after the app splash's first frame when there was no system splash), the 3 s cap unchanged; `SplashTest` pins it. Ships in `music-v0.5.0-rc.7`.
    **Built** (2026-10-26, branch `music-splash-c`): `logic/Splash.kt` `SPLASH_HOLD_MS` 400 and `splashHoldEnd(system, goneAtMs)` - the hold's end in ms since the app splash's first frame: the system splash GONE at t -> t + 400, but never past the 3 s cap (from the first frame; GONE with no instant known -> the cap); FADING -> none yet; never handed over -> max(500 ms wait, 400 ms hold) = 500 ms, the two running concurrently from the first frame, not in sequence. `splashFadeDue(contentShown, system, goneAtMs, nowMs)` = the content shown and `nowMs` past that end (pure, the time a parameter); `ui/LaunchSplash.kt` feeds it ms since its first frame (`SystemClock.uptimeMillis`, the GONE instant read in the composition that first sees GONE, one delay to the hold's end - no ticking), and latches the fade once due (a hand-over later than the 500 ms wait no longer brings the splash back). `contentReady` and the 3 s cap unchanged; no new dependency or string. `SplashTest` pins the hold (content at once + GONE at 300 ms: no fade at 699, fade at 700; content late at 1.5 s: fade then), the concurrent wait (500 ms, not 900; not at 400) and the cap (GONE at 2.8 s: the fade at 3 s). Emulator check: emulator.md §6u steps 12 and 18 ("at full strength at least 400 ms").
  - **Device › Calendar: the page is cluttered and two words don't work** (the user, 2026-10-27, an iPhone screenshot of the nb page: "Unødvendig mye hjelpetekst gjør siden rotete ... bør legges under i-tegn slik som i resten av pwa", "Barnets avtaler i kræsjer litt", "overordnet påminnelse på låseskjerm bare heter Av og Den rolige raden?? Hva i alle dager betyr det"): (1) the three footers move under the PWA's `<details class="info">` "i" toggle (as About and Calls have), one per group; (2) the row "Barnets avtaler i" wraps against its select - the select goes on its own line under the label (or the label shortens to "Barnets avtaler"), both languages; (3) the lock-screen master row reads "Påminnelser på låseskjermen" / "Reminders on the lock screen" with a select Av / Stille rad (today's `off`/`row`; the policy key `lock_reminders` unchanged - the user, 2026-10-27, wants a third option "stille rad + et ping", the item below), its sub-line "En stille rad før hendelsen - ingen lyd, ingen vibrasjon"; the per-calendar reminder select keeps Av / Kalenderens egne / 15 min før, and its footer says the master switch wins; (4) optional: a tiny inline SVG of the lock row under the "i" so the parent sees what a reminder looks like (the user: "kanskje en liten screenshot som viser alternativene") - do it if it is one small SVG, else skip. Server, XS-S; `tests::calendar` page tests + the browser pass nb/en. **Merged 2026-10-27** (branch `calendar-polish`, fb46b056: the three footers one line each + an "i" panel, "Barnets avtaler" stacked over its select, the master "Påminnelser på låseskjermen" as a select Av / Stille rad with the sub-line, a 12-line SVG of the lock row in the "i"; 1419 server tests, jstest 193, browser pass nb/en light/dark JS on/off by the implementer; no separate code QA - XS). Left as is: the Dagen switch's sub-line ("Av skjuler flisen, linjen på låseskjermen og påminnelsene; reglene står") - reword with the ping item if the user wants.
  - **A colour per calendar instead of a "Skole:" prefix** (the user, 2026-10-27: "noe liten fargekoding ... skille mellom x antall kalendere man abonnerer på, skole vs fotball ... hvor man da ikke nødvendigvis trenger å skrive Skole: eller Fotball: før hver event"): mockups §6 in `docs/design/69-mockups/dagen.html` (artifact v13): A a 4 dp colour edge, B a dot before the time + a legend when the day has more than one calendar, C the time in the calendar's colour, D a chip with the calendar's name; the lock row with the same dot; the PWA's "Farge" row with eight calm colours, the first free one chosen automatically, peach reserved for the kid's own. **Approved 2026-10-27: B** (the user: "Jeg liker prikkene med legend under, vi går med det med 1 forskjell; fra og til klokkeslettet bør stå med lik tab ut fra siden"): the dot in its own 14 dp column so the start and end times share one left edge (mockup v14, §6 B); the legend only when the day has more than one calendar; the lock row's dot; the PWA's "Farge" row with eight named colours, the first free one chosen automatically, peach reserved for the kid's own. Policy `calendar.calendars[].color` (eight named values), both dirs in one commit; the title rule "replace with nothing" documented as the common school-feed rule. Launcher + server, S-M; **MERGED 2026-10-31** (`calendar-colours` 481572dd: migration 0106, eight named colours Himmel/Skog/Sol/Bær/Hav/Lavendel/Sand/Skifer, "Velg for meg" until a colour is saved, "Ingen ledig farge" when all eight are taken, the auto-pick at every save on the Calendar page and at a report once the phone shows two colour-taking calendars, every row's colour counts as used, a gone calendar's colour-only row pruned, the kid's own LOCAL calendar excluded by its constant key, the dot column, the legend, the lock row's dot alone - the name stays Dagen's legend's; code QA `docs/review/qa-calendar-colours-code.md` 0/1/7/9 + the fix round). Emulator §6w step 9 in E6; ships in rc.8 / server 0.60.0. Open palette nit: berry next to peach (1.21:1) - the user's call.
  - **Design 72's hourly fetch pushes to the phone** (the user, 2026-10-27: "så lenge vi da også pusher nye ting til telefonen hver time uten at den må sjekke selv?"): yes - the note §"When": a new rev nudges exactly the subscribed phones over the existing SSE nudge; the phone fetches the set then, not on its own schedule. Confirmed in the note, nothing to add.
  - **Dagen is not a task of its own: recents shows an empty frame, Home/Back cut without the app-close animation** (the user, 2026-10-27: "å trykke på firkanttasten ... tar deg til recent apps, som da gjerne bare viser en tom ramme og ikke Dagen appen ... om jeg trykker på home knappen eller tilbake knappen, så får man heller ikke den animasjonen hvor appen forsvinner ned og ut"): verified in the code - Home's tile starts `DayActivity` with a plain intent (`HomeGridAdapter.kt`), so Dagen lives in the Home task, which is `excludeFromRecents` + `clearTaskOnLaunch`; Recents therefore has no card for it, and Home/Back are an in-task activity switch drawn with the theme's `WindowFadeTransition`, not the system's task-close animation. Only the lock's glance already opens it in its own task (`glanceIntent`, E5's H1). Fix (launcher, XS-S): `android:taskAffinity="${applicationId}.day"` on `DayActivity`, `EventActivity` and `EventEditActivity` (all three stay in that task), Home's tile starts it with `FLAG_ACTIVITY_NEW_TASK` (one intent for both callers), a task description with the app's name and icon so the recents card reads right (no `FLAG_SECURE` on Dagen, so the card shows the day); the fade stays for the pages inside the task. Then Home/Back cross a task boundary and Android plays its own task-open/close animation, as for any app. Check: the pause/lock still ends the task as it ends the missed-calls one; a Home press while Dagen is open (`clearTaskOnLaunch` no longer clears it) - Dagen stays in recents until Back, as a normal app. **Not an argument for a separate APK**: the task is the unit Recents and the animations work on, not the package. **MERGED 2026-10-27** (`day-task` b3094f31 + the fix round via `launcher-small-b`: the `.day` affinity on the three Dagen activities, one `DayActivity.intent` with NEW_TASK for Home and the glance, the task description, plus a guard the implementer added: Dagen's screens redirect to the time-rule screen when a lock reason is set, since Recents could otherwise put Dagen over it; emulator.md §6w step 8; 1880 tests) - code QA `qa-day-task-code.md` 0/0/2/3 Merge (the guard mirrors Kid Settings, the reminder row still lands on the day, nothing ever ended our non-lock tasks); fix round: `onNewIntent` sets the intent, the pins, CLAUDE.md; emulator §6w step 8 owed in the next session; ships in rc.8. Open product question from the QA: whether the phone book and Kid Settings should become own tasks too (an emulator step: Recents shows the card, Home animates).
  - **The PWA's language before a choice** (the user, 2026-10-27: "Hvorfor var plutselig akkurat kalendermenyen i PWA på norsk når alt annet var på engelsk?" - then "Bare la det være som det er om det var i18n som slo inn, jeg er ok med det"): design 69's pages take `Accept-Language` at once, so nb pages appear tab by tab as the S-i18n slices land. Kept as is; nothing to build.
  - **Rounds S2 + S3 - security Lows, the server half** (`server-sec-lows-a`, `-b`; the launcher half is L4, the music half M2; **S2 merged 2026-10-26** (`server-sec-lows-a`, code QA `docs/review/qa-s2-server-sec-lows-a-code.md` 0/2/7/8 + fix round; server 0.55.0): the review's L1-L10 + 22 S7 - per-phone flood limits (60 security-log lines/h, 20 events of a kind/h, 3 urgent pushes/h except emergency calls), LAN feeds pinned to their judged addresses with same-host redirects only, dns-events bounded, storage budgets (500 reports/day replace the previous, 3000 fixes, 10 000 blocked domains, 500 PIN events, 50 crash hashes), CSP `script-src 'self'` (the rule editor's handler in `static/rule-kind.js`, a template scan test), `SESSION_MAX_DAYS` 90, the server address rule (https fine; http to Tailscale silent; http to another private address saved with a warning - the user, 2026-10-26: the address can't tell an encrypted tunnel from cleartext; http to a public host refused), pruning per statement, `CALL_STATE_FIELDS`/`TIME_STATE_FIELDS` (a new launcher key is dropped until listed - part of the API-in-both-dirs rule, with a contract-file test); L7 was mostly design 59's. **S3 (privacy half) merged 2026-10-26** (ea466a85, branch `server-sec-lows-b`, code QA `docs/review/qa-s3-server-sec-lows-b-code.md` 0/1/4/4 + fix round; server 0.57.0): P1-P8 except P3 parked - no fix stored while location is Off and **the newest fix per phone is kept past the window while location is on** (the user, 2026-10-26: "vi må alltid beholde last known location til neste login"; Locate shows "Last seen N days ago"), the parent's Off erases every fix and the `phone_off` positions at once (zone arrival/departure events stay to the location window), the 48 h status history keeps each phone's newest pre-window report (the boot race), `security::PhoneLine` typed phone-log kinds with their windows, VAPID `sub` = the repo URL, one neutral User-Agent, tiles' Referer = the origin only (accepted); decision 7's defaults still wait for J1's A3):
    - The security reviews' Low items (server 18, launcher 16, music 9).

### Done (not released yet)

(Entries older than 2026-10-27; the newer ones stayed in `PLAN.md` under "Done since the last release". Heading kept from the original.)

- **CI: arm64 image on a native arm runner (the user, 2026-10-24, branch `ci-native-arm64`)**: done - no QEMU; the
  release and CI image jobs are an amd64/arm64 matrix (`ubuntu-24.04`, `ubuntu-24.04-arm`), each built and smoked
  natively, then `server-release-image-merge` joins the two digests and tags `X.Y.Z` (and `latest`) as before.

- **Back from Apps › a phone goes back to Apps (2026-10-24, branch `pwa-polish-a`)**: the user: "på apps, om jeg går
  inn på feks Emma sin tlf og så trykker tilbake - så kommer jeg til device taben for Emma? Føltes unaturlig". The
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
  lock never steps aside for a timer (`42-lock-clock.md`, Fossify's source read; FossifyOrg/Clock's receivers and services
  are not exported and its only exported entry is an activity, `SplashActivity`, so no pause/lap/reset is reachable
  from the lock, checked 2026-10-21). **Device checks owed (the
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

- **The speaker's own Next/Previous on a podcast (measured 2026-10-21, the user's Pi, Emma, `sonos-play-test.py
  --press`)**: a double press (Next) did nothing at all - no socket event, UPnP kept counting (`canSkip: false` for our
  one-URI stream); a triple press (Previous) restarted the same episode at 0 (socket BUFFERING position 0 with
  `previousPositionMillis` 12248, same `itemId`, then PLAYING; UPnP TRANSITIONING 0:00:00), which matches the app's own
  "previous more than 5 s in restarts the episode"; a PAUSED at 4.75 s followed 5 s later (unexplained - possibly a
  press read as a single tap). So the speaker's Next can't drive the app (nothing to observe) without a Sonos queue, which
  the user ruled out for podcasts; Previous is visible as an outside seek to 0 that the app follows (device check: the
  app's bar and bookmark follow it).

### Shipped in `server-v0.61.0` / `launcher-v0.38.0-rc.1` / `music-v0.5.0-rc.8` (2026-11-01, main bc8dfb2b)

- **No code entry on the rule screen** **MERGED 2026-11-01** (`small-c` 5dea62b6; server CI 1470/0, jstest 204/0, launcher 1909/0; light code QA 0/1/3/2 fixed; shipped in server 0.61.0 / launcher 0.38.0-rc.1) - (the user, 2026-10-26: "ta vekk muligheten til å skrive inn opplåsingskode på låst modus"; decided 2026-10-26: **PWA only** - the parent lifts a rule from the PWA, no hidden path on the phone): drop the "Skriv inn opplåsingskode" prompt from the rule screen; the Emergency row stays.
- **Tidier status on Devices and the phone's summary** (the user, 2026-11-01, iPhone screenshots of Emma's row and header: "Online · seen for 7 min siden", "4 min today", "Battery 100 % · for 7 min siden", "At Test since 24. okt. 22:19" - "Trenger vi egentlig seen og battery xmin ago? Kan vi rydde og gjøre det penere? ... Kom med en mockup før vi implementerer noe"): the ages repeat, and the mixed nb/en is the S-i18n mid-state (slice 2's times in nb, the rest English until slices 3-4). Mockups A (one fact line; the ages only when offline; words only for what deviates - recommended), B (chips), C (one line) sent 2026-11-01 (artifact "Vibb phone status"). **Picked 2026-11-01: C, the minimal** (the user: "jeg liker best den minimale fra mockupen"), with "Slått av" told apart from "Frakoblet" (the user: "vet vi ikke egentlig om frakoblet betyr slått av?"): design 38's `phone_off` (newer than every `phone_on` and status report, `devices.rs` `power_off`) already says it on the summary - the list and the summary both say "Slått av 21:14 · batteriet var 3 %" then, "Frakoblet · sist sett 13:05" only when no shutdown reached the server. **MERGED 2026-11-01** (`status-tidy` 9def9748: `phone_status.rs`, `Zone::since`, `offs_now` + migration 0109's index, "Slått av" on the list too; server CI 1475/0; light QA 0/3/3/2 - the index, the list failing loudly and the screen-reader label fixed, the dropped tails and lock-without-screen-time kept as the user's minimal pick). Server templates + i18n keys, S.
- **The PWA offers notifications itself on its first opening from the Home Screen** **MERGED 2026-11-01** (`small-c` 5dea62b6; server CI 1470/0, jstest 204/0, launcher 1909/0; light code QA 0/1/3/2 fixed; shipped in server 0.61.0 / launcher 0.38.0-rc.1) - (the user, 2026-10-27: "når man legger PWA til på home screen/installerer app så ber den automatisk om push notification tillatelse og setter opp abonnement hvis man får OK fra bruker på det"): today the subscription lives only under Notifications › Settings › This Device (`static/notifications.js`, the Turn On button), and Devices shows a `push-warning` link there when the browser has none. Browsers (iOS Safari standalone, Chrome) only grant the permission prompt inside a tap, so the PWA cannot fire it by itself at launch; what it can do: at the first opening in standalone mode (`display-mode: standalone` / `navigator.standalone`, Push available, permission `default`, no subscription the server knows) a one-tap card at the top of the page - "Get notifications on this phone?" with Turn on / Not now - whose Turn on does the same `pushManager.subscribe` + form post as the settings button, so it is one tap instead of finding the settings; Not now remembered in this browser (`localStorage`) and the Devices link stays the way back; nothing shown in a plain Safari tab (the Home Screen comes first there, as the settings page says). Chrome's `appinstalled` can show it at once after an install too. Server static JS + a partial + i18n keys (the words through the catalogs), S; jstest for the state table. Queued in the server list after S-i18n slice 2.
- **Spotify diagnostic log for 15 minutes** (approved 2026-10-27) **MERGED 2026-11-01** (`spotify-diag` 66c57573: 09d3cdcc, 49345044, e0ec88a5 + the QA fix round; server CI 1467/0, launcher 1910/0, music 1236/0; migration 0108; light code QA 0/1/1/3, fixed; ships in the next launcher/music RC and server release) -: a one-shot button on Device › Advanced writes `music.spotify_diag_until` (epoch seconds, now + 15 min) into the policy (pinned by `policy_json_keys_snapshot` + the launcher's `PolicyResponseCompatTest`, both dirs in one commit) and nudges the phone; the launcher passes it to the music app the way the Spotify on-flag travels today (`MusicPlan` → the bridge's library hand-over); `PlaybackService.spotifyLog` writes `VibbSpotify` when `BuildConfig.DEBUG || now < until` (the renderer's lines are secret-free by design - `music/CLAUDE.md` "No `Log.` in `spotify/`"; the implementer re-reads every line it would print for tokens/account ids before shipping); the page shows "on until hh:mm" while it runs and the button again after; words through the catalogs. Then the user captures with `adb logcat -s VibbSpotify`. Server + launcher + music, S; after the colours.
- **The media session's artwork is a `file://` path in the music app's private cache** **MERGED 2026-11-01** (`spotify-diag` 66c57573: 09d3cdcc, 49345044, e0ec88a5 + the QA fix round; server CI 1467/0, launcher 1910/0, music 1236/0; migration 0108; light code QA 0/1/1/3, fixed; ships in the next launcher/music RC and server release) - (seen in the user's logcat 2026-10-27: SystemUI's `ImageLoader` fails with ENOENT on `/data/user/0/me.vibb.music/cache/art/<hash>.jpg` at every state change): `PlaybackService.artUri` puts the small copy's file path into `MediaMetadata.artworkUri`; the platform session hands that URI to SystemUI, which can never read our private cache (and here the file was already gone - `Store`'s small-art sweep or a copy never made). Media3's own bitmap loading covers our notification, so the visible effect is at most a missing picture on the system's media controls. Fix: a `content://` URI through a FileProvider for the small art (read-only grant to the system), or omit `artworkUri` and set `artworkData` for the session; and `hasCopy` re-checked when the metadata is handed over. Music app, XS-S; check first on the Jelly Star whether the lock screen / QS media card shows the cover.
- **Web Filter: say what a list switch means for the phones** **MERGED 2026-11-01** (`small-c` 5dea62b6; server CI 1470/0, jstest 204/0, launcher 1909/0; light code QA 0/1/3/2 fixed; shipped in server 0.61.0 / launcher 0.38.0-rc.1) - (the user, 2026-10-26: "hvorfor er web filter i settings noe som lar deg skru lister av og på? Virker ikke som om det er linket til en enkelt telefon? ... per tlf web filter liste valg ... tilsynelatende ikke stemmer overens"): the model is a default for every phone (Settings › Web Filter's switches, `dns_blocklists.enabled`) plus a per-phone override (the phone's Web Filter page: Default / On / Off, `device_blocklist_overrides`) - consistent in the data, but the two pages don't tell each other's story: the global row shows a bare switch, the phone page says "Default (Off)". Fix: each global list row gets a sub-line from the overrides ("On for every phone", "On for every phone except Testtelefon", "Off · on for Testtelefon"), the group header reads "Lists for every phone", and the phone page's option reads "As for everyone (On)" instead of "Default (On)"; the footers shortened to match. Server/PWA, XS-S; a browser pass of both pages. Verify against the code before building (`handlers/dns_filter.rs`, `device_pages.rs:1359`, `dns_filter.html:23`, `device_filter.html:51`).
- **Home's fold rule removed - the grid's edge where it was before rc.8** (the user, 2026-11-01, `jellystar2` on rc.8 with the music card: the third row cut at its icons' top half - "hvorfor kutter vi ikonene så høyt oppe? det liker jeg ikke"; options sent, the pick: "Kutt lavere, sånn ca heller der det var før"): `gridFoldPaddingPx`/`foldGrid` and Home's pre-draw hook deleted; the grid runs to its edge with the 16 dp padding and the bottom fade. Merged 2026-11-01 (`home-unfold`; Home unit tests 115/0). Ships in the next launcher RC.
- **Dagen: the key always, and today again after a minute away** (the user, 2026-11-01, a `jellystar2` screenshot of a day with one event: "jeg ønsker at den alltid må vise legend" and "etter feks 1 minutt ute av fokus så begynner den alltid tilbake på i dag"): `DayView.legendShown` = any event; `DAY_BACK_TO_TODAY_MS` 60 s - stopped that long, or a saved state that old, opens on today, a day asked for (the reminder row) wins. Built and merged 2026-11-01 (`dagen-small`; Dagen unit tests 64/0). Ships in the next launcher RC.

### Shipped in `server-v0.62.0` / `launcher-v0.38.0-rc.2` / `music-v0.6.0-rc.1` (2026-11-01, main 63cb6bbf)

- **Device › Time becomes "Tidsregler", tidied** (the user, 2026-11-01: "en on off bryter for alle universale rules og kunne velge per regel ... ta bort mulighet til å pause rules og gi extra time her, for det er bare duplicat"; mockup approved: "Mocukp ser bra ut, kjør på!") **MERGED 2026-11-01** (`time-rules-page` 5fbbd96c + fix round 1a42e823 + merges incl. 81108273 (0114 before 0115); server only, migration 0115 `device_rule_off`; 1522 server tests, CI 41353399989 green; code QA 0/1/6 - M1 the migration order, met by merging B2 first; the order test is now `migrations_from_73_in_order`) - the page and the summary's row say "Tidsregler"; Extra time, Pause a Rule and Recent leave the page (Quick actions keep them, "Ba om mer tid kl. …" moved to the top of Quick actions); a switch per rule for everyone on each phone (a later rule is on for every phone), own rules always apply, "Egen grense" picks the own daily limit; 0115 back-fills off-rows so no phone's effective rules changed (an Own-rules phone now gets later global rules on - by design). Asked of the user, not answered: ended lifts now only in the security log - add them to the summary's Activity? Shipped in `server-v0.62.0`.
- **The emergency menu (round B2, design 73)** (the user, 2026-10: "112 er utgangspunktet (som kan endres) og så kan man legge til flere nr. Da dukker det opp som en valg-meny"; mockup A approved without legevakt; "Nei kun ordentlige nødanrop fra låst skjerm") **MERGED 2026-11-01** (`emergency-menu` d9e15c3e note + 3ac24e2d + fix round cad07f18 + merges; launcher + server, migration 0114, shared `server/testdata/emergency_numbers.json`; server 1516/0, launcher 1943/1938, CI green; code QA 0/2/4, all fixed) - Nødsamtale opens a sheet "Ring nødnummer" (the first real emergency number big and red, rows, one tap calls, the rows scroll, Avbryt below); one number = today's "Ringe 112?"; **from any locked screen only real emergency numbers call** - other rows dimmed "Lås opp for å ringe"; unlocked they follow the call rules (never the emergency dialer); real = 112/911 + the country's (47: 110/112/113) + the platform's `isEmergencyNumber`; PWA Device › Anrop og kontakter "Nødmeny" per phone (↑↓, remove, add, suggestions 113 Ambulanse / 110 Brann for +47, at most 6, never without an emergency number - also repaired when the country code changes), `call_policy.emergency_menu` always sent (older launchers: 112). Launcher build memory raised to 3 GB (84cde2e4: the Kotlin daemon ran out of heap on CI). **Emulator check owed:** the sheet from every entry point, one-number dialog, the emulator's own emergency number (never a real call), locked rows dimmed, 6 rows at font 2.0, the PWA group at 390 px. Ships in the next server release and launcher RC.
- **A phone that is off or out of touch says so: dot and line agree** (the user, 2026-11-01, Emma off at 20:35 with an orange dot and no "Slått av"; "Hva er egentlig Finn sending??"; mockup approved: "Jeg aksepterer ditt mockup forslag, kjør!") **MERGED 2026-11-01** (`status-off` ef57e1a0 + fix round 22d32672 + merges; server only; 1503 server tests, CI 41350342879 green; light QA 0/0/3, two fixed, About's raw "Borte" kept on purpose) - `phone_status::dot`: off grey at once; no stream under 10 min green (`NO_CONTACT_AFTER_MINUTES`); 10-45 min orange "Ingen kontakt · sist hørt for N min siden" + the rest; over 45 min grey "Frakoblet · sist hørt hh:mm"; on Devices, the summary header and Locate (labels follow the dot); `offs_now` and Locate's `before_off` compare the off's server receive time (`created_at`, within 5 min of the shutdown) with `reported_at`, so a phone clock behind or a report at shutdown no longer hides "Slått av"; the off detail "Siste posisjon kl. 20:26 – se kartet i Finn"; Locate's list "sist hørt 13:05" from the catalogs. Shipped in `server-v0.62.0`.
- **CalDAV calendars, read-only (design 72 step 2)** (approved 2026-10-27, pri 2) **MERGED 2026-11-01** (`caldav-read` bd88bc74 + fix round 1bea2e2a; server only, migration 0110; server 1486/0, CI green; code QA 0 High / 2 Medium / 6 Low + one test gap, all fixed: M1 the server's own hrefs never reach loopback/LAN on a public account and the password goes only to hosts that answered 401, M2 a calendar no longer listed gets `not_listed` instead of stopping the account, L3 no deleting trigger - an unused account goes 24 h after last use, L4 the 401 stop is nonce-guarded, L5 a typed password is stored only on a passing Test, L6 a REPORT at least daily, L7 the cap checked before any tick, L8 one account count; ships in the next server release) - Device › Calendar "CalDAV-kontoer": server or e-mail, username, app password, Test; one switch per calendar with events; a ticked calendar reaches the phone as a step 1 link (`calendar.feeds`, no launcher change); the account is the family's, each phone ticks its own; iCloud/Fastmail mail domains map to their servers; Google points to its secret iCal address. **Unverified against real servers** (iCloud, Fastmail, Nextcloud, Radicale; the fixtures are written in their shape, not captures; design §10 / emulator.md §6y step 2 not run) - the user's own account test after the next server release.
- **Device › Calendar: one "Hentes av serveren" list, a page per source, an Add Calendar page** (the user, 2026-11-01, an iPhone screenshot of Kalenderlenker: "litt rotete og lite proft sammenlignet med resten ... burde være litt enhetlig"; mockup B redrawn with the PWA's own parts and approved: "Jeg er fornøyd med kalender mockup nå") **MERGED 2026-11-01** (`calendar-sources-ui` 4b1f262d + 6dee3366 + merge e1df0ae7 + fix round e25d9905; server only; 1490 server tests, CI 41329561223 green; light code QA 0/0/7, all fixed) - links and CalDAV accounts as `.group-icons` rows (teal link tile, indigo account tile, sub-line, failing reason in orange) with `Legg til kalender…`; a link's page (`/devices/{id}/calendar/links/{uid}`: Navn inline, Lenke, Henting rows, Hent nå, Fjern…), an account's page (`/calendar/caldav/{account}`: Server, Logget inn, the allowed hosts, a switch per calendar, Test på nytt…, Fjern konto…; a calendar the account no longer lists dimmed with Remove), an add page (`/calendar/add`, the Add App CSS-only source choice Lenke / CalDAV-konto, `#add`); handlers in `handlers/calendar_sources.rs`; orange `flash-warn` for a failed Test, red for a refused save; style.css v67. Shipped in `server-v0.62.0`.
- **Lock-screen reminders: the quiet row plus a ping** (the user, 2026-10-27: "rolig rad only, eller rolig rad plus et ping?") **MERGED 2026-11-01** (`reminder-ping` bc57df62 + fix round ddc20fcb + merges; launcher + server, migration 0113; server 1499/0, launcher green, CI 41343127956 / 41343130724; light code QA 0/2/2, all fixed) - `lock_reminders` off / row / ping (stored as the row + `device_policy.calendar_lock_ping`), served as `ping` only to a launcher claiming `calendar_ping_v1` (older ones get `row`); the phone plays the default notification sound once per reminder (event + start, kept a day) only if the ringer is normal, no DND, no call, no time rule or bedtime, the row is visible (screen off or the lock/glance up - not while the phone is in use; the coordinator's call) and within 90 s of the row coming up; an exact alarm (`USE_EXACT_ALARM`) at the next moment a row can come up, kept before the first unlock. PWA select "Stille rad + lyd" with its ⓘ. **Emulator check owed:** the sound in logcat (`ReminderPing`) / `dumpsys audio`, none under a time rule or in use. Ships in the next server release and launcher RC.
- **The time-rule screen takes the lock glance's look (round L6)** (the user, 2026-10-26, Jelly Star screenshots of school mode) **MERGED 2026-11-01** (`rule-screen` 7d389ef8 + fix round b90e76f5; launcher only; launcher 1910/0, CI runs 41312624726 / 41313773505 green; light code QA 0/0/5, three fixed: the first frame waits for the lock ground (`holdForLockGround`), the card is a TalkBack heading on this screen, the root ScrollView `clipToPadding="false"`; L1 the date pattern on a live locale change and L5 left, as the glance) - the glance's clock face, date and alarm line in the same window spot (shared `lock/LockClockFace.kt`, the profile clock on the Jelly Star so it does not jump at unlock), the glance's rule card (`view_lock_rule_card.xml` + `lock/RuleCardView.kt`) with a third line for what still works, the glance's red Nødsamtale button, the lock's ground instead of the plain night (the implementer's own addition, `applyGround()`, easy to drop); a long rule name is cut with "…" on one line as on the glance. Behaviour unchanged. **Device check owed** after the next launcher RC: no clock jump at unlock, cold-start first frame, readability over the lock picture, TalkBack. Ships in the next launcher RC.
- **The tab bar's icons keep their shape when chosen** (the user, 2026-11-01: "Egenrlig vil jeg at outline og form er identisk når de skifter farge? Det blir roligere? Så kanskje den helst bør være litt fetere i både grå og fersken farge? Gjelder alle tegnene") **DONE 2026-11-01** on main - one outline icon per tab (the filled `tab-icon-on` set removed), stroke 1.8 -> 2.1 in both states, the chosen/pending tab only turns it `--tab-on-icon`; the label stays unbolded (pick B's rule); style.css v68. Shipped in `server-v0.62.0`.
- **S-i18n slice 3b - the Apps tab in nb and en (design 70 §5.1)** **MERGED 2026-11-01** (`pwa-i18n-3b` 0bd38ce6 + fix round c3467257 + merge of main e045fdd4; server only; 1498 server tests, node 210, CI 41338692394 green; light code QA 0/0/2, fixed: Install Now's poll keeps the page's language, seven nb wordings) - apps_list, tracked_app_add/detail, app_display_form, app-display.js, install-now.js and install-now.json; tracked_apps.rs (incl. S4's five sync problems and the F-Droid repo/package refusals), app_display.rs, install_now.rs; 237 new keys per catalog, the nb list sent to the user. Shipped in `server-v0.62.0`.
- **S-i18n slice 3a - the Devices tab in nb and en (design 70 §5.1)** **MERGED 2026-11-01** (`pwa-i18n-3a` 11df8be4 + 42b6077f + 2d461ad9 + fix round b735b98d; server only; 1489 server tests, node 208, CI 41324420189 green; code QA 0/2/5: the photo-save error worded, every catalog key used (new test), a pre-deploy session keeps its 2FA banner, both scans also catch literals in `{{ }}`/`{% %}`, `|safe` on a bound word and backtick strings, the restart lines reworded; mixed nb/en on the summary values, Attention and lift rows accepted until slice 4) - every 3a template and partial (devices list, a phone's summary and sub-pages but Locate/Calendar, Add Phone, the setup code and QR, the tab bar, bell, status line, push offer, confirm), `partials/js_words.html` for reconnect.js / nav-timing.js / reveal.js, the handlers' titles, notices and error bodies; 664 new keys per catalog, the nb list sent to the user ("Attention" = "Trenger tilsyn"). Shipped in `server-v0.62.0`.
- **The launcher's security Lows, first branch (round L4)** **MERGED 2026-11-01** (`launcher-sec-lows` 74e6ba82 + fix round f9b8c73f + 90760516; launcher only; CI 41321407539 green; every item re-verified against the code first, all 15 still applied; code QA 0/1/5, all fixed) - S-L1 the lock/VoIP keep only intents their notification's app created, Home opens only the player's own session activity; S-L2 the crash guard counts only crashes in the lock screen's UI classes; S-L3 at bedtime every system clock and a signer-checked Fossify Clock are spared, no other alarm app; S-L5 Bluetooth file sharing blocked while managed (override/pause lift it; headphones pair); S-L6 the kid-PIN wait doubles up to **1 h** (`PIN_BACKOFF_CAP_MS`, the user's pick), a try whose count can't be saved is refused with "only the parent code works"; S-L7 the time-rule alarm on a non-exported receiver; S-L8 the music app trusted by the launcher's signer (grants, config, nudge, exemption, bridge, session pick); S-L9 DNS limits (16 in flight, 256 held, a 3 s slot wait, FORMERR for QDCOUNT != 1, one DoT deadline incl. the handshake, the block answered before the log write); S-L10 a tsnet load failure fails the connect; S-L11 `DISALLOW_CONFIG_VPN` also on filter-only phones (parent's off wins, override lifts); P-L1 contact-like channel ids as a per-phone HMAC tag; P-L4 no Wi-Fi names in logs. **E4 device checks owed:** VPN flood, Bluetooth sharing blocked / headphones fine, the music signature pairs, bedtime alarm from the system clock/Fossify, VPN settings greyed on a filter-only phone. Ships in the next launcher RC.
- **The music app's security Lows (round M2)** **MERGED 2026-11-01** (`music-sec-lows` 03aa1ebd + d217d2a7 (S-L4) + fix round 50ce1f37; music only (+ `scripts/sonos-ws-probe.py`, its selftest in music.yml); music 1247/0 debug + release, CI 41330234797 green; all 7 items re-verified and fixed; code QA 0/2/4, all fixed) - S-L1 a SOAP reply capped at 1 MiB while read, a 10 s call deadline (`Soap.CALL_DEADLINE_MS`), DOCTYPE/ENTITY refused, a leading BOM stripped; S-L2 other apps' controllers play/pause/skip but can't set the queue's items; S-L4 a public entry's http audio tried over https first, any failure or a `text/html` answer falls back to the listed URL and marks the host plain for 24 h (`HttpsFirst.PLAIN_FOR_MS`, the user: "Å teste https først for podcaster høres fornuftig ut"), cache keys and rows keep the listed URL; S-L5 the capture scrubber refuses leftover ids/MACs/IPs/signed URLs (also percent-encoded), console shape-only output left out on purpose; P-L1 release Sonos logs without uids, rooms, titles or URI paths (scheme only); P-L2 only the home household's speakers reported - home from the room last played on, else the household with most players seen in 30 days (`SpeakerCache.REPORT_MAX_AGE_S`), home rooms kept however old; P-L3 no cloud backup or device transfer. Defaults (10 s, 30 days, 24 h) as recommended. **Device checks owed:** lock screen / Bluetooth / Home card still control playback, an http podcast via https with one ~4 s fallback where unsupported, release logcat shows `RINCON_#xxxx` tags, a friend's rooms never in the PWA's picker, §6h with a trickling fake speaker. Ships in the next music RC.
- **F-Droid trust (round S4)** **MERGED 2026-11-01** (`fdroid-trust` 9fdd836d + fix round cde4205f; server only, migrations 0111 + 0112; server 1496/0, CI 41335399686 green; code QA 0/1/3, all fixed) - Q31: entry.json read only out of `entry.jar` after its JAR v1 signature checks (`jar_signature.rs`: manifest digest, .SF over the whole manifest, CMS SignedData by ring, SHA-256 only, strict DER) and the signer's certificate matches the repo's pin - compiled in for f-droid.org (`43238d51…ccab`) and apt.izzysoft.de (`3bf0d6ab…937a`), from F-Droid's client and the repos' own pages; any other repo pinned at its first good fetch (`fdroid_repo_keys`), the pin dropped with the repo's last app (the user approved TOFU, the word "repo", SHA-256 only); a bad signature or key fails closed (last good index and release kept, the reason on the app's page in nb/en); a signed entry.json older than the newest taken is a rollback (`fdroid_repo_state`), and the hourly sync never moves a release to a lower versionCode (Check Now/Forget may); Q32: at the first pin the APK's signer must be one index-v2 lists (listed-but-unreadable refused). Q33 (ABI) left in the queue. **Live check owed on the VM:** an f-droid.org and an IzzyOnDroid app sync with Check Now, no RepoSignature/RepoKey, `fdroid_repo_state` gets both rows, `fdroid_repo_keys` stays empty for them. Shipped in `server-v0.62.0`.

## Release tags 2026-11-02..2026-11-04

Moved out of PLAN's queue 2026-11-07. The tag history paragraph (it opens with the E7, E7b, E7c emulator sessions note, done 2026-11-02, run docs `docs/testing/2026-11-02-e7-run.md`, `2026-11-02-e7b-run.md`; the Jelly Star leftovers it lists are kept in PLAN's waiting list). PLAN keeps one line: "Last tags: ...".

**Emulator sessions E7, E7b, E7c DONE 2026-11-02** (run docs `docs/testing/2026-11-02-e7-run.md`, `2026-11-02-e7b-run.md`; all findings fixed and re-checked; the four branches MERGED, see Done). Left for the Jelly Star: SOS by five power presses, a VoIP ring card, Settings › Modes, the Jelly's own clock and Fossify (the alarm row and the Clock row), H1 on a real SIM (which dialer shows 112, the call log, Telecom's emergency-callback mark and its length), M-B1 on the device, a real iCloud account; E7c saw "App is not available - Pixel Launcher" once after the unlock from Android's PIN pad following a reboot (the suspended stock launcher's recents task first in the lock-task list) - check on the Jelly Star. **`server-v0.64.0` + `launcher-v0.38.0-rc.4` TAGGED 2026-11-03** on 9b96886e (the user: "Push når du er klar"; CI green; the Done list above ships in them: kid-unhide, updates-cache, lock-faceid, pwa-i18n-3d); signing = the user's approval. Next tags: `launcher-v0.38.0-rc.5`, `server-v0.65.0`. **`server-v0.66.0` + `launcher-v0.38.0-rc.5` TAGGED 2026-11-03** on cfdb82fa (the user: "ja bare å pushe tags da"; main CI green on it: server, launcher, music): design 75 (the lock weather), the Vær group on Device › Låseskjerm, "Nylig bruk" gone, the contact photo crop + kept originals, nb photo refusals, Home's contact ring; music unchanged, no tag. Signing = the user's approval. Next tags: `server-v0.66.1` (the 10-min return to Devices), `music-v0.6.0-rc.2` when the music app changes. **`server-v0.65.1` TAGGED 2026-11-03** on 70623007 (the user: "ja tagg 0.65.1 takk"; CI green; the lock's Face ID starts on its own again). **`server-v0.65.0` TAGGED 2026-11-03** on 2d7cac41 (the user: "do it!"; CI green; ships S-i18n 3e and the Pust lock); signing = the user's approval. Next tags: `server-v0.66.0` + `launcher-v0.38.0-rc.5` with design 75 (the lock weather). **`server-v0.63.1` TAGGED 2026-11-03** on d8cbdb73 (small-d: the tab icons as mockup A, the kid's app names, On Home behind an (i), CalDAV 403 words, add from the family; CI green). **Release 3 TAGGED 2026-11-02** (the user: "Ta e7b og så tagger du"): `server-v0.63.0` and `launcher-v0.38.0-rc.3` on 9bbe1aa8 (main CI green on it and on the merge ef1865db); music unchanged, no tag. The release workflows wait for the `release` environment's approval (the user's). Release note: running IPv6 bans are lifted at the upgrade (passkeys step 2, /56 keys). Next tags: `launcher-v0.38.0-rc.4`, `music-v0.6.0-rc.2`, `server-v0.64.0`. **Release 2 PUBLISHED 2026-11-02**: `server-v0.62.0`, `launcher-v0.38.0-rc.2` and `music-v0.6.0-rc.1`, all on 63cb6bbf (the launcher's build re-run after a proxy.golang.org stream error, then signed). Next tags: `launcher-v0.38.0-rc.3`, `music-v0.6.0-rc.2`; the Done list below ships in the next server release. **`server-v0.67.0` + `launcher-v0.38.0-rc.6` + `music-v0.6.0-rc.2` TAGGED 2026-11-04** on 2e73a78e (the user: "Bare tag og push når du er klar"; main CI green on it: server, launcher, music; E9 + E9b passed): the "Done since the last release" entries up to the E9 fixes ship in them. Release note: release the server first; a phone whose server URL is plain http and not a Tailscale IP (a LAN address, `http://*.ts.net`) stops syncing - fix it in Vibb One Settings › Server URL with the parent code (a phone with no parent code needs a reset); no host actions any more (Software Updates shows versions only). Signing = the user's approval. **`server-v0.68.0` + `launcher-v0.38.0-rc.7` TAGGED 2026-11-04** on 810ecd2e (the user: "Du kan tage og pushe"; main CI green on it: server, launcher, music): S-i18n slice 5, the per-app lock media switch + the E9b leftovers, push never lost silently (migrations 0127, 0128); music unchanged (rc.2). Signing = the user's approval.

## Moved out of PLAN's queue 2026-11-07 (built and merged; audit)

The queue was checked against the code on 2026-11-07; every entry below is built and merged. The text is verbatim; the line under each heading is the evidence. Where an entry had an unbuilt, undecided or declined part, PLAN keeps that part as a short line in its waiting, scope-not-settled or dropped section.

### Emulator session E6 (done 2026-10-31) and the releases it was followed by

Evidence: `docs/testing/2026-10-31-e6-run.md`; the releases are tagged (see Release tags above and the Done list).

**Emulator session E6 DONE 2026-10-31 on `jelly36` (main 9064518e; run doc `docs/testing/2026-10-31-e6-run.md`):** nothing blocks the release. §6y calendar links 22 pass / 0 fail (2000 events applied in 3.0 s; a kill mid-apply recovers at the next sync; both EXDATE forms honoured, `EXDATE_COLUMN` stays; DST faithful); §6w 8-9 18 pass (Dagen its own task, E5 H1 fixed; dots, legend, the lock row's dot, auto-pick, "No free colour"); §6 step 12 fold 4/4; B6 4/4 (the essential list names no GMS; no GMS line in an hour - the image has no Google account, so the nag stays a device check); nb heading "Lørdag"; the Android 16 image's Conscrypt still trusts emSign ECC Root CA - G3 (a Conscrypt mainline update drops it, nothing of ours to do); Etar's battery-intent crash reproduced (Etar's own). **`launcher-v0.37.0-rc.8` + `server-v0.60.0` PUBLISHED 2026-11-01** on main 2e2b243b (CI green, 1463 server tests; server release run 41281600476 with the signed tarballs and the image, launcher run 41281600354, a prerelease). **`server-v0.61.0` + `launcher-v0.38.0-rc.1` + `music-v0.5.0-rc.8` PUBLISHED 2026-11-01** on main bc8dfb2b (CI green; release runs 41304221491 server, 41304222053 launcher, 41304222008 music; the launcher went to 0.38.0-rc.1 because rc.9 is out of range - rc.1..8, the 9th code is the release).

### E6's Low findings (L3, L5 and the doc wording of L2/L4)

Evidence: L3 and L5: `small-c` 5dea62b6 (merged 2026-11-01); L2/L4: wording in `docs/testing/emulator.md`. L1 (requestLayout log lines) stays in PLAN's queue.

  - **E6's Low findings, queued (not started):** **MERGED 2026-11-01** (`small-c` 5dea62b6; server CI 1470/0, jstest 204/0, launcher 1909/0; light code QA 0/1/3/2 fixed; ships in the next server release and launcher RC) (L3 and L5 only; L1 stays) - L1 the lock glance's views log `requestLayout() improperly called` (12 lines; none from Home); L3 without the music key the calendar-link form says nothing - the server makes a new key at start and old links turn `key_lost` only at their next fetch (a notice on the Calendar page); L5 each time-rule redirect stacks another `LockActivity` in the home task (three seen; reuse the instance). L2/L4 are test-doc wording (webcal needs https; a removed link's row; the task count grep; the LAN feed address), fixed in emulator.md with the run notes.

### Round L1 - glance and Home layout

Evidence: `launcher-glance-c`, 85abe49f (merged 2026-10-26). Q5 (fingerprint with the screen on) stays in PLAN's waiting list.

  - **Round L1 - glance and Home layout** (merged 2026-10-26, `launcher-glance-c`; RC3-8 pinned, six-inch F3/F4 and the lower music tile built; only Q5 is left):

### Round L2 - pause end and enforcement

Evidence: READ_PHONE_STATE: fddfc0ce (`launcher-enforce`, merged 2026-10-26). The pause-end kill and the VoIP fixes are dropped (PLAN's Dropped list); hide-and-unhide is in Asked for.

  - **Round L2 - pause end and enforcement** (`launcher-enforce`; **merged 2026-10-26**, code QA `docs/review/qa-l2-enforce-code.md` 0/0/3/3 + fix round; ships in `launcher-v0.37.0-rc.6` with L3): READ_PHONE_STATE (d) built; **the pause-end kill is not buildable** - since Android 14 `killBackgroundProcesses` only ends the caller's own processes (AOSP 5b7edbf2ba07; no device-owner force-stop; only hiding kills an app, and that drops its alarms/jobs/widgets), so suspended apps stay cached until Android's low-memory killer reclaims them - the user's 2026-10-26 `dumpsys meminfo` (WhatsApp 92 MB, Element X 86 MB resident) is that, not a leak; `launcher/CLAUDE.md` gotcha; the item below stays only if the user wants hide-and-unhide for the apps that are blocked anyway. Per decision 3 the VoIP fixes (a), (b), (c) are dropped ((a): E1 never reproduced the missing call screen in android mode); the App blocker waits for the package dump:

### Software Updates caches (tailscale / apt)

Evidence: `updates-cache` is in Done (313acd82); `updates-cache-2` was dropped 2026-11-04 when the host actions were removed (PLAN's Dropped list).

  - **Software Updates still runs `tailscale version --upstream` (a network call to pkgs.tailscale.com, 5 s timeout) and `apt list --upgradable` on every visit** (found by `updates-cache`, 2026-11-03; now in parallel, not cached): cache them like the release tag if the page is still slow. **DROPPED 2026-11-04** (`updates-cache-2` e4c19eb0 not merged: the host actions go, see "No host actions from the server at all"; was built, light QA done (`qa-updates-cache-2.md`: 1 H - the updater busy check never saw a running oneshot, pre-existing; 2 M, 5 L); fix round ON HOLD 2026-11-04 - the user asked whether the PWA may act on the host at all ("PWA skal ikke ha rettigheter til å gjøre noenting som helst på host vel??"), see Asked; small.

### Round S1 - server small items

Evidence: f20cc7f5 (`server-small-e`, merged 2026-10-26). The WebKit pass of the summary stays in PLAN's waiting list.

  - **Round S1 - server small items** (`server-small-e`; **merged 2026-10-26**, f20cc7f5, code QA `docs/review/qa-s1-server-small-e-code.md` 0/2/5/4 + fix round; server 0.54.0, tag after design 66 lands or on its own if that waits): `PHONE_SERVER_URL` in `.env` (DB wins; QR + Connect page; `deploy/env.example`, `install.sh`); episode pictures: the r5 finding was a per-host hold (three failures on one dead host ended the whole art pass) - now per host, plus a pass-wide cap of 6 while no host answered; GitHub asset `digest` into `ReleaseAsset.sha256`, a mismatch refused (catalog APKs; tarballs keep minisign); page timing (`static/nav-timing.js`, `?timing=1`, Software Updates › Diagnostics › Page timing row - step 1 only, the prefetch step stays below); settings saves in the security log (`settings_log::log_saves` inside `require_full_auth`: a 303 back to a page = a save, refusals-by-redirect carry `settings_log::Refused`, a handler that wrote its own event gets no second row, 10-min coalescing per parent and page, `NOT_SAVES` list); one red alert while paused (the Attention row stays, the Quick actions card is one plain line; browser pass light/dark done); the dead-code review's server list, D3's server half, D4's route. **Owed to the user:** the iPhone tab-switch numbers through About › Page timing after the tag; WebKit pass of the summary.

### PWA tab-switch timing, step 2

Evidence: `pwa-i18n-3d`, d8712d36 (lazy covers, the timing line).

    - PWA tab-switch timing step 2: **measured 2026-11-03** (the user's iPhone, 5G): Enheter tap 121 ms, Apper 95, Finn 126 (ttfb 66-69, connect 0, dns 0) - prefetch would save little; Musikk tap 640 ms with the same ttfb = its covers hold the load event. **No prefetch**; instead lazy covers (`loading="lazy" decoding="async"` + size) and a wrapping timing line, added to `pwa-i18n-3d` (IN BUILD).

### Dagen: the kid's date picker shows no day numbers

Evidence: 5cb066d0 (`day-view`, merged; `VibbPickerDialog`, `PickerThemeTest`). "Seen on a screen" stays in PLAN's waiting list.

  - **Dagen: the kid's date picker shows no day numbers** (the user, 2026-10-27, `jellystar2` screenshot of "Ny avtale": the framework `DatePickerDialog`'s header and the selected day's circle, no other numbers, no weekday row): a theme gap - the dialog's day text is drawn in the background's colour. **Fixed 2026-10-27** (5cb066d0 on `day-view`, merged): the activity follows the system's day/night mode, so in light mode the system's light dialog drew our white day numbers on white; both pickers now use `R.style.VibbPickerDialog` (parent the framework's dark `Theme.Material.Dialog`, every colour named, the day/clock numbers through `res/color/vibb_picker_number.xml`), `PickerThemeTest` pins the constructors, the items and 4.5:1 contrast; emulator.md §6w step 7 (light and dark via `cmd uimode night`). 1878 launcher tests. Ships in rc.8; not yet seen on a screen.

### The notification filter kept Play services' nag with the switch ON

Evidence: 17ac7c1c (`nf-keep-log` + fix round via `launcher-small-b`: the keep reasons are logged). The logcat at the next GMS nag stays in PLAN's waiting list.

  - **The notification filter kept Play services' cross-device welcome nag with the switch ON** (the user, 2026-10-27: "notification filter har vært PÅ hos meg helt siden starten ... it does not work correctly"; the dumpsys: `com.google.android.gms`, channel `multidevice...WELCOME_CHANNEL_ID`, flags `AUTO_CANCEL`, importance 4, our listener allowed): `nagVerdict` keeps it only as POLICY_UNKNOWN/SWITCHED_OFF/UNMANAGED (the runtime sweeps once the policy is known, so not the first), ESSENTIAL_PACKAGE (the resolved essentials: `essentialNotificationPackages` - the SMS_CB/SMS_EMERGENCY_CB receivers with MATCH_SYSTEM_ONLY, dialers, Telecom, emergency dialer, clock, IMEs; **GMS lands there if it registers a cell-broadcast receiver on this image**), or ALLOWED (the effective allowlist + messaging apps + a rule's usable apps). The user's checks (2026-10-27): both cell-broadcast actions resolve only `com.google.android.cellbroadcastreceiver` (GMS is NOT an essential by that route); logcat `Rule active: 9 essential packages` while the nag was posted (~05:37) and at the listener's reconnect 06:31 (a sweep); a shell notification (`cmd notification post`, package `com.android.shell`, clearable) was removed at once - **the rule works in general; GMS's is kept or its cancel refused for a reason we don't log**. The server never allowlists Play core (`play.rs` `PLAY_CORE`), `facts()` reads nothing that throws for it. The full logcat holds no `cancel com.google.android.gms` line, and the first cancel of any package/channel is always logged (`nagLogDue(1)`), so `act()` never ran: **the verdict was Keep** - the remaining reasons are ALLOWED (the allowlist ∪ the contacts' messaging apps ∪ a lock's usable apps) or ESSENTIAL_PACKAGE (the 9 resolved essentials), neither logged. **Fix MERGED 2026-10-27 (`nf-keep-log` 9e04fc84 + the fix round a40f318f via `launcher-small-b`: `kept <pkg> / <channel>: <REASON> [via allowlist|messaging|lock-usable] (N so far)`, FACTS_FAILED/GROUP_CHILD_KEPT for the silent returns, `platform kept` after a cancel that did not take, emulator.md B6; 1882 tests) - code QA `docs/review/qa-nf-keep-log-code.md` 0/0/4/3 Merge; fix round done: the unreadable-children path logs `ACTIVE_UNREADABLE`, `refresh` logs the resolved essentials BY NAME, every filter line under tag `NotificationRule`, skipped post-cancel checks counted; ships in rc.8 - then the user's logcat at the next GMS nag decides (B6)** (launcher, XS-S; the user, 2026-10-27: "kommer vi til bunns i hvorfor den fortsetter å vise notifications er det jeg lurer på"): (1) log a keep's reason for a non-essential package at the cancel lines' rate (package, channel, `NagKeep`), (2) after a cancel check once (1 s) whether the key is still active and log "the platform kept it" (Android 15/16 refuses some listener cancels), (3) emulator.md: the shell-notification check (`cmd notification post -S bigtext -t T tag1 hei` vanishes within 1 s with the filter on, stays with it off).** Then re-read the user's logcat at the next GMS nag.

### Spotify playback on `jellystar2`: starts, the progress never moves

Evidence: 6d513bb4 (`spotify-diag`: the 15-minute diagnostic log, merged 2026-11-01). The next occurrence stays in PLAN's waiting list.

  - **Spotify playback on `jellystar2`: starts, the progress never moves, then "Trenger internett"** (the user, 2026-10-27, screenshot: "Cult Logic - Miike Snow", the time row "Kobler til…", the pause button shown): under investigation - the shape matches the web player's two watchdogs in `music/.../spotify/SpotifyRenderer.kt` (`armFirstState`: no state of ours within 10 s of the play call; `armGap`: no state for 30 s while playing; both log before failing with UNREACHABLE). Asked the user for `adb logcat -s VibbSpotify VibbMusicDebugFeed MusicBridge BridgeSync` across a repeat, whether sound comes out, and whether the track played before. Hypotheses: the page's state posts don't reach the bridge, or they carry a relinked uri the tracker doesn't count as ours. **Not captured 2026-10-27**: the Spotify log is debug-only (`spotifyLog` under `BuildConfig.DEBUG`), so the release app on `jellystar2` gave no `VibbSpotify` line; the user started another playlist and it played again (a healthy session in the general logcat: positions advancing, Widevine key renewed). **Captured 2026-10-27 19:59 (read 2026-10-31)**: play at 19:59:32 (BUFFERING at 15117 ms, a resume mid-track after the app had been in the background), the page opened a new Widevine session, got its key, created the audio decoder and took audio focus at 19:59:34 - so the SDK WAS playing - and at 19:59:43, 10 s after the play call, the app went PAUSED at the same position: `armFirstState` ("no state of ours after the play call"). The page's state posts don't reach the renderer (or aren't judged ours) after a background spell - suspect the WebView's JS timers/events paused or throttled while the app was invisible (`SpotifyWebHost`: `onPause`/`pauseTimers`? the page's `postMessage` path), a relinked uri second. The diagnostic log (below) decides; the fix likely `resumeTimers` + a re-read of the SDK state on a start. Open as intermittent: the next time, before touching anything, `adb logcat | grep -i -E "vibb.music|spotify|scdn|chromium|cr_|widevine|MediaDrm"` plus the Web filter's Blocked Activity (the host list `spotify_hosts.json` is still provisional - a refused CDN host would give exactly this shape), or the debug music app on the emulator. **Approved 2026-10-27** (the user: "Ja legg gjerne til diagnoselogg i 15 min for spotify takk"): a parent-side "Spotify diagnostic log for 15 minutes" button under Device › Advanced.

### Spotify audiobooks shared as shows

Evidence: 6d513bb4 (the audiobook sentence and the `SPOTIFY_AUDIOBOOK_SPIKE` flag). The spike itself stays in PLAN's waiting list.

  - **Spotify audiobooks shared as shows end in "Nothing in it can be played here"** (the user, 2026-10-27, iPhone screenshot: "Vitser: prøv å ikke le 3", `spotify:show:5VDiAIsKP2QCJKDNe0kDI0`, and `show/5Mf1oJgaKevODXPdivLdqB`; "spotify har kommet med lydbøker i Norge! Men den funker ikke for oss?"): Spotify now shares audiobooks as show links, so design 26c's refusal of `audiobook`/`chapter` URIs at add time doesn't catch them; `GET /shows/{id}/episodes` returns every chapter with `is_playable: false` (third-party clients can't play audiobooks; `restrictions.reason` says why), `spotify::episode` leaves them all out, and the listing fails with the generic `no_items`. Fix (server, XS-S): when a show's page has items and every one is unplayable, keep the first `restrictions.reason` and, for `payment_required`/an audiobook-shaped show, fail the listing with its own code worded "This is a Spotify audiobook - Spotify lets only its own apps play them." (en/nb through the catalogs; `music.rs:685`'s `no_items` stays for a genuinely empty show); the add form may say the same at once when `GET /shows/{id}` marks it (check the show object for an audiobook marker first - `media_type`/`is_externally_hosted`/`type`). The user runs the decisive check meanwhile: whether open.spotify.com plays the audiobook in a browser with the same account (if the web player can, the SDK path is worth a spike; if it says "listen in the app", it is closed). Queued after the Spotify diagnostic log. **The user, 2026-11-01:** "lydbøker fra spotify som jo har show lenker, spiller helt problemfritt på go-librespot; så lurer på om vi kanskje rejecter lydbøker unødvendig". So the account has the rights, and `is_playable: false` may be the Web API's view for our client id, not the player's: librespot speaks Spotify's own protocol, the Web Playback SDK is the web player's engine (open.spotify.com) - not the same path, but a strong hint. **Changed plan: a spike before any refusal sentence** (**MERGED 2026-11-01** (`spotify-diag` 66c57573: 09d3cdcc, 49345044, e0ec88a5 + the QA fix round; server CI 1467/0, launcher 1910/0, music 1236/0; migration 0108; light code QA 0/1/1/3, fixed; ships in the next launcher/music RC and server release): the server switch `SPOTIFY_AUDIOBOOK_SPIKE`, default off, and the specific sentence when every chapter is unplayable) - keep a show's unplayable chapters when the reason is audiobook-shaped (behind a flag), hand one chapter uri to the SDK on the user's phone and see whether it plays; if it does, list them as a show's episodes (progress per chapter as today); if the SDK refuses, the sentence above. Needs the user's real account (fake-spotify can't answer it).

### Assess the eMudhra CA key compromise (CVE-2026-111943)

Evidence: `ca-distrust` 43551aba shipped in server 0.60.0; E6 checked the phones; `server/DEPLOY.md:347`; the launcher's issuer pin is design 76 (merged 2026-11-07).

  - **Assess the eMudhra CA key compromise (CVE-2026-111943, 2026-10-17) against our trust chain** (the user, 2026-10-31: "vi bør vurdere om vi er påvirket av den emudhra sertifikat sikkerhetshullet som nettopp har kommet ut"): eight emSign intermediates' private keys were recoverable from the public certificates; browsers blocked them 10-17..10-20, Mozilla removed the root, distros updated `ca-certificates`; no malicious use confirmed. Verified 2026-10-31 where our trust comes from: the server (reqwest + rustls, `rustls-no-provider`) trusts the OS store of `distroless/static-debian12` / the VM - nothing compiled in; the phones trust Android's store (Conscrypt mainline updates); the launcher does NOT pin the server's certificate (the phone reaches it over Tailscale or an https host); parents' browsers are already blocked. Exposure = a forged leaf + an on-path attacker on a path that still trusts the emSign roots: on the server side the feeds and the Spotify/Storytel API calls (tokens), not the update path (minisign) nor APKs (signer pins); on the phone side the server connection only outside the tailnet. **Checked 2026-10-31: the pinned distroless digest (still the registry's current `nonroot`) carries BOTH roots Debian removed** (emSign ECC Root CA - G3, AAA Certificate Services; the bundle also has emSign G1/C1/C3, which Debian kept). **Fix MERGED 2026-10-31** (`ca-distrust` 43551aba; the dispatched CI run 41240818114 green on amd64 and arm64: "ca bundle: 150 in the base, 2 removed, 148 kept", "TLS ok: api.github.com answered 200 OK"): the Dockerfile's busybox `certs` stage filters the bundle by DER SHA-256 against `server/deploy/distrusted-roots.txt`, fails the build if a listed root survives or fewer than 100 remain, the final image copies the result over `/etc/ssl/certs/ca-certificates.crt`; the image smoke test's `selftest-tls` proves it works; DEPLOY.md + the hardening note say where the trust comes from. Ships in server 0.60.0 (the user's VM pulls the image). Still to do (S): (1) check the server image's `ca-certificates` date (rebuild on a base that dropped the roots; the user's VM too) and whether the emSign roots are still in the phones' stores (`Settings › Security › Trusted credentials`, or `adb shell ls /apex/com.android.conscrypt/cacerts | grep -i emsign` on the emulator); (2) decide whether the launcher should pin the server's SPKI from the provisioning QR (works with Let's Encrypt only with `--reuse-key`; a design question, not a quick fix); (3) one sentence in `server/DEPLOY.md` on CA-store hygiene. Sources: Help Net Security 2026-10-21, Cloudflare's write-up, Mozilla bug 2083449. **State 2026-11-01:** the server image fix shipped in server 0.60.0 (`ca-distrust`); E6 checked the phones - the Android 16 image's Conscrypt still carries emSign ECC Root CA - G3 (Google's Conscrypt mainline update drops it, nothing of ours); left: the decision whether the launcher pins the server's key from the QR (a design question, Let's Encrypt's rotation).

### Calendars without ICSx5/DAVx5 (design 72)

Evidence: Steps 1-3 merged: ICS 2026-10-27, CalDAV read-only 2026-11-01, write-back (72b) 2026-11-02, `e7-integration`.

  - **Calendars without ICSx5/DAVx5 - "paste a link in the PWA and that's it" (design 72)** (the user, 2026-10-27: "er det DAVx5 og ICSx5 gjør vanskelig nok til at det rettferdiggjør to ekstra installerte apper?? Hadde vært mye lettere om det bare var å lime inn lenker i PWA"): proposal, not approved yet - the server fetches ICS feeds (it already fetches RSS and the F-Droid index with the LAN-pinning rules) and CalDAV calendars (read-only first: PROPFIND/REPORT with an app-specific password kept on the server, never on the kid's phone), hands the phone the VEVENTs of a bounded window with RRULE/EXDATE/timezone intact, and Vibb One writes them into a local calendar through the provider (which expands recurrences) - no sync app, no account, no per-app grants; ICSx5/DAVx5 stay possible for those who have them. Steps: (1) ICS links (the school feed case) S-M; (2) CalDAV read-only M; (3) CalDAV write-back of the kid's events later/maybe. **Approved 2026-10-27** (the user: "Og kjør! Ics er pri 1, dav pri 2."): **design 72 written, QA'd (`docs/review/qa-72-design.md` 2/5/12/8) and revised 2026-10-27** (20f7cab6, 313 lines: unfold on the bytes then decode, an orphaned source deleted by trigger, an empty feed keeps the last good set until the 3rd empty fetch or 24 h, provider-gone vs row-refused, the name optional, overrides in the series' zone, a CalDAV 401/403 stops the account; §13 = the implementer's brief items). **Step 1 (ICS) BUILT 2026-10-27** on `calendar-feeds-1` (f6b2fbf3 contract + server, 3537f945 launcher, b1dea016 docs; server 1443 / launcher 1897 tests; fixtures `server/testdata/calendar_feeds/`, `src/windows_zones.txt` CLDR 49, emulator.md §6y) - main (slice 1) merged in 5ec48921 + counted words 2a1cd898 (274 keys per catalog, server 1450 / launcher 1897); code QA `docs/review/qa-calendar-feeds-1-code.md` 1/1/8/8 "mergeable after a short fix round" (H1: `ics::parse` panics on an absurd DURATION/TRIGGER and the one fetch task dies with it; M1: a sibling phone attaches to a `key_lost` row); fix round done 3f7db5d4 (H1: checked sums, a 400-day cap, a panicking parse stored as `unreadable`, each fetch its own task; M1: re-sealed on re-add; L1-L8 as the review asked; server 1456 / launcher 1907); **MERGED to main 2026-10-27**, E6 §6y 22/0, shipped in server 0.60.0 / rc.8; **step 2 CalDAV read-only MERGED 2026-11-01** (see "Done since the last release"); **left: step 3 write-back (**shape answered 2026-11-01**, the user: "man skal kunne legge inn en caldav kalender og velge at det er kalenderen barnet selv skal kunne legge inn dagen i dag hendelser i": the parent marks one CalDAV calendar as the kid's own; the events the kid makes in Dagen today (its LOCAL calendar) are written there by the server - PUT of a VEVENT with the app-specific password the server keeps; design after step 2)**; the nb words sent to the user to read; (migration 0105; 0104 is slice 1's): one `calendar_sources` row per URL (uid = SHA-256 of the URL, so a re-added link keeps its rules), per-phone links, the fetch every 60 min + "Fetch now" through the RSS client's rules (5 MB cap, backoff, last good set kept), own content-line parser `ics.rs` (no crate, no RRULE expansion - the provider expands), Windows zone names via a vendored CLDR table, policy `calendar.feeds: [{id, name, rev}]` + `GET /api/devices/calendar/feeds/{uid}` with ETag (contract `calendar_feeds.json` both dirs), one LOCAL read-only calendar per link on the phone (`FeedPlan` diff by uid + series rev, rev written last), PWA "Kalenderlenker" with status/Fetch now/Remove, capability `calendar_feeds_v1`; step 2 CalDAV read-only (ctag first, password only to the hosts confirmed at Test); step 3 write-back shape only. Owner decisions (defaults): per phone with a shared fetch, 60 min, 60 days back / 400 ahead, the link sealed, plain http to a public host refused. ICS first, CalDAV second; server-side parsing (the user's reflection on sharing more with the server weighed - the server already sees the rest, the password stays off the kid's phone).

### Round S-i18n - the PWA in Norwegian and English

Evidence: Slices 1-5 all merged (the last 2026-11-04), see Done.

  - **Round S-i18n - the PWA in Norwegian and English** (**slice 3a Devices MERGED 2026-11-01** (see Done); **3b Apps MERGED 2026-11-01** (see Done); **3c Locate MERGED 2026-11-02** (see Done); **3d Music MERGED 2026-11-03** (see Done); **3e Settings MERGED 2026-11-03** (see Done; every template is moved); left: slices 4-5; **slice 4 MERGED 2026-11-04** (see Done) (the user: "Kjør den backlog listen du har!"); **slice 5 MERGED 2026-11-04 (see Done) - the round is complete**; device_calendar*/account join MOVED in 3e; `pwa-i18n`, server; the user, 2026-10-26: "Husk at jeg ønsker i18n på ting her, så greit å tenke på i all ny kode. Ønsker det på hele oppsettet vårt" - was "scope not settled" since 2026-10-24): a translation layer (`server/src/i18n.rs`: `Lang` en/nb, one catalog per language, `t(lang, key, params)`), the parent's language on the account (design 62's `admin_users`), `Accept-Language` as the default before a choice, every template's words and every event/push/security-log text as a key plus parameters (design 64's zone events already are; the older prose events migrate), Norwegian first. Design 69 (Dagen) introduces the layer for its own pages; this round moves the rest. **Standing rule from 2026-10-26: no new user-facing string is hardcoded - launcher strings in `values` + `values-nb`, server words through the i18n layer.** L, in five slices (2026-10-26): (1) the layer + the parent's language (an `admin_users` column, a row under Account; `Accept-Language` before a choice) - design 69 introduces the layer; (2) events, pushes and security-log labels stored as key + params and worded in the reader's language (the older prose rows stay prose); (3) the templates tab by tab (Devices, Apps, Locate, Music, Settings), each slice adding a scan test that refuses literal words outside `t(...)` in the moved templates; (4) the words Rust builds - summaries, relative times, dates and clock formats per language, plurals; (5) the seam with the phone (the phone names rules itself - the server stops sending English names where the phone has its own). After L7 - the user, 2026-10-26: "la oss kjøre dagen i dag komplett først" (done 2026-10-27: Dagen built, QA'd, merged, emulator-checked, shipped in rc.7 / 0.59.0). **Design 70 `docs/design/70-pwa-i18n.md` written, QA'd (`docs/review/qa-70-design.md` 3/6/8/5) and revised 2026-10-27** (60f2aa17: `/sw.js` gets no words, `phone_off` params `reason`/`battery`/`late` with older rows = a non-empty title, the phone-line budget keeps `device {id}` in `detail`, the PWA names the active rule by `active_rule_id`, slice 1 gives every template struct `lang` + `Words`, `i18n::error`, the language stored once on the first signed-in request; decisions D1-D5 stand as defaults until the user says otherwise). **Slice 1 MERGED 2026-10-27** (07099709 built, code QA `docs/review/qa-s-i18n-1-code.md` 0/0/5/7, the fix round b12034ef: unmoved pages keep `lang="en"`, an unreadable stored language falls back to the header in memory, `t` warns once per missing key, the nb words the user chose; merged 8141b65b, 1426 server tests; ships with the next server tag). **Slice 2 MERGED 2026-10-31** (`pwa-i18n-2` 02a73f6b; ships in server 0.60.0) on `pwa-i18n-2` (cbee7056, four commits; 1458 server tests, 193 jstest; 671 keys per catalog, 396 added; migration 0107; the nb list sent to the user as a file) - main merged ea3b9b7f (1462 tests, 684 keys per catalog); code QA `docs/review/qa-s-i18n-2-code.md` 0/1/5/7 "sound - merge after one fix" (M1: the security log shows a row's words OR its facts, never both - the route after a CSRF refusal, the phone of a per-phone Screen Time save, lost); fix round done 67f51288 (M1: `Detail::shown` = words · facts on the row's detail line; L1-L5; nb: "(fra {peer})", "Sikkermodus eller en avbrutt start", "Egne regler på." / "Ramme {budget}."); branch CI run 41249661563 red (25 tests: the id facts `device {id}`/`entry {id}` shown raw after the words) → fixed 02a73f6b: a bare id fact is hidden beside words that name the phone or entry, and the phone's own lines that don't name it lead with the reader's phone word ("phone 1: …", nb "telefon 1: …"; keys `security.detail.phone`/`on_phone`, 686 per catalog); run 41250961086 green, 1463 passed. English the later slices must take (from the QA): `parent_events::battery_line` and `parent_pin::where_`/`lockout_text` and `lifts::minutes_text` → slice 4; push.rs "The push service refused it", admin.rs "Time unknown"/"a while", `Kind::switch` labels → 3e; the music pages' `%b` dates and `settings_hub::day_time_label` → 3d/3e (§4: the times in both languages, the 8 event kinds as key + params, pushes in the subscriber's language, the security log's `params`; migration 0107 - 0105 ICS, 0106 colours; the session banner "Wrong codes before this sign-in" belongs to 3a). Was: branch `pwa-i18n-1` (the catalog rules, `t_count`, the key-itself fallback, `Words` on ~65 structs, migration 0104 `admin_users.lang`, the extractor order `?lang=` → account → Accept-Language → En, the Account row + `POST /account/language`, `account.html` moved).

### PWA small things left from the old list (2), (3)

Evidence: (2) `small-d` 8c58b566, (3) `small-c` 2969d98b.

  - **PWA small things left from the old list** **MERGED 2026-11-01** (`small-c` 5dea62b6; server CI 1470/0, jstest 204/0, launcher 1909/0; light code QA 0/1/3/2 fixed; ships in the next server release and launcher RC) (item (3) only) - (moved up 2026-11-01): (2) "add an existing family contact" - **MERGED 2026-11-03** (`small-d`, see Done); (3) a Cancel in the `<details>` confirms (Remove Contact… in `device_calls.html` has none). Server, XS each.

### Passkeys for parents, passkey-only accounts, a Face ID lock (design 74)

Evidence: Steps 1-3 merged 2026-11-02 (design 74 fully built), see Done.

  - **Passkeys for parents, passkey-only accounts, a Face ID lock on opening** (the user, 2026-11-01: "La oss legge inn at man kan selv velge å legge inn en passkey inne på accounts og parents? Der kan man vel også det velge å kun bruke passkey for kontoen din og ikke tillate passord innlogging? Eller er det farlig? Og også da velge om man skal velge og kreve biometric og passkey etter x min for pwa?"; after the coordinator's assessment of passkeys / Face ID lock / OIDC - OIDC not asked for): add passkeys under Settings › Account (and see them per parent), optional "passkey only" (no password + TOTP sign-in) with a safe recovery, optional "lock with Face ID after N min" for the PWA. **APPROVED 2026-11-01; APPROVED 2026-11-02** (all eight questions answered, design 74 §8; mockup v2 with the whole Account page and the passkey-only sign-in approved: "Mockup ser bra ut, du kan kjøre på" - https://claude.ai/artifact/MimkFPaRd2fq7cW4ZyQH2g). **Step 1 MERGED 2026-11-02** (see Done). **Step 2 MERGED 2026-11-02** (see Done). **Step 3 MERGED 2026-11-02** (see Done) - design 74 is fully built. Steps 2 (Kun passkey + the passkey-only sign-in page; **before it**: the review nits from step 1's revision check (an orphaned `passkey_started` row when a challenge expires, `passkey.js` reading any HTML answer as signed out, a 5-min-old autofill choice counting) and the sign-in pool's per-client cap and the ban count per IPv6 /56 or /48, or a short grace for a challenge already handed to its session - revision check A R1, so a flood of fresh /64s can't keep a passkey-only parent out) and 3 (the lock) queued after it: design note 74 written (`passkeys-design` 89de90e7: own verifier on `ring`, RP ID = the request host or `PASSKEY_RP_ID`, Kun passkey needs 1 passkey with a warning that two are recommended (the user, 2026-11-02), recovery by another parent's Reset or `reset-sign-in`, the lock Av/1/5/15/60 min per device with no password fallback, steps 1 passkeys M / 2 Kun passkey S-M / 3 the lock M), design QA 3/5/9, all taken in af50a317 (origin = this request's origin, resets delete passkeys from step 1, only user activity counts for the lock, a server-side challenge store, one readiness predicate, the lock check in `admin_or_why`); note 74 merged to main; eight questions to the user (the six (lock times incl. "med en gang", per device vs per account, Kun passkey with 1 synced passkey, the nb word, which hostnames, logging unlocks, plus: the child knowing the parent's phone code, other services on a shared `PASSKEY_RP_ID` domain); then a mockup; server only (WebAuthn), M-L.

### Settled: the device pages' logs

Evidence: Settled 2026-11-03; "Nylig bruk" is gone from the lock page.

- **Settled 2026-11-03: the device pages' logs** (the user: "sjekk om det er flere sub menyer som har sine egne unødvendige logger"): surveyed all Device › pages - Activity, Filter's Blocked Activity, the Locations list, About's crash reports and Advanced's "Removed since last report" stay (each the only place for its data or an action); the lock page's "Nylig bruk" goes (in the design 75 round); Locate's "Requests" (a duplicate of Activity, with Cancel) stays for now (the user: "La den være inntil videre").

### PWA i18n (the long-list item)

Evidence: Complete: the S-i18n round (slices 1-5) above.

- **PWA i18n** (the user, 2026-10-24, on design 64's push wording: "burde gå for i18n så man enkelt kan endre og legge
  til flere på sikt ... mer som en generell long list ting"): the PWA is English-only; a translation layer (strings in
  one table per language, the parent's language on the account, Norwegian first) would also need every event text to
  be a key plus parameters - design 64 step 1 already stores zone events that way; the older events are prose. Long
  list; decide the scope when a second language is wanted.

### Design 50: the player and the library

Evidence: 9a7017a0. The rewind-after-pause idea it mentions is declined (PLAN's Dropped list).

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

### Design 49: shuffle and repeat in the player's control row

Evidence: 9a7017a0.

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

### Design 51: "show the text" per app on the lock's glance page

Evidence: 5b382da5, migration 0083. The §3 emulator check stays in PLAN's waiting list.

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

### Design 48: one Activity log on the phone's summary

Evidence: d2b6563b.

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

### Design 46: a context-mode playlist shows what is coming

Evidence: §1.4, the play arrow: 9a7017a0. Kept in PLAN: the §1.1-§1.3 queue view (Asked for, longlist), 46b the reader account (waiting, the user's choice), the librespot-style session not pursued (Dropped).

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

### Investigate: does one bad episode picture URL hold a listing's other pictures?

Evidence: Done 2026-10-26, round S1, f20cc7f5.

- **Investigate: does one bad episode picture URL hold a listing's other pictures?** **done 2026-10-26** (round S1, f20cc7f5). (from qa-r5-design.md r5-3,
  2026-10-20: the proposed `Why::Art` failures rule changed nothing, because with `failures > 0` the regular time already
  is the backoff). Find out whether a single picture's failure raises the listing's `failures` at all, or whether the
  hold is `art_due`'s one-day retry / `ART_MAX_FAILS`; then decide the rule with a test that reproduces the case
  (`fake-audio.py --feed` with one 404 picture). Next music/server round.

### Music app's episode list: thumbnails and a kid-side order flip (design 39)

Evidence: 937bf7eb (merged 2026-10-19). The device checks stay in PLAN's waiting list.

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

### REMOVED 2026-10-20: the report's battery_exempt (design r7 section 2.5)

Evidence: Removed 2026-10-20 (PLAN's Dropped list has the one-line form).

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

### Wake the screen for a notification - a global switch

Evidence: e1569747, migration 0084.

- **Wake the screen for a notification - a global switch** (the user, 2026-10-20, on the lock screen never waking for a
  notification, design 32b's exclusion: "la det være en global bryter i pwa? Under feks lock screen kategorien i phone
  settings"): one per-device switch in the PWA under Device › Lock screen, off by default (today's behaviour), a policy
  field `lock_wake_on_notification` (both directories, `policy_json_keys_snapshot` + `PolicyResponseCompatTest` in one
  commit); when on, the launcher wakes the screen through the existing VoIP wake path (`PinLockRuntime.show(wake)` with a `WakeKind`; the lock activity
  itself never gets turn-screen-on, design 32 §7; never a dismiss), off again after 15 s untouched, not in a pocket
  (proximity), for a missed call and for a glance app's new row (alerting, not suspended, passes DND; design 51's
  list), never for the music tile. Launcher round 7 (the server half with it).

### Any allowed app may ring over the lock

Evidence: 5248fa4e (`l7-a`).

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

### Launcher round 7 section 4 - small things from the Fossify Launcher comparison

Evidence: 5248fa4e (`l7-a`). The device checks stay in PLAN's waiting list.

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

### Bluetooth headphones: connect a paired device (design 52)

Evidence: 8c6ed318 + adbb91d6. The Jelly Star checks stay in PLAN's waiting list.

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

### Design 53: Sonos grouping + precision

Evidence: 53-0 through 53-d: 818ad11e, 8e0fcd77, 22bff6a7. The speaker checks (§5) stay in PLAN's waiting list.

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
  **Measured 2026-10-21** (`scripts/sonos-play-test.py` on the user's Pi, speaker Emma, Hallo Bablo, both `--didl
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
  coordinator stays (`RINCON_...:100`, name "Emma + 1"). So the phone keeps one socket, to the coordinator of the
  room it plays to, and follows a MOVED to the new coordinator's url; the grouping verbs can stay UPnP (`x-rincon:`,
  `BecomeCoordinatorOfStandaloneGroup`, as soco) with WebSocket only for events. **The key, measured the same day**
  (`sonos-ws-probe.py --key`, the user's Pi, Emma): no `X-Sonos-Api-Key` header -> the upgrade is refused (HTTP
  400); a random UUID -> accepted, the full handshake runs. The local endpoint needs the header but doesn't check its
  value - the RFC 4122 example UUID is no secret and no borrowed key (it is in `jjlawren/sonos-websocket`'s first
  commit, 2023-04-09, and in Music Assistant's `aiosonos`; SmartThings' official driver sends its own Sonos-issued key
  to the same port 1443). **The certificate, checked the same day** (the user's Pi, `openssl s_client` to Emma): the
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

### PWA small things (0)-(3)

Evidence: (0) `notifications.js`, (1) d99b3bdc, (2) `small-d`, (3) `small-c`.

- **PWA small things**: (0) Notifications › Getting Notifications: after Send a Test the device rows poll for ~30 s
  (as Install Now's row does) so "Shown just now" appears without a reload (the user, 2026-10-19: "måtte refreshe
  siden"); only the rows re-render, never a scroll jump. (1) ~~a switch in the off state loses its knob~~ - fixed 2026-10-19 (d99b3bdc: the knob was
  painted under the positioned switch; `position: relative` on it); the toggle "jump" on Device › Apps fixed the same
  day (scroll-name: a row that moved groups keeps the exact position; `window.name` carries the record too); (2) "add an existing family contact" to a phone
  (the server already keeps one address book per number); (3) every `<details>` confirm
  (Remove Contact… and the rest) gets a clear Cancel that closes it.

### `PHONE_SERVER_URL` in `.env`

Evidence: Done 2026-10-26, round S1, f20cc7f5.

- **`PHONE_SERVER_URL` in `.env`** - **done 2026-10-26** (round S1, f20cc7f5). Was: the phones' server address as an env default; the DB value
  (Phone Setup) wins when set, the page says "from .env", a startup warning when it isn't https.

### A configurable emergency menu (design 73)

Evidence: Design 73: 3ac24e2d, cad07f18 (shipped in server 0.62.0 / launcher rc.2).

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

### Episode pictures on demand, not by the sweep's clock

Evidence: Migration 0082 + round S1.

- **Episode pictures on demand, not by the sweep's clock** (the user, 2026-10-19, Snipp Snapp Snute stuck without
  pictures on rc.4 until "Check now": "tlf må be server om filen med en gang"): today an item has a picture hash only
  once the server's sweep fetched it, so the phone can't ask for what it doesn't know of, and an entry checked under
  the old caps waits for its interval (6 h) - `art_more` is 0 for it after the 0.29.0 upgrade. A new entry's first
  check already fetches every picture (continuing at once past the 250-request/10-min budget), and a new episode's
  picture comes in the check that finds the episode, so no phone-to-server "want" is needed (the user, 2026-10-19).
  Two fixes: (1) migration 0082: `art_more = 1` for every listing with items lacking a picture, so the first sweep
  after an upgrade fetches them; (2) soften `failures == 0` in the `Why::Art` rule: one bad episode URL shouldn't
  hold every picture for 6 h.

### A per-phone avatar as the push notification's icon

Evidence: 78640a5e.

- **A per-phone avatar as the push notification's icon** (the user, 2026-10-19: the phone's avatar with the round
  Vibb mark as a small tag bottom-right): the worker's `icon` (and the declarative payload's) can be any URL of ours,
  composited on the server from the phone's picture. Chrome/Edge/Brave/Samsung/Firefox show it; Safari (iPhone, Mac)
  shows the web app's own icon and is believed to ignore `icon` - step 1 is one test on the iPhone with a distinct
  icon before anything is built. Goes with the item below.

### A photo of the child for each phone in the PWA

Evidence: 78640a5e, migration 0100.

- **A photo of the child for each phone in the PWA** (the user, 2026-10-16, low priority): the Devices list,
  the phone's summary header and the Locate switcher show a coloured disc with the first letter; let the
  parent upload a photo per phone instead (`photos::process` with `Shape::Square`, like contact photos - EXIF
  and GPS gone, ≤ 512 px - its own `photos::Store` and backups), the disc as before without one. Server only.

## Moved out of PLAN's queue 2026-11-07 (full text of entries shortened in PLAN)

PLAN's waiting list and Asked-for section keep these as short lines with a pointer; the original text is here, verbatim.

### The idea round of 2026-11-03 (picks owed) and the older unbuilt ideas

  - **Idea round: more for the kid - DONE 2026-11-03, waiting for the user's picks** (the user: "Hva mer er nyttig for en tiåring uten å være en skjermtid-maximizer?"; `docs/review/ideas-2026-11-03.md`, artifact NaV92Z9s2a9owuhatfkE3F: 15 ideas, top 5 Kveldskortet, Huskelista, Hurtigbeskjeder, Neste buss, Været som klær; nothing approved to build. Still unbuilt from `ideas-2026-10-26.md`: A1 Mine regler, A2 Be om, A3 bedtime warning, D2 Er du fremme?, D3 Følg meg hjem, D4 Si fra).

### Q5: fingerprint with the screen on

    - **Fingerprint with the screen on, Jelly Star check** (the user, 2026-10-25, emulator: with the screen on the fingerprint unlocks only once SystemUI's pad is up; from screen-off it unlocks at once): expected in the hybrid - the glance occludes the keyguard, and Android listens for fingerprint only while the keyguard is showing and not occluded (design 59); no API lets us dismiss a secure keyguard after our own biometric check. Check on the Jelly Star (side-mounted sensor; some OEMs keep listening while occluded); if it does not work there with the screen on, one line in Kid Settings' help ("press the sensor from a dark screen, or swipe up first"), nothing more. Not a bug.

### Design 32b / 71: the screen-on hand-over in android mode

    - **Design 32b, now the screen-on hand-over in android mode** (the user, 2026-10-26, `jellystar2` screen recording of three power-button cycles, read frame by frame: "Er en slags resizing tidvis av bakgrunnen ved power button cycles her; er dette noe vi kan addressere?"): at every screen-on the stock keyguard is on screen for 1-4 frames (40-170 ms) before the glance - once with SystemUI's own clock, once as the bare wallpaper - and in one cycle the glance's STALE page (the old clock, 12:04) showed first for 4 frames; in those stock frames the system wallpaper is at SystemUI's wake-up zoom-out (1.0), while the glance's own opaque ground (design 25's copy) is at the wallpaper's resting scale (`config_wallpaperMaxScale` 1.10) - so the ground steps 10 % when the glance covers them (the same step Home solved 2026-10-25 by keeping its copy). Decision 4's premise ("own mode only") is wrong: the stale frame exists in android mode too, so 32b stays. Fix direction (a design note first, emulator-measured, `screenrecord` + `vidsum.py`): the glance's window must be the first frame at screen-on (why does our window drop out for a frame after it was there with stale content - the keyguard's re-occlude transition? `setTurnScreenOn`/`showWhenLocked` timing?), the page's content blanked at the lock-up so a stale frame can't show (design 32's ground rule), and if a stock frame remains, the glance's ground may start at the wake-up's zoom-out and settle with SystemUI's own zoom so no step is visible. Launcher, S-M; after L2. **Design 71 written 2026-10-27** (e62854b9, `docs/design/71-screen-on-handover.md`; in design QA): the architect re-measured the video and **corrects the reading above** - our copies draw at 1.0 (no code reads `config_wallpaperMaxScale`), the keyguard's frames show our own baked lock wallpaper at Android's resting 1.10, so the 10 % step is the other way round and fixed, not an animation; cycle 3's "stale page" is the lock task's snapshot from the last unlock (no page content), not a stale draw. Recommended: every full-screen wallpaper copy (glance/keypad ground, Home, phone book, kid Settings) drawn at `config_wallpaperMaxScale` (`WallpaperZoom.kt`, a `zoom` on `GroundDrawable`), `setRecentsScreenshotEnabled(false)` on `PinLockActivity` as Home has; our window can't be the first frame without `turnScreenOn` (design 32 refused it), so 1-4 keyguard frames stay but look identical; emulator §6x before/after, done = 0 stale frames and zoom 1.000 ± 0.005 on every lit frame. **Design QA'd (`docs/review/qa-71-design.md` 1/3/4/2) and revised 2026-10-27 (a47d8dc7)**: the measurement goes through the emulator console's recording (`screenrecord` blacks our secure window) with a tracked `scripts/zoom-match.py --selftest`; the 12:04 frame is most likely the emulator's host composer, not our lock (`setRecentsScreenshotEnabled(false)` kept as parity with Home only); `jellystar2` is the desktop AVD, so **the step is BLOCKED on the user's pre-check on the physical Jelly Star**: a slow-motion clip of three power cycles read with `zoom-match.py` - 1.10 = build, 1.00 = close the item as emulator-only. Recommended D1-b (android mode only): the lock wallpaper handed to Android baked pre-shrunk by 1/maxScale (`WallpaperApplier`, one `canvas.scale`, a `lockzoom=` key part) so every screen keeps today's framing; D1-a (every copy at 1.10) the alternative; D2 accept the remaining keyguard frames (design 32 §7.1). Waits for the user's clip and D1 pick.

### A Google nag in the shade on `jellystar2`

  - **A Google nag in the shade on `jellystar2`** (the user, 2026-10-27, screenshot: "Tjenester på flere enheter · Nye funksjoner på flere enheter" with a Settings link, plus Android's own "Oppdatert/Slettet/Installert av administratoren" group): the admin-install group is `android`'s and stays by design (launcher/CLAUDE.md "Notification rule"); the cross-device one is GMS - whether the rule should have cancelled it depends on the server switch `notification_auto_cancel` (default OFF until B5) and the channel. Owed from the user: `adb shell dumpsys notification --noredact` (or without the flag) while it is still in the shade, so we see the package, channel and flags (memory: google-nag-capture). No work until then.

### Zones update only every 30 min on the emulator (asked 2026-11-07)

- **Asked 2026-11-07: zones update only every 30 min on the emulator; the PWA's location minutes before the push** (the user: "jeg trodde konseptet var at android sin egen sone-sjekking skulle med liten insats holde hvorvidt man er i en sone eller ikke oppdatert med mye høyere frekvens ... trodde de 30 min bare var en slags backstop"; "pwa location blir oppdatert flere minutter før en push melding faktisk går ut"). Answered from design 64 §4 and §9: AOSP's proximity alert (no Play services) polls its own balanced fix only about every 30 min and otherwise judges the fixes other apps cause; the fast path on a phone is our significant-motion trigger (a hardware sensor, near free), whose checks come every 3-30 min by the distance to the boundary - the emulator has no such sensor (§9 item 8) and falls back to the heartbeat, so it can't show the fast path. The push waits for the server's two-fix rule: the crossing's first fix shows on the map at once, the confirming GPS fix comes 2 min later (`CONFIRM_DELAY_MS`), then the push. Owed: the Jelly Star's sensor (`dumpsys sensorservice`, "Significant Motion") and its walk (design 64 §9). Offered, not approved: the confirming fix after ~60 s instead of 2 min (`CONFIRM_GAP` is 60 s), and the PWA marking an unconfirmed crossing.

## Moved out of PLAN's queue 2026-11-07 (index lines and structure)

The old queue's bare index lines (each is carried by a full entry in PLAN or the archive above) and its sub-headers, as they stood.

### Approved, not built - the queue (kept current; checked against the code 2026-11-02)
Rounds and waves: `docs/plan/2026-10-25-queue-rounds.md` (2026-10-25). Combined lines are split at their own
separators, one item per bullet, so each sits in its round; the words are unchanged.
- **Cleanup**
- **Launcher**
  - **Emulator session E2 - screen sizes** (the coordinator's, after L1 and M1):
  - **Waits on the user / the Jelly Star / phase D** (no round yet):
    - Phase D (own lock removed)
    - RC3-4 (release-build setup timing)
    - Immich wallpapers
    - design 60 (hardware-bound token)
- **Music app**
- **Server / PWA**
  - **Scope not settled / waits on the user** (no round yet):
    - Home Wi-Fi as a trigger
    - Wi-Fi in Phone Setup
    - notifications phase 2 (ntfy/Matrix)
    - Zone series numbers after the user's Jelly Star day
    - developer-verification registration (nothing in the repo)
### Asked for, scope not settled yet

## Moved out of PLAN 2026-11-16

Done and released by `server-v0.81.0` / `launcher-v0.40.0-rc.3` / `music-v0.6.0-rc.6` (2026-11-16). Their owed device checks moved to PLAN's "Waiting on the user or a device check".

### The queue's old head and next-tags lines

Last tags: server-v0.77.0 and launcher-v0.39.0-rc.8 (2026-11-12, both on 598d2da8: design 90 the catalog update holds and the per-app wait, Locate's age box gone and a taller map, Kalender's row; rc.8 the three upstream launcher fixes; released 21:25 UTC), then server-v0.76.1 (2026-11-12, on 13076ffe: the real logo always in the lists, the iOS top pocket fix; released after the user's approval), then server-v0.76.0 and launcher-v0.39.0-rc.7 (2026-11-12, both on 218bae5e: design 87 the apps' real logos, design 88 Locate's rows, design 86 the QR preflight, the band mark, Locate's lower map; released 15:43 UTC), then server-v0.75.1 (2026-11-12, on 91544f22: Locate's one look for one phone; released after the user's approval), then server-v0.75.0 (2026-11-12, on 8b640baf: design 85 both steps with the full-screen map, phone-words, the catalog app's versionCode fix; released 10:16 UTC), then launcher-v0.39.0-rc.6 (2026-11-12, on 40e3526a: design 78 step 2, the kid's light look for Meldinger, the phone book and the lists; released 07:11 UTC as a prerelease), then server-v0.74.1 (2026-11-11, on 808a3d31: the night band's four points from the user's iPhone; released 21:18 UTC), then launcher-v0.39.0-rc.5 and server-v0.74.0 (2026-11-11, both on 3ffc28ab: the kiosk-root fix, design 78 step 1, design 83 both halves, `mmsc-words` and design 84 the night band; rc.5 released 14:01 UTC, the server 17:12 UTC), then launcher-v0.39.0-rc.4 (2026-11-10, on d84d2e05: the bubble's time, the buzz for every message, the pause-hang fix, the failed picture's retry and the compose bar; released 20:50 UTC), server-v0.73.0 (2026-11-10, on b548fa47: design 82's server side; released 17:56 UTC), server-v0.72.1 (2026-11-10, on a7b7544b: the iOS 27 PWA fix and Devices' night band; released 12:05 UTC) and launcher-v0.39.0-rc.3 (2026-11-10, on 28ccd706: design 81's reactions, the thread follow, the phone book's night ground, the 40 ms reaction buzz; RELEASED 11:35 UTC as a prerelease); before it server-v0.72.0 + launcher-v0.39.0-rc.2 + music-v0.6.0-rc.4 (2026-11-10, on edeb78a6, all three released 2026-11-10 - the server's signing job hung 51 minutes in `apt-get update` before anything was signed, the user cancelled the run, its cancelled jobs were run again and went through in two minutes; `server-release-sign` got `timeout-minutes: 10` on the user's word 2026-11-10, "Du kan legge inn en time limit på signeringsjobben ja": design 79 the phone book as a list with the kid's contact request, design 80 Calls & Contacts made simpler with "Gruppemeldinger" and the learned own number, the music app's greyed play and "Kan ikke spilles nå"; SMS still "Ikke styrt" on the kid's phone until step 0), server-v0.71.0 + launcher-v0.39.0-rc.1 (2026-11-09, on a11cc107: design 77 steps 1, 2, 3a and 4 - the SMS engine, the kid's message screens, the PWA side; SMS stays "Ikke styrt" on the kid's phone until 3b and step 0; also the contact-sheet edge fix and the glance calendar lines; 0.38.0's RCs were used up, so a new minor), server-v0.70.0 + music-v0.6.0-rc.3 (2026-11-09, on 76edb9ba: Spotify audiobooks for real, the SMS engine's server side behind `sms_engine_v1`), server-v0.69.2 (2026-11-09, audiobook chapters under the spike), server-v0.69.1 (2026-11-09, the Spotify show diagnostic) after server-v0.69.0 + launcher-v0.38.0-rc.8 (2026-11-08, on d80fac83; music unchanged at music-v0.6.0-rc.2); every entry in "Done since the last release" up to the MET floor ships in those, the Spotify diagnostic in 0.69.1.

**Next tags: none approved.** Before that: RELEASED 2026-11-16 on 312345d9 (server run 43812248317, launcher run 43812253265, versionCode 400003, music run 43812253322, versionCode 60006; the user signed `release` for all three; the go is used up). History of that go: APPROVED 2026-11-16 (the user: "Push tags"): `server-v0.81.0` (designs 101, 102 server, 103, 104 and design 100's server half), `launcher-v0.40.0-rc.3` (designs 100 and 102), `music-v0.6.0-rc.6` (the new icon), on the "Server 0.81.0" commit - main green on all three. Before that: RELEASED 2026-11-15 on 30be47df (server run 43734575057, launcher run 43734578939, versionCode 400002, music run 43734579157, versionCode 60005; the user signed `release` for all three; the go is used up). History of that go: APPROVED 2026-11-15 (the user: "Ja vi kjører uten fcm! Push tags, men også music eller?"): `server-v0.80.0` (version bump at tag), `launcher-v0.40.0-rc.2` and `music-v0.6.0-rc.5` on the "Server 0.80.0" commit - since server-v0.79.0/launcher-v0.40.0-rc.1: design 96 glance-generic, the screen-size fixes S1-S9 and their QA, player-scale, design 97 Mobilnett r4, pwa-back, roaming-line, design 98 Flymodus with the shade fix and the 112 change, design 99 Snarveier; music since rc.4: the screen-size fixes and the player cover scaled with the width. Previous: `server-v0.79.0` and `launcher-v0.40.0-rc.1` RELEASED 2026-11-14 on c279e5d0 (server run 43574145786, launcher run 43574148250, versionCode 400001; the user approved `release` for both; the go is used up). The stable launcher for the QR's `releases/latest` (still 0.34.0): proposed to skip 0.39.0 and tag `launcher-v0.40.0` stable on the user's word that rc.1 runs well. History of that go: proposed 2026-11-14: `server-v0.79.0` (power-quiet, traffic 1+2, Mobilnett server, activity row; version bump at tag) and `launcher-v0.40.0-rc.1` (design 93 + small fixes launcher, traffic 1+2, Mobilnett, stream-reconnect) together, server first; the user: wait until the DoT session resumption is ready (it was, with no code, 2026-11-14), then propose again - proposed again. `server-v0.78.0` RELEASED 2026-11-13 (run 43422662652; design 93's server part, the four small fixes; the go is used up). `power-quiet` is merged for the next server tag. `launcher-v0.39.0` (stable) waits for the user's word that the RC runs well (rc.8's 598d2da8 is the candidate); the next RC carries design 93's launcher part (and design 94/95 once built).

### From "Approved, not built - the queue"

- **DoT session resumption (design 95's D7, slimmed) - APPROVED 2026-11-14 (the user: "Jeg støtter gjenopptakelsen"); DONE 2026-11-14 WITH NO CODE: measured on jelly36 (`dot-resume` 8418ce2a, docs only), Conscrypt already resumes - Cloudflare every connection (~5.2 KB full -> ~2.2 KB resumed), Quad9 every other (it sends no new ticket on a resumed one), Google never (its server declines); the launcher's own 14 connections in 35 min: 3 full at the VPN's start, 11 resumed; design 95's ~1.1 KB/query was already the resumed cost. The rule to keep it (default factory, the peer host passed to `createSocket`) is in docs/notes/launcher/dns-and-vpn.md; open for the Jelly Star: an idle-hours data check**: the DNS filter's DoT connections to a custom upstream resume the last TLS session, so a new connection skips the certificate chain (the emulator's ~1 KB per query is mostly handshakes: 10 s idle, evicted on a network change). Measure first whether Android's Conscrypt already resumes (TLS 1.2 and 1.3); build only what is missing. Launcher only, no server, no PWA. Not taken: a longer idle (a server-side close after the radio slept costs battery), an answer cache (Android's resolver caches by TTL already). Zero in Bare Vibb anyway (DNS answered locally).
- **Glance clock: a general copy of Android's lock-screen clock on every phone - APPROVED 2026-11-14 (the user: "Do it"; S10 of the screen-size matrix), BUILT (design 96, `glance-generic` 8ab79dba..b9c47e86: AOSP's small clock 86 dp, 18 dp under the 40 dp keyguard bar, 28 dp start; reproduces the measured profiles within 0.1 dp; measured profiles from Android 16, an 18 dp lower clock on 14/15; emulator 6.7"/6.1" within 1-2 px of Android's own; CI 43608374471 green); code QA 501fb6ce 0 H / 0 M / 6 L (the real Jelly Star runs Android 16 per Unihertz and keeps its profile - `JELLY_STAR_CLOCK` is AOSP-emulator numbers, never confirmed on the phone); fix round 4028202f + 1ed82365 (L1 the date's shift from its real scaled size, 0.93 of the growth, measured at 1.3/1.8/2.0; L2 the general date start 32 dp; L3 Android 14 checked on an API 34 AVD - same as 15; L4 the tablet cutoff on the smallest width, landscape centred; L5 RTL and non-Latin digits centred; L6 docs); CI 43612569670 green; MERGED 2026-11-14; in no release yet - the next launcher RC. Still owed: a frame of Android's keyguard and ours from the real Jelly Star to confirm `JELLY_STAR_CLOCK`**: today only phones with a measured profile (Jelly Star, two emulators; exact model + density + width, `glanceClockProfile` in `lock/LockGlance.kt`) get the copy of Android's small clock; every other phone - and a Jelly Star with another display size - falls back to the centred 56 sp clock. Build a general profile computed from AOSP's own keyguard clock dimensions for the phone's density and width, measured profiles kept where they exist; optionally the model of a phone without a measured profile in the status report.
- **S9 back to the floating "+" and the music player's cover scaled up - APPROVED 2026-11-14 (the user: "Do it"), MERGED 2026-11-14 (`player-scale` cefdfcd0 + 25aea5c0 + docs; the strip code removed, 96 dp under the last card while the "+" shows; the cover `playerArtMax` = width minus the carousel's gaps and peeks, 224..320 dp - 291 dp on a 411 dp phone, the group centred; Jelly Star and narrow unchanged; Launcher 43602768397 / Music 43602770778 green; light review by the coordinator; in no release yet - the next launcher RC and music tag)**: `ADD_STRIP = false` (the user agreed the floating "+" looks best); the player's cover grows with the width (capped), the title and controls follow right under it, the group centred in the height (S10: ~150 dp gap on a 6.7").
- **PWA: a button that posts and returns to the same page leaves an extra history entry - DONE: REPORTED 2026-11-14, BUILT (`pwa-back` 8980243b: every staying POST form is sent by `scroll-restore.js` with `X-Handy-Stay`, the server answers 204 + `X-Handy-Location`, the page replaces its entry and reloads; password, passkey, file and target forms stay native), CODE QA DONE b5ef2fd2 (1 H - a re-post after a network error or 5xx could act twice; 1 M - no busy state, a second tap sends again; 5 L), ALL FIXED 6eee1806 (never a re-post after a network error or 5xx - a GET reload; a busy button; saves land when the last settles); MERGED 2026-11-14 to main, in no release yet (ships in the next server tag)** (the user: Apps -> Vibb One -> "Check now", wait, swipe back -> the same app page again): the plain form POST + 303 back to the page pushes a second entry. Fix every such form in the PWA so the return replaces the entry (anchors kept, never a jump to the top).
- **Snarveier: the kid's quick panel as an ongoing notification in the shade - DONE 2026-11-15, MERGED (design 99 revision 3: one row of four 40 dp icons without names, the same folded and unfolded, the Vibb mark as its icon; code QA's M1 and L1-L5 fixed, plus "Blokker flymodus" now dims Flymodus at once; launcher unit tests 2621/0; unreleased - next server + launcher RC; Jelly Star checks owed) - was APPROVED 2026-11-14, DESIGN WRITTEN 2026-11-15 on `design-99` 631e74067 (design 99; measured on jelly36: Android always unfolds the top notification when the shade opens and forgets a fold on close; Android 16 bundles two silent notifications of one app - only a colorized foreground-service notification stays apart, and it is always on top; folded row 48 dp/180 dp wide fits six only as 28 dp discs; the 3 x 2 grid with names 195 dp; so "narrow by default, grid on a tap" is NOT possible - D1 to the user: a = grid by default, fold on tap until the shade closes (recommended), b = the row only; items Lommelykt, Flymodus, Lyd, Wi-Fi, Bluetooth, Mobildata, Lysstyrke, Mer tid, Musikk; bit 128, migration 0148, `shortcuts.json`; D1-D11 incl. D11 the long-press menu can turn off any Vibb channel - a separate follow-up; launcher M, server S), DESIGN QA DONE 195511974 (1 H - design 98's route: "status bar disabled" is only recorded under lock task, so the shade stays tappable while Settings is on the list -> FIXED in the merged code (`d98-shade`); the revision must start Flymodus from the shade only after a Vibb window has focus (a finger in the shade can stop the collapse); 2 M - one fail-closed tap check (Musikk through the lock tile's gate, a cold process refuses), and D1 c IS possible: post the row with a seventh "⋯" cell that reposts with the grid, back to the row at screen off - offered to the user; 11 L), the user 2026-11-15: "Men fire snarveier holder vel gjerne?" -> proposed back: FOUR items in ONE row - folded the row of four (~36 dp discs fit the 180 dp row), unfolded the same row with names under (220 dp / 4 = 55 dp a cell, the names' fit to be measured), no grid, no "⋯"; D1 then moot; ANSWERED 2026-11-15 (the user: "Ja" to four in one row and the rest as recommended: bit 128 on every phone by migration, Mer tid and Musikk as items, Lysstyrke in three steps, no På/Av line, the parent sees the picks, hiding is the kid's choice, the same panel under a rule, a dimmed tap does nothing; default four Lommelykt, Lyd, Flymodus, Wi-Fi) - REVISION 2 dfa310969 + BUILT on `design-99` c13ddfca1 (contract `shortcuts.json`, migration 0148, server + launcher, Home's torch tile removed; launcher unit tests 2621/0; jelly36 run §6an: four in a row, unfolded 55 dp cells with names fit at font scale 1.0 in nb and en, cut at 1.3; every device item tapped under the kiosk and under a rule; Flymodus from the shade waits for Vibb's window focus, then the shade-blocked route; hidden at the keyguard; two bugs fixed; FOUND: on a phone where Vibb holds the dialer/SMS roles Android locks all its channels, so the kid can't hide the panel - the hide path applies only to phones without the roles), CODE QA DONE 0ea961151 (0 H, 1 M - a dimmed Flymodus still opened the focus window; 5 L), ALL FIXED 01e0bb93b/74b7613a9, MERGED; the user 2026-11-15 after the screenshots: "Må vi ha navn under logoen? Det har ikke ios" -> NO NAMES: one row of icons folded and unfolded alike (bigger discs), names only in the picker and for TalkBack - goes into the fix round with the QA findings and a Vibb mark as the header icon (the long-press form falls: lock task blocks the assist gesture, design 98 §5b)** (the user, after the mockups: "Jeg likte best ... KONSEPT A · RUTENETT"; "l oss droppe tlf nr der, jeg har jo allerede det på home menyen for key contacts"): concept A - up to six round buttons, 3 x 2, filled peach when on; the kid picks and orders from what the parent allows; never on the lock screen; NO call/contact items. SHAPE (the user 2026-11-14: "bør vi bruke det smale utseende med bare enkle ikoner så vi ikke bruker mye plass"): one narrow row of six small round icons as the collapsed view, and - the user changed their mind the same evening: "ditt forslag om å utvide den ved å trykke på den er bra og det vil jeg ja" - an expanded view, the 3 x 2 grid with names, on a tap/drag; design 99 measures whether Android unfolds the top notification by itself and keeps it narrow by default if it can; filled peach = on. Measured on jelly36 (A16, lock task): Quick Settings leave no empty band, the shade starts with the notifications. DECIDED (the user: "Ja det er greit mtp lommelykt. Ønsker en på lock screen og"): Home's flashlight tile goes in the same round as Snarveier's torch; the lock screen's torch button stays.
- **One Vibb notification: the anchor merged into Snarveier - APPROVED 2026-11-15 (the user: "Genialt! Vi slår det sammen med snarvei baren vår, det er ideelt!"), DONE 2026-11-15, MERGED (`design-100`: step 1 c9f245e4/77a4bdf6, steps 2-3 308a1000/efa11c62, code QA de13200d fixed in 3a5dff45, the emulator gate 401e0374 - Nunito can't draw in the shade so the panel's lines use the shade's own text styles, a 12-hour clock, the card's gap - and §6ao's record 55619f35; migration 0149; owed on the Jelly Star: emulator.md §6ao. Left, small: the rule card's TalkBack text ends ".."; a gift just before midnight stays in the shade's note up to 30 min into the new day)**: while Snarveier shows, the anchor's foreground service (`CommandListenerService`) carries the Snarveier panel as its one notification, so the kid never sees a second, empty "Vibb One"; the time left ("1 t igjen i dag") moves into the panel (no "Be om mer tid" button there - D1 below). With Snarveier off the plain anchor notice comes back (Android requires one for the service). Icons with it (same message): Snarveier gets the gear, the plain anchor, Play's install mode, the ring, the setup notice and the filter's "kobler til" get the round Vibb mark (`ic_stat_vibb`). Also in it (the user, 2026-11-15: "Ja legg inn det du sa!"): a "Be om mer tid" button right on the time-rule screen's card when the screen time is used up (the same request, "Spurt kl. 14:02" after) - today the ask lives only in the shade, which a kid at "Skjermtiden er brukt opp" won't think to pull down. D-items answered 2026-11-15: no "Be om mer tid" button in the panel (the user: "Trenger egentlig ikke mer tid der" - the time line stays, the optional Mer tid disc stays a choice), D2-D4 and D6-D8 as recommended. Added the same day: one request open at a time - open until a parent answers or the day ends, not 30 min (the user: "barnet bør ikke få lov til å pinge flere requests mens en request for den dagen fortsatt er ubesvart"); the PWA's answer card at the top of the summary with +15 min / +30 min / +1 t / Nei (mockup "Forespørsel", the user: "ser bra ut"), the push as today opening it; "Nei" reaches the phone (API in both directories). After a no the kid may ask again (max 3 a day, one open at a time) and sees who answered ("Pappa svarte nei kl. 14:10") - the user's answers 2026-11-15. Revision 2 d1241704 (QA ce0272ca: 0 High, 4 Medium, 11 Low), its D9-D12 as recommended (the user, 2026-11-15: "Ja til alle"); the card goes after design 101's header. Build: step 1 (one notification, launcher only) BUILT (`design-100` c9f245e4 + 77a4bdf6); steps 2-3 BUILT (308a1000 the round trip with migration 0149, efa11c62 the time-rule screen, b2dca5dc docs; launcher 2635/0, server fmt+clippy green); code QA de13200d (0 High, 0 Medium, 11 Low) fixed in 3a5dff45 (launcher 2635/0); emulator checks (emulator.md §6ao) in work. Comes before the blocked-channels item below (it changes the channels). Size M.
- **The phone's summary page, mockup A, and the app's logo in its band - APPROVED 2026-11-15 (the user: "Vi går for a!" on the mockups https://claude.ai/artifact/HhJSZo1KzLwDe6AYCsynaB, and "Når jeg klikker inn på en app på App tab, kunne vi lagt inn ikonet helt oppe til høyre, akkurat som barnets profilbilde"), DESIGNED + QA'd (design 101 30ea7bcd, QA 3fde090f: 0 High, 7 Medium, 11 Low), D1-D9 as recommended (the user, 2026-11-15: "Ja til alle"), DONE 2026-11-15, MERGED (`design-101` 28902fa8..018a5cc9: the build, code QA 17f0b6f1 - 2 High, 2 Medium, 6 Low - and its fix round with the airplane period below; L5 let the Activity sub-line wrap without a two-line clamp so its clock never hides)**. Added 2026-11-15 for the fix round (the user: "Høres bra ut!"): the summary's grey "Flymodus var på 18:01–18:02" box goes - while airplane mode is on the status line says "Flymodus på siden …" as before; an ended period becomes an Activity row (no push); the emergency-call line and the lost-role warning stay.: the picture half over the band's edge with a pencil badge, a tap opens a sheet (Bytt bilde / Fjern bildet / Avbryt) instead of the "Remove picture" link; one status line (online, the zone with a pin, battery); Quick actions' three tiles say their state (time left, the rule in force or none, the next rule); Activity counts only what is new to this parent, not every row; the band's title row tighter. An app's page under the Apps tab shows the app's own logo at the band's top right, as a phone's pages show its picture. Size M, server only.
- **Captive portals and in-app browser logins without free Chrome - APPROVED 2026-11-15 (the user: "Ja legg i køen å komme meg en bra løsning for dette uten å slippe fri chrome"), DONE 2026-11-15, MERGED (`design-102`: design b724662c with revisions 2-5 after three QA rounds; build a63b3d4b server + contract with migration 0150, 0fd3de9d the Wi-Fi window, 17b3850c Chrome's role; code QA c1ee3eaf fixed in a5d7e521; the emulator gate ecb5f5cf - a POST's 307 reached neither WebView hook, now judged at commit; the captive state after a binderless close; Chrome's hide loop e20a3fa8 - and the record a7554c39; owed on the Jelly Star: real portals, Feide/Google logins, `chrome://policy`, a second SSID, emulator.md §6ap/§6aq. Seen, small, not fixed: Chrome's Privacy Sandbox prompts (add `PrivacySandboxPromptEnabled: false`), the PWA's recent sign-ins oldest first, and the test enrolment had no location permission for the launcher (not design 102's))**: hotel/café Wi-Fi sign-in through Android's own captive-portal login window (not Chrome: no address bar, the portal page only, closes when the network validates) - first measure on the emulator with a fake portal what Vibb does to it today (hidden by the allowlist? outside the lock-task list? the DNS filter and the VPN's lockdown in the way?); and apps that open a browser to log in: recommended Chrome under its own managed policy set by the device owner (URLBlocklist "*" plus a URLAllowlist the parent keeps in the PWA), the alternative a Vibb login window (Google refuses sign-in in embedded views). Never free browsing. Size M-L, launcher + server.
- **PWA: a switch save without the visible page refresh - APPROVED 2026-11-15 (the user, on Advanced: "om jeg slår brytere på og av der så kommer det forskjellige grader av synlig refresh hvor det også ser ut som noe Css blir tegnet på nytt ... Gjennomgående i hele pwa ønsker jeg jo egentlig ikke den type oppførsel"), DONE 2026-11-15, MERGED (`design-103` 341bd817 design, revision 2 23274969 after QA 1d06cd2b, build 1a8d62a2..5315631e, code QA 3d0b79a0 - 1 High, 1 Medium, 8 Low - fixed in 3a034f3f; jstest 438/0, the real-engine fixture green in Chromium and WebKit, a browser pass at 390 px; owed on the iPhone: Avansert's switches without a blink, the hairlines at 3x, VoiceOver on `#swap-status`)**: today `static/autosave.js` submits the form, so every switch is a POST, a redirect and a whole new page (scroll-restore puts the place back, but the page is painted anew). Design a save in place for the whole PWA: the post sent by script, the page not reloaded, only what the save changed updated (dependent rows, flash, badges), the no-JS form kept as the fallback, the save-never-jumps rule kept; Safari 14 floor. Also (the user, 2026-11-15, two screenshots of Advanced): "Open for setup" opened shows its title twice (the row and the panel's own header with Close), a dark-peach outline round the tapped row, three lines of text and a red button - one row that opens in place, no outline on a tap (keyboard focus only), one short line, one button; and the separators between the restriction switches show in one state and not the other - make them consistent on every list. And (the user: "Do it" to the recommendation) a text field's focus border softer: a lighter, thinner peach than today's 1.5 px `--tint` (pick A of 2026-10-25), on Add Phone and everywhere. Server only. Size M.
- **iOS: the grey fade over the status bar after a return - a fix that keeps the look - APPROVED 2026-11-15 (the user: "Do it"), RESCOPED, DONE 2026-11-15, MERGED (`design-104` 8f7c3def; the coordinator read the diff; jstest 392/0; the user checks on the iPhone after the next server release - if the fade still comes, whether it clears within ~3 s)**: the black-translucent premise was wrong - the builder found `status_bar.rs` has served `default` to iOS 27 since server-v0.72.1 (the user's iPhone is on 27.2 and the app was re-added then), so the iOS 26.1 scrim research doesn't apply and a test copy would prove nothing. The fade comes "etter den har vært i bakgrunnen av og til" (the user, 2026-11-15) under `default`: iOS's scroll-edge pocket losing `.top-strip`. Now: `top-strip.js` re-nudges the strip a few times after a return (~300 ms, 1 s, 3 s) and on keyboard close; no reinstall. Server only. Size S.
- **The music app's new icon: the Vibb rings with a play button - APPROVED 2026-11-15 (the user, with their own drawing `vibb-music-q7` 1024/512/192/180 px: "Kan vi ikke bruke denne som vibb music app ikon?"; the logo rounds on the canvas https://claude.ai/artifact/9wyg87Fx1AEvPm6qt8BWa6), DONE 2026-11-16, MERGED (`music-icon` 60bca7ba: `scripts/music-icon.py` from the launcher's own ring paths, the play triangle fitted to the 1024 px drawing; the vector render differs from the drawing in 0.21 % of pixels, all on ring edges - anti-aliasing; the media notification's and the new-episodes notification's small icon the same shape; music Gradle green; ships with the next `music-v*`)**: night rings on peach, three of the Vibb mark's wavy rings with a play triangle (nudged right) where the inner ring and the dot were. Rebuilt as vectors from the mark's own paths, not the PNG: the adaptive icon's foreground inside Android's safe zone (the PNG's outer ring would be cut by a round mask), `vibb_peach` background, the same shape as the monochrome (themed) icon and the media notification's small icon. Identical to the user's drawing (the user: "Klarer du å få den helt lik da?"): the mark's own ring paths, the play triangle measured from the 1024 px PNG, the drawing filling the 72 dp visible area (its outer ring then sits inside the 66 dp safe zone), checked by rendering the vector at 1024 px and diffing it against the PNG. Music app only; the name on Home stays "Musikk" / "Music" unless the user says otherwise. Size S.
- **Mobile network fully off (an airplane-mode equivalent) - DONE 2026-11-15, MERGED; the shade fix MERGED too (`d98-shade`, design 99 QA H1: the route's window drops LOCK_TASK_FEATURE_NOTIFICATIONS - Android collapses and blocks the shade - and the close takes Settings off the list before the shade comes back; measured on jelly36 before/after, a kill mid-window recovers; unit tests 2603/0; LEFT: a finger already in the shade during SystemUI's ~300 ms collapse can stop it - contained by step 7's clear + re-hide; design 99 must start the route only after a Vibb window has focus) (design 98: Flymodus in the kid's settings, the rule screen's "···" sheet, Foreldrekode's offline rule pause; launcher unit tests 2602/0 on the fixed tree; unreleased - needs a server and a launcher tag; ANSWERED 2026-11-15: (a) the post-112 refusal narrowed to ECBM + 10 min, Vibb's callback window dropped ("112 10 min er greit. Dette er et edge case"); (b) the clock rule stays ("Per i dag klarer ikke barnet å stille klokken på tlf selv"); the real Jelly Star checks below) - was APPROVED 2026-11-14 as Flymodus in the kid's settings through the Digital assistant role, DESIGN WRITTEN (design 98, `design-98` 82abae18: measured - the assist gesture never reaches the assistant under lock task (`DISABLE_SEARCH`), so the long-press quick panel FALLS; the route works at the keyguard and screen off; Settings must be unhidden for ~0.5 s; `DISALLOW_CONFIG_DEFAULT_APPS` blocks the onboarding, so a PIN-gated 5-min lift; kid row bit 64; contract `airplane_mode.json`; D1-D7 incl. a "Snarveier" ongoing notification in the shade as the quick panel's only working form), DESIGN QA DONE 2f7d2fd6 (1 H - the route wrote the lock-task list outside LockTaskChrome and could pin recents over the PIN lock; 9 M; 8 L), REVISION 2 9a9f77fe takes all 18 (the route an input of LockTaskChrome, status bar off during it, a DE marker for death/reboot, the report before the monitor, every role snapshotted, an Off-only row, no turn-on in ECBM or 10 min after 112); D8 and D9 ANSWERED 2026-11-14 (the user: "Tidsregler bør vel alltid gjelde så man ikke kan komme seg rundt de ved å sette på flymodus?" -> D8 first taken as Vibb ending airplane mode at a rule's start - WITHDRAWN the same evening after the user's traps ("man ikke kommer i scenario hvor du skal på fly og får ikke satt på flymodus ... eller ... ikke får slått av en regel"): proposed instead - turning airplane mode ON is always allowed, also during a rule (a button on the rule screen; it never weakens a rule), Vibb never ends airplane mode itself, "Slå av flymodus" on the rule screen when the rule allows calls, a calm Home line "Flymodus er på" with a tap to turn it off - waiting on the user; CORRECTION: the parent PIN on the rule screen does NOT exist any more (removed 2026-11-01 by the user's choice of 2026-10-26, `OfflineOverride` starts nothing) - I had said it did; mockups of a hidden PIN door + Flymodus on the rule screen sent (https://claude.ai/artifact/V3FYVAfh5yMaUFA5JLkYnA: A dim "Foreldre" text, B long-press the rule icon, C a dim "···" sheet - recommended; the PIN pauses only the current rule) - the user CHOSE C ("Jeg likte den", confirmed on the fixed mockups: "Vi bruker c som du foreslår!"; a tap on "···", the sheet slides up, no edge swipe): Flymodus + Foreldrekode on the rule screen, the PIN pausing only the rule in force offline (a narrow door replacing the removed 2-hour override), turning airplane mode on always allowed, Vibb never ends it, Home's "Flymodus er på" line - REVISION 3 54a046bb (the sheet, the offline pause of the rules in force as an ordinary `time_lifts` row `created_by = 'phone_pin'` via `POST /api/devices/rule-pause`, contract `rule_pause.json`, migration 0147; launcher M-L, server M), DESIGN QA OF REVISION 3 DONE b6eebe7d (0 H, 5 M - the PIN before the choice, the choices not the PWA's, a reboot-ended pause coming back, "Avslutt nå" in the gap lost, no alarm re-arm; 8 L), REVISION 4 e0037fab takes all (choose then PIN, the PWA's 15/30/60/120 min, one clock source per phone_key lift, ack on the post's 200 posted before the policy fetch, recheck; a copy's pause also pauses its rule via a new `copy_of` in `lock_now.json`); D1-D4, D6, D7, D10, D11 and D13 ANSWERED as recommended 2026-11-14 (the user: "Ok kjør på"): D1 the kid's switch on for new phones, hidden until the assistant step; D2 "Åpne for oppsett nå" on the QR page; D3 airplane mode stays off after 112, no turn-on for 10 min or in callback mode, a line in the emergency notice; D4 no battery saver now; D6 no Flymodus from the PWA now; D7 no push on turn-on; D10 Samtalefilter without adb in the setup window; D11 no Foreldrekode when only the budget locks; D13 no "Til <end>" choice - BUILT 2026-11-14 on `design-98` c120b2301 (steps 1-8; launcher unit tests 2602/0, server fmt + clippy clean; the jelly36 run, `docs/testing/emulator.md` §6am, found and fixed 4 bugs - Settings' floating voice theme, the 112 edge from `com.android.phone`, the post-Off sync waiting for a real validated network, the Wi-Fi step's resume; not run: 2b/2c, Bluetooth, Gboard's mic, the Wi-Fi step's 5-min deadline), CODE QA DONE 37e7f7d43 (0 H, 1 M - "Avslutt nå" on one rule of a two-rule phone pause ignored on the phone; 5 L), ALL SIX FIXED ed5c602d1 (per-rule lift check, own deadline alarms, `on_by` stamp, the emergency check off the main thread, the pre-plan chrome pass drops Settings, unposted pauses kept) - the unit tests ran green after the disk cleanup the user allowed 2026-11-15 (5.3 -> 50 GB free), MERGED; two questions to the user: (a) after a connected 112 call the turn-on refusal follows Vibb's own callback window, 60 min, not 10 - keep or narrow to ECBM + 10 min? (b) a clock set back ends the pause at once, setting it right inside the window brings it back (as the existing lifts) - ok?; to check on the real Jelly Star: the voice activity and its theme, `apm_enhancement_enabled`, the Wi-Fi panel, the 112 edge's sender; D1-D4, D6, D7, D10 and new D11 still to confirm; proposed as separate items: "Fridager" (a rule skipped on chosen dates, e.g. a travel day or holiday) and a check of what the music app plays offline; D9 accepted: "Oppblåst for oppsett er kun for oppsett"); D1-D4, D6, D7, D10 asked to confirm as recommended** (the user: "om vi kan gjøre on-boarding av assistenten enkelt når man in ruller er en ny tlf, så er jeg ok med å ta det med, og ta med flymodus i barnets settings"; asked, not decided: battery saver through the same route, and the quick panel - long-press power/home opens a small Vibb panel over any app; the user likes it: the kid picks the items from what the parent allows, never over the lock screen; the design proposes the list). Earlier: ASKED 2026-11-14, RESEARCH DONE (measured on jelly36 A16 with a throwaway probe, nothing in the repo): no DO API sets airplane mode, radio or SIM power, and Quick Settings stay off under lock task; the working route is Vibb One as Digital assistant - its voice session starts `Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE` (Settings briefly on the lock-task list), real airplane mode in ~0.3 s; OFF by adding and clearing `DISALLOW_AIRPLANE_MODE`; Vibb can't turn Wi-Fi on in airplane mode (Android keeps it on only after a privileged toggle once, `wifi_apm_state`); a 112 call ends airplane mode and it stays off; side finding to check: the SystemUI Internet dialog opened by a broadcast under lock task (from the DO probe); open on the real Jelly Star: the voice activity present, the power menu's items, the assistant role list; NOT YET TESTED: the route with the screen off/keyguard; proposal to the user, waiting on their word before any design)** (the user, after learning the kid can't reach airplane mode - Quick Settings are closed under lock task and the kid's settings have no row: "Hmmm det er jo et lite problem da? Undersøk hvordan vi kan løse dette og slå mobilnettet fulstendig av"): the user means REAL airplane mode (the whole radio, as Android's own; Wi-Fi may come back on after): find what a device owner can use on Android 14-16 to set it, else to turn cellular fully off (radio/SIM, calls and SMS too, emergency calls kept), from the kid's settings and/or the PWA; findings and a proposal to the user before any design. Next to design 97 (in build), not part of it.
- **Mobilnett revision 4: "I utlandet" and the kid's Mobildata switch - APPROVED 2026-11-14 (the user: "Høres bra ut!" + "er det mulig å gi barnet en bryter med av og på, hvor av betyr egentlig vibb only"), DESIGN WRITTEN (design 97, `mobilnett-r4` 5cbc0ee3: policy `abroad` off|vibb|home with the derived `data_roaming` for older launchers, capability mobile_network_v2, the kid's switch bit 32 of quick_controls_mask, migration 0145 - `phone` becomes Som hjemme only if Android's switch last read on, else Ingen data; the Jelly Star is dual-SIM on one modem, so the launcher most likely can't turn roaming on there - the parent does it once during Åpne for oppsett; D1-D7 to the user); design QA aec63de7: 1 H (a 901 ship/plane network marked home escaped Ingen data and Bare Vibb) / 8 M / 7 L; REVISION 2 000ca849 takes all (a 901 network always abroad, the sink kept across flaps/cold start/upgrade via a second `vpn_inputs_r4` record, an in-service home signal for the trip latch, the notice pruned with location retention, the day-price cost in Bare Vibb's sub-line, abroad by country ISO; the one-modem roaming write CUT - D9 - the parent turns Android's switch on once during Åpne for oppsett); D1-D9 DECIDED 2026-11-14 (the user): D1 Bare Vibb is the default (no "anbefalt" mark); D2 as written; D3 the kid's switch shown from the start on new phones (existing phones keep it hidden until the parent turns it on); D4, D5, D7, D9 as written; D6 CHANGED (corrected by the user the same day) - a notice at every border crossing into another country, a country visited earlier in the trip included, and a 901 ship/plane network as a place of its own; no notice for a place already notified within the last 1 h (border cells), none on coming home; D8 CHANGED - the notice always names the country, Location Off too (it is the cost fact). BUILT on `mobilnett-r4` (9707dba4..e7084c67: design revision 3, steps 1-6; the emulator's Wi-Fi bypass measured no leak - EPERM under every VPN shape; an out-of-service flap dropped the abroad sink, fixed - a suspended cellular network keeps the sink; upgrade/rollback with rc.1 kept the sink), CODE QA DONE 43fa9f83 (2 H - a test seed and a switch count, both CI-only; 2 M - Kotlin warnings, and a foreign network without Android's roaming flag read as home, M2 fixed as abroad by country whatever the flag; 8 L), ALL FIXED cca7afc3; MERGED 2026-11-14 to main, in no release yet (ships in the next server + launcher tags); still owed: the real Jelly Star checks (the vendor tile, dual-SIM relock, a second country, ~55 s sink hold) and two wording questions - (2) ANSWERED: no "Roamer i Norge" in national roaming, only that the phone uses another network at home (small follow-up, not built); (1) ANSWERED after mockups (https://claude.ai/artifact/14trZrh8r52SwX3Geex1aD): grey at home, orange with the country abroad - together with (2) a small follow-up, DONE - MERGED 2026-11-14 (`roaming-line` 1e53b449: grey at home or unknown, orange abroad leading with the place and no second place line; "Bruker et annet nett i hjemlandet" for national roaming from a `mobile_network_v2` report; reviewed by the orchestrator, small), in no release yet; was: the orange line at home, explained again (the user asked whether Android's switch is on by default - no: AOSP's default is off and their phone reported off), waiting; (2) clarified: home is the SIM's own country, never a hard-coded Norway to the user (the orange roaming-switch line at home under the new Bare Vibb default; the country shown in national roaming)**: (1) data roaming's two choices replaced by one, "I utlandet": Ingen data (today's Av, locked) / Bare Vibb (the sink while the best network is roaming - recommended) / Som hjemme; the launcher turns Android's roaming switch on itself where a device owner may (single-modem phones; check the Jelly Star's modem), else the parent does it once during Åpne for oppsett and the PWA says when it is off; (2) a notice when roaming starts ("<kid> er på roaming i <land>", a link to the choice); (3) clearer text on the Mobile Network page ("Telefonens valg" means Vibb doesn't lock; the status row names Android's roaming switch and says when the settings lock keeps it from being changed); (4) the kid's Mobildata switch (design 95's D6/N4): On / Off in the kid's settings, Off = Bare Vibb on cellular; it can only restrict more, never lift the parent's choice; reported to the parent. Server + launcher, a migration and a contract change (mobile_network.json). Not taken: automatic EU/EØS.
- **`launcher-v0.39.0-rc.4`: RELEASED 2026-11-10 on d84d2e05** (tagged 20:28 UTC, the user approved the `release` environment, published 20:50 UTC as a prerelease; versionCode 390004; the go "5 go" is used up). What it carries, as written before the tag: the failed picture's retry and the pause-hang fix are merged (2026-11-10), so it is tagged on the main commit that carries them once the launcher suite on main and Launcher CI are green there; each go is used by its tag. **For every emulator pass: what the user compares by eye, and every finding that depends on the screen's height, is taken on `jelly36` (36 px status bar), not `handy36` (136 px).**
- **Locate: everything under the map into rows like the rest of the PWA - ASKED 2026-11-12 12:44 UTC, MOCKUP APPROVED 12:59 UTC (the user: "Mocukp på finn ser bra ut, men kanskje ha posisjon innstillinger bare i samme grå kort som de tre andre valgene?" - four rows in one group: Posisjoner, Soner, Finn telefonen, Posisjonsinnstillinger; "Location is off" stays on the page above the rows, the wipe-not-ready warning moves to Finn telefonen), MERGED 2026-11-12 14:27 UTC (`locate-rows`, design 88: Soner, Finn telefonen and Posisjonsinnstillinger as pages, every in-flight request always shown on Find; code QA no High/Medium, L1-L3 fixed; Server CI 43233024243 green; released in server-v0.76.0)** (the user, on the one-phone view's Zones, the zone footer, Ring/Stop Ringing/Lock/Erase and the Requests log: "Meste under kart bør gjemmes i en settings sak, med lignende gui som alt det andre vi har? Samme design språk liksom"; also asked how Ring etc. work with several phones and whether the log is needed). Proposal: under the map only the age row + Update Location, then grouped rows - Locations, Zones (count, "Here now") and "Find the phone" (Ring, Stop, Lock, Erase and the requests history on its own page); design + mockup before any build.
- **The apps' real logos in the PWA - APPROVED 2026-11-12 12:39 UTC, DESIGN WRITTEN (design 87, `design-87` 763e7773, not merged; mockup sent), Q1-Q3 ANSWERED 12:56 UTC as recommended (the user: "1. appens ekte logo, 2) ja 3 enig!" - Automatic shows the real logo, every app tile round, a phone's rows only its own logo); DESIGN QA DONE (no High left - one High, the kids' names in the doc, fixed; M1-M6 and 11 Lows fixed in revision 2; the doc rebuilt as one clean commit on `app-logos` 29617781, the old local `design-87` branch with the names deleted, never pushed); STEP 1 (server + contract) MERGED 2026-11-12 14:02 UTC (`app-logos`: migration 0140, `app_logos.rs`, `POST /api/devices/app-logos/{hash}`, `GET /app-logos/{hash}`, `testdata/app_logos.json` in both dirs, round app tiles; code QA no High/Medium, L1-L7 fixed; Server CI 43230041054 and Launcher CI 43227768204 green; released in server-v0.76.0); STEP 2 (the launcher draws and uploads) MERGED 2026-11-12 14:34 UTC (`app-logos-2`: `AppLogoPlan.kt`, `AppLogos.kt`, sendStatus @Streaming with a bounded body read; code QA no High/Medium, L1-L5 fixed; suite 2513/0; Launcher CI 43235533265 green; emulator pass on jelly36: 19 of 19 logos drawn and posted on the first sync, hidden apps too, dark mode changes no hash, airplane mode ends the step quietly; a changed icon after an update unverified; released in server-v0.76.0 + launcher-v0.39.0-rc.7)** (the user: "Hvordan kan vi enkelt få inn de ekte logoene til apk vi legger til (og helst egentlig også de standard google appene som er pre-installed) på pwa?" ... "Ja takk!"): proposal A chosen - the launcher draws each installed app's own icon (96 px), reports its hash in the status report, uploads only the ones the server asks for; the server stores them shared across phones and shows them where the PWA draws a letter or glyph today. Launcher + server + a contract in both dirs; an older launcher keeps today's letters. Design and a mockup first.
- **The thin orange loading bar under the band only when a load takes time - ASKED 2026-11-12 22:02 UTC, MERGED 2026-11-12 22:23 UTC (`bar-delay`: `BAR_AFTER` 400 ms in reconnect.js `?v=13`, the pressed mark stays immediate; Server CI 43316890731 green; released in server-v0.77.1)** (the user: "Den der oransje loadestreken som vises i pwa under logo, den burde vi ta vekk med mindre det tar litt tid ... et litt unødvendig glimt som kan se ut som en feil"): reconnect.js shows `.net-progress` at once on every navigation; it should appear only after a short delay (a fast load shows nothing).
- **Locate: the age box under the map goes - APPROVED 2026-11-12 19:04 UTC, MERGED 2026-11-12 20:02 UTC (`locate-no-age-box`: the bubble carries the zone, the still line, "Last seen", the before-off and the exact time; Update Location the first grey row with its wait; the made-up-locations count its own row while above 0; the map taller again at clamp(280px, 55vh, 480px) on the user's word "vi tåler litt høyere kart"; Server CI 43291463612 green; released in server-v0.77.0)** (the user: "Trenger vi den statusen under kartet egentlig??" ... "Do it!"): the marker's tag and bubble already say the age and the accuracy; the bubble gains "I sonen Skolen" and "Har ikke flyttet seg siden 14:02"; "Oppdater posisjon" becomes the first row of the grey card, its wait ("Spør telefonen…") in that row.
- **Catalog app updates: auto as today, held for the parent when a release asks for new sensitive permissions, a 2-day wait for GitHub releases - APPROVED for design 2026-11-12 18:54 UTC, DESIGN WRITTEN (design 90, `design-90` f00afb05, not merged; server only, migration 0141), the user 19:06 UTC: no new notice - a waiting or held update shows only through today's inbox events (available, then updated; no new push switch, D1 no); the wait configurable in hours PER APP, chosen in Add App with 48 h pre-filled for every source, f-droid.org too (the user: "Bare legg at 48h er standard forslag for alle repoet også fdroid"; D2 changed), changeable on the app's page (the user: "konfigurerbart per pakke istedet ... som en del av gui"; D5 changed); own uploads go at once like f-droid.org (as designed); D2-D4 and D6-D10 as recommended unless the user objects; DESIGN QA DONE (`design-90` c58951a6: H1 the held row's own dedup key, H2 never stepping back to an older release, M1-M6, L1-L10 fixed; existing apps get 48 h too, 336 h cap); MERGED 2026-11-12 20:34 UTC (`design-90` e402ff1c + 16365a85 + aff5617d: migration 0141 (edited before any release ran it - the never-released branch's own amendment, `tracked_app_seen_releases`), `catalog_holds.rs`, the permission reader in `apk.rs` fail closed on a late pool/map and NUL strings; code QA's two High, M1 and Lows fixed; a missing wait field on Add App saves 48; the first release of a new app waits too with "First release reaches the phones … - or Update Now" (`FIRST_RELEASE_WAITS`, the user not yet asked to change it); Server CI 43297196810 green; released in server-v0.77.0)** (the user: "jeg støtter ditt forslag på versionkontroll av apper!"). Proposal: auto-update while the package, signer (already pinned) and the sensitive permission set stay; a release asking for new sensitive permissions (SMS, contacts, location, camera, microphone, notification listener, overlay, accessibility, ...) is held until the parent approves it from a push/inbox row; GitHub releases wait 2 days (F-Droid not, it builds itself), "Update now" skips the wait; optionally "Always ask me" per app.
- **Upstream's recent commits: anything to bring over - ASKED 2026-11-12 16:52 UTC, IN REVIEW** (the user: "Ser forøvrig at vår originale upstream har gjort noen commits; noe vi kan ta med oss på enten server eller pwa?"): siesta5787/kid-phone-server and kids-launcher-mdm read against ours; a list to the user, nothing ported without a go. REVIEWED: upstream since server f9e4e9671e / launcher 946ff1fec9 (9 + 19 commits); server and PWA need nothing; three launcher bug fixes proposed (c88216cfe0 the VPN service's stop-before-startForeground crash, 0d4201dab9 a fresh tsnet instance after a failed connect, 619f2bfab6+395a926122 Find My Device's fix indoors - fused high accuracy first, a longer bound for explicit ring/locate only); the user 18:50 UTC: "Foreslår vi tar inn alle de forbedringene du foreslo som var enkle og greie!" - the three launcher fixes MERGED 19:23 UTC (`upstream-fixes`: the VPN stop-before-start fix with a pending-start count and onRevoke; a failed tsnet connect drops its server only once joined, a first join keeps it; a QR setup connects once; locate's explicit fix fused-first with a one-update request and a 60 s bound; code QA one Medium and two Lows fixed; suite 2517/0; Launcher CI 43287147450 green; released in launcher-v0.39.0-rc.8); "Lock now" as an instant school mode for X hours - the user likes it, a design and mockup to come (the user: "Vis meg den skolemodus-nå mockupen!"; MOCKUP SENT 2026-11-13: a Quick Actions tile "Skolemodus nå…" with a sheet - the rule (Skole preselected), 1/2/3 h or Ut dagen, Til HH:MM - a running row with Avslutt nå, the kid's r13 rule card; server-only as a temporary `time_policy.rules` entry, S-M; Q1-Q7 to the user; the user's answers 2026-11-13: not "Skolemodus" - it is "the phone is locked now" (name proposal: "Lås nå", the kid sees the chosen rule's name + "Til HH:MM"); any of the phone's rules, its mode in the chosen window, also past its usual schedule; tile hidden with no rule; never past midnight; durations as proposed; Q2 yes. Plus a refresh of Quick Actions: one "Mer skjermtid…" tile instead of three, and the same sheet pattern for every action that needs a choice (extra time, pause rules, lock now, unlock). MOCKUP REVISION 2 SENT 2026-11-13: five tiles - Mer skjermtid…, Sett på pause…, Lås nå…, Lås opp…, Finn - one sheet pattern; running Lås nå reopens its sheet (extend/shorten, Avslutt nå); size M; open: Q1 switch rule while locked (no), Q2 a scheduled rule already on (both lock), Q3 pause-all pauses Lås nå (yes), Q4 Lås opp 30 min/1 t needs a launcher change (a length on `start_pause`), Q5 the kid sees the rule's name and icon; the user 2026-11-13 on revision 2: Finn tile goes (it has its own tab); Lås opp is unclear next to pause and rare - moves to an "Avansert" row under Innstillinger as "Åpne for oppsett" (fixed 2 h, Q4 dropped, so server only), its running state still a row on top with Lås igjen; no "…" on tiles; tiles: "Mer skjermtid", "Pause regler" (en "Pause rules"), "Lås nå". then (the user: "Eventuelt kunne vi bare hatt Rules, og undermenyen har å sette på rules i x antall timer, eller pause alle?") TWO tiles: "Mer skjermtid" and "Regler" - the Regler sheet picks a rule (or Alle for pause), Slå på nå / Sett på pause, and the duration. MOCKUP REVISION 3 SENT; DESIGN WRITTEN (design 93, `design-93` 34103249, not merged: server only, migration 0142 `time_lock_now`, the lock a dated-by-weekday copy of the rule in `time_policy.rules` with id -(row), policy shape unchanged; size M ~1,400 lines; gap: a phone offline 7+ days relocks the same weekday until it syncs - stricter, never looser, the Parent PIN lifts it); DESIGN QA DONE (revision 2, `design-93` 199cfb86: no High; M1-M6 and the Lows fixed - tiles as links `?open=` with the sheet below the grid, JS opens in place; auto-reload holds while a sheet is open); the user's answers 2026-11-13: Q1 ok but the card should follow a value from the PWA, Q2 ok but the phone should know the lock's absolute end - both as fields on the copy rule (a small launcher change, policy shape + a contract file, the server part first), Q3 Åpne for oppsett as the first row of the existing Avansert page, Q4 one at a time; tile state lines only when they say something (Pause regler: a rule in force or a pause running; Slå på regel: a rule turned on outside its schedule). DESIGN REVISION 3 DONE (`design-93` 5befcb22: `card_first` + `until_ms` on every served rule, contract `lock_now.json`, capability `lock_now_v1`; six steps, 1-4 a server release that works on rc.8, 5 the launcher for the next RC; ~1,750 lines); MERGED 2026-11-13 (`design-93` 945c6837; code QA one Medium - a rule switched off kept its lock running, now ended by a trigger in 0142 - and four Lows fixed, three Lows left as noted; Server CI 43417931602, Launcher CI 43417935602 green; the server part released in server-v0.78.0, the launcher part (`until_ms`, `card_first`, `lock_now_v1`) for the next RC with an emulator pass); the user 2026-11-13: THREE tiles after all - "Skjermtid" (not "Mer skjermtid"), "Pause regler" and "Slå på regel", the two rule tiles opening the same sheet with the mode preselected); holding a release that asks for new permissions - the user leans yes and asked for thoughts on auto-update in general; Find My Device before the first unlock - explained (not Tailscale-specific: before the first unlock after a reboot the launcher can't run at all, public endpoint or not).
- **PWA app lists: the app's real logo even when the parent chose a glyph - ASKED 2026-11-12 15:48 UTC, MERGED 2026-11-12 16:10 UTC (`logo-always`: the list templates put the logo first; design 87 revision 3; light review by the coordinator; Server CI 43252324275 green; released in server-v0.76.1)** (the user: "apper jeg har gitt egne ikoner vises med egne ikoner og ikke originale? jeg trodde vi bestemte originale ikoner?"; design 87 R2-L5 had a chosen glyph win). The lists (Device › Apps, the Apps tab) show the phone's real logo whenever there is one; the chosen glyph stays in the "Name and icon" preview and its sub-line.
- **Kalender as a "Del av Vibb One" row in Device › Apps, like Meldinger - ASKED 2026-11-12 15:48 UTC, APPROVED 16:52 UTC ("Ja til begge"), MERGED 2026-11-12 18:50 UTC (`calendar-row`: Kalender's row under Meldinger in Device › Apps, gated on `calendar_v1`, links to Device › Calendar #dagen, "On Home"/"Not on Home"; Server CI 43264191711 green on its rerun - the first run hit a runner disk I/O error; released in server-v0.77.0)** (the user: "ser også at messages vises i vår app liste, men ikke kalender? gir det mening?"). Meldinger is an app row since design 82 (its key `me.vibb.builtin.messages`, lock level, Home, name and icon); Kalender (Dagen) is only Device › Calendar's switches.
- **iOS: the status bar area turns a grey fade again after a return from the background - REPORTED 2026-11-12 15:11 UTC, MERGED 2026-11-12 15:44 UTC (`ios-top-pocket`: `html:not(.doc-scroll)` night in the iOS standalone block, `static/top-strip.js` nudges the strip on pageshow/visible; light review by the coordinator; Server CI 43245999606 green; released in server-v0.76.1; the user checks on the iPhone)** (the user: "Har nå sett noen ganger at den toppen kommer tilbake??" ... "Plutselig når jeg kom tilbake til den nå! Forsvinner etter at jeg drepte den"). Read of the code and WebKit: not painted by us - iOS's scroll-edge pocket tinted with the light canvas when WebKit stops sampling `.top-strip` (after a reload or a resume). Fix: the canvas (`html`) night in the installed iPhone app only, plus a one-frame nudge of the strip on pageshow/visible so WebKit re-sends its colour. Server only. **REOPENED 2026-11-13: the user on server 0.78.0: "den fortsetter å gå til den skyggeovergangen når den har vært fokus. Intermittent issue som fortsetter" - the 0.76.1 fix does not hold. Asked whether "fokus" means after a text field had focus (the keyboard; the nudge runs only on pageshow/visible) or after the app was in the background, and for a screenshot. Not in work until then.**
- **The band's Vibb mark blinks on pull-to-refresh - REPORTED 2026-11-12 12:39 UTC, MERGED 2026-11-12 13:13 UTC (`band-mark-svg`: the mark inline as SVG, 2.3 KB; Server CI 43220879208 green; released in server-v0.76.0), with Locate's shorter map (12:41 UTC, the user on an iPhone 16: "Burde kartet gjøres litt kortere?" - `clamp(260px, 45vh, 420px)` for `clamp(300px, 60vh, 520px)`)** (the user: "om jeg trekker ned update swipen vår der, så blinker vibb logoen? Er det nødvendig?"): the pull reloads the whole page and the band's mark is an `<img decoding="async">` PNG, painted only after it decodes. Fix: the mark drawn inline as SVG in `partials/band.html`. Server only.
- **Android developer verification - DONE 2026-11-13: the user's account approved, `me.vibb.launcher` and `me.vibb.music` registered with the release certificate (SHA-256 AC:4B:04:26...0B:5D, checked against rc.8's APK)**; a new package (design 92's bootstrap, if built) needs its own registration: then register `me.vibb.launcher` and `me.vibb.music` with the release key's certificate (`RELEASE_CERT_SHA256`); any key-ownership proof is signed locally by the user or by a one-off workflow in the `release` environment, never in the repo. Enforcement reaches Norway in 2027; it does not lift design 92's Play Protect question.
- **Locate looks the same with one phone as in the Alle view - APPROVED 2026-11-12 10:40 UTC, MERGED 2026-11-12 11:21 UTC (design 85 revision 3; `static/map-marker.js` shared by both views; code QA one Medium - the first draw's label - and three Lows fixed; Server CI 43204352908 green; released in server-v0.75.1)** (the user, on server 0.75.0 with one phone, seeing the old page with Leaflet's blue pin and the lone switcher button: "ja selvsagt skal det se likt ut!"). The one-phone view takes mockup B's look: the phone's avatar marker in its tone with the time tag (fresh/old), the chips over the map instead of the switcher above it (no chips row with a single phone), the legend under the map and in full screen; the trail, 12 t / 24 t / 7 d, the age row, Update Location, Locations and the zones stay. Server only.
- **Bug: a catalog app's update shows "available" in the inbox but never "updated" - REPORTED 2026-11-12 08:01 UTC, MERGED 2026-11-12 08:58 UTC (`tracked_apps.latest_release_version_code`, migration 0139: the release APK's own versionCode, read at sync and by a startup backfill, judges "installed"; the name only as a fallback; confirmed cause: the APK's versionName is `2026.11.11-39-Web`; code QA no High/Medium, L1-L3 fixed; Server CI 43183468138 green; released in server-v0.75.0; the 2026.11.11 install raises nothing after the fact, the next release does)** (the user: Organic Maps' new version was reported in the PWA inbox, the emulator shows it was updated, the inbox never says so). Likely cause: `catalog_release_installed` compares the phone's versionName with the tag's version, and Organic Maps' tags (`2026.11.11-39-android`) are not its versionName; fix by comparing the release APK's own versionCode. Server only.
- **Locate: every phone on one map - APPROVED for mockups 2026-11-11 ~21:48 UTC, MOCKUP B CHOSEN 22:00 UTC, DESIGN WRITTEN AND QA'D (design 85, `design-85` 31e65572 on origin, not merged: design QA no High, five Medium and the Lows all taken in revision 2); O1-O3 ANSWERED 2026-11-12 06:34 UTC as proposed (the user: "Dine forslag er bra!" - O1 1 h, O2 the seven colours in that order, O3 skip Av, not set up and Disconnected); STEP 1 (the colours) MERGED 2026-11-12 07:25 UTC (`devices.avatar_tone`, migration 0138, seven `.tone-*` classes; code QA no High/Medium, three Lows fixed, Server CI 43173137962 green; released in server-v0.75.0); STEP 2 (the Alle view) MERGED 2026-11-12 09:12 UTC (`phones.json`, `POST /devices/locate/all`, `static/locate-all.js`, `static/map-full.js`; code QA no High, M1-M2 and L1-L5 fixed; Server CI 43187022619 green; released in server-v0.75.0; the user's iPhone check open), with a full-screen map (⤢ on the map, ✕ to close, the legend kept as a strip at the bottom) - mockup APPROVED 08:01 UTC ("Mockup er godkjent!")** (the user: "Jeg likte B!" - a large map, "Alle / Emma / Jack" chips over it, a time tag on each marker, a tap opens a bubble with "Vis <name>"; "fresh" follows the phone's chosen update frequency - "fersk bør vel henge litt sammen med valgte oppdatering frekvens"; and every phone gets its own avatar colour until a photo is set - "forskjellige farger generelt som avatar frem til man setter et bilde ville jo vært god skikk", across the PWA) (the user: "i utgangspunktet viser den last known på begge, men tydelig om det er recent eller ikke. Og at man kan velge en og en også. Ser gjerne noen mockups her."): today `/devices/locate` shows one phone at a time with a switcher; wanted an all-phones view first, each phone's last known place with how fresh it is made plain, and the one-phone view as today. Server only; no build before the user picks a mockup.
- **Screen-size matrix on emulators - RUN 2026-11-14 (the user: "Kjør 4 som du foreslår"; `screen-sizes` 06d08a4f, docs/testing/2026-11-14-screen-sizes.md: narrow 4", jelly36, six35, a 6.7" tall; 2 H - S1 the time-rule screen's Nødsamtale under the 3-button bar on the Jelly Star class, S2 the narrow class's music player controls overlap -, 3 M S3-S5, 5 L S6-S10 with S10 a design question (nothing scales up on 411 dp); S1-S9 FIX ROUND APPROVED 2026-11-14 (the user: "Kjør på! Barnet må kunne bruke både bevegelse og knapper" - every screen must work with both navigation modes), BUILT on `size-fixes` (5f0d2f97 + ad24218a; Launcher 43585622417 / Music 43585625194 green; emulator pass with both nav modes); code QA 0b5a4bd6: 0 H / 2 M (Dagen's second line shrinks on a long link; S9's 80 dp strip in landscape) / 8 L; fix round 95e72d51 (M1 Dagen's second line a plain TextView again; M2 the strip only while a page keeps ~3 rows, else the floating "+", a swipe from the strip turns the day; L1-L8, L6/L7 tests pin S1; Launcher 43593761109 / Music 43593763423 green); MERGED 2026-11-14; in no release yet - the next launcher RC and music tag; S9's strip under the "+" shown to the user, waiting on their word (`ADD_STRIP = false` in `ui/day/DayActivity.kt` gives the old floating "+"); S10 shown to the user as side-by-sides, waiting on their word)** (the user, 2026-10-25: "kjøre tester med emulatorer med veldig forskjellig skjermoppløsning og dimensjoner så vi kan sjekke hvordan løsningen vår skalerer på ulike telefoner"): one pass of every launcher screen (Home grid, phone book, keypad, the glance in own and android mode, the time-rule screen, Kid Settings, the pause screen, the music app's library and player) on at least four AVDs - a narrow 4" (540x1168 @ 320 dpi, 270x584 dp, the Bluefox NX1's class), the Jelly Star (480x854 @ 240, 320x569 dp), a 6.1" (1080x2400 @ 420, 411x914 dp - `six35` exists) and a 6.7" tall (1440x3120 @ 560), each at font 1.0 and 1.3, 3-button and gesture navigation; screenshots under `docs/testing/screens/sizes/`, a findings table like `2026-10-23-six-inch-android15.md`. Launcher (+ music), M; the narrow one first since it is below our 320 dp floor.
- **E6 finding L1: the lock glance's views log `requestLayout() improperly called`** (12 lines; none from Home; `docs/testing/2026-10-31-e6-run.md:176`; launcher, a log-noise fix, MERGED 2026-11-13 (`small-fixes` 465b57a1; code QA one High - a stacked picker 0 px wide - one Medium and two Lows fixed; Server CI 43396817615, Launcher CI 43396821782 green; device checks left: the glance's logcat, Meldinger scrolled up, the PWA at 200 % on an iPhone; in no release)). L3, L5 and the doc wording are built (archive).
- **Two server tests fail around local midnight** (seen 2026-11-09 ~23:00 UTC by design 80's implementer, on `main`'s code): `tests::summary::an_event_row_shows_its_clock_once` and `quick_actions_list_running_pauses_in_local_time` passed again after midnight. MERGED 2026-11-13 (`small-fixes` 465b57a1; code QA one High - a stacked picker 0 px wide - one Medium and two Lows fixed; Server CI 43396817615, Launcher CI 43396821782 green; device checks left: the glance's logcat, Meldinger scrolled up, the PWA at 200 % on an iPhone; in no release): make them independent of the wall clock (a CI or release run at that hour can go red on them). Until then: no server tag between 22:30 and 00:30 UTC.
- **Calls & Contacts scrolls sideways at 200 % text size** (seen 2026-11-10 by design 80's implementer while measuring V3, at 390 and 320 px; not from design 80's rows): the tab bar's "Innstillinger", the RCS row's link and the message-app select are wider than the screen at a 200 % root font. MERGED 2026-11-13 (`small-fixes` 465b57a1; code QA one High - a stacked picker 0 px wide - one Medium and two Lows fixed; Server CI 43396817615, Launcher CI 43396821782 green; device checks left: the glance's logcat, Meldinger scrolled up, the PWA at 200 % on an iPhone; in no release); no page of the PWA should scroll sideways at any text size - check the other tabs at 150/200 % in the same round.
- **A reaction's chip moves the rows above it while the kid reads older messages** (seen 2026-11-10 in the mark's emulator pass, from the thread-follow round, not the mark): scrolled up, a reaction arriving for a bubble on screen grows that bubble by the chip's 18 dp and the rows above move up by as much; "scrolled up stays put" should hold for it too. Low, launcher only, MERGED 2026-11-13 (`small-fixes` 465b57a1; code QA one High - a stacked picker 0 px wide - one Medium and two Lows fixed; Server CI 43396817615, Launcher CI 43396821782 green; device checks left: the glance's logcat, Meldinger scrolled up, the PWA at 200 % on an iPhone; in no release). The same move, found 2026-11-10 in `bubble-time`: the last bubble, cut by the list's lower edge while the kid reads older messages, loses its time line when a message joins its run, and the rows above come down by the line's height (measured on the emulator: 26 dp for each such message); one anchor would serve both.
- **`launcher-v0.39.0-rc.3`: RELEASED 2026-11-10 on 28ccd706** (the user approved the `release` environment; published 11:35 UTC as a prerelease) (versionCode 390003; the user's go "Du har go for neste launcher RC"; launcher suite on main 2428/0, Launcher, Server CI and Music green on that commit). The go is used up: the next launcher tag needs a new one.

### From "Done since the last release"

- **Design 78, the kid's light mode: STEP 2 MERGED 2026-11-12 23:12 UTC (`kid-look-3` 78edd6f2 + docs 8c77bf9b + the code QA's fix round 7fa9c26c - H1 a thread opened by id dropped its kept text, M1 the search highlight in Lys, L1 orphan kept texts, L2 commit() on Accepted; L3 accepted; launcher suite 2493/0, Launcher CI green 43116553730); the emulator pass on `jelly36` passed A-D (screenshots sent; not seen: Wi-Fi/Bluetooth rows, font scale, 3-button navigation, Mørk->Lys; the Jelly Star recording still owed - no test phone); released in launcher-v0.39.0-rc.6 2026-11-12; in build since 2026-11-11 22:10 UTC (`kid-look-3`; the user: "Ja" to starting it now - Meldinger, the phone book, the Wi-Fi/Bluetooth/sound lists on page tokens, the row in release builds; goes into the next launcher RC).** Before: step 0 (the spike): THE EMULATOR HALF DONE 2026-11-10 ~21:20 UTC (branch `spike-78`, 78b6836c, throwaway, never merged; 22 recordings on `jelly36`, Android 16). The ground: no frame of the other look on any path (Home's tile and the lock glance row, cold and warm, Mørk and Lys, system night on and off) - `setApplicationNightMode` decides the starting window, and it must be set at every unlocked start (unset, the page follows the system: a light splash before a Mørk page). The bar's ink: on the cold open from Home's tile in Lys the status icons stay white on the light bar for ~230 ms before a ~95 ms tint (warm and the glance row pass; the stock dialer's cold start tints at once, so it is ours - cause not found). Non-page activities stay night in Lys. Not checked: the expanded dark option (the image has none), the Jelly Star. The user, 2026-11-10: no test phone yet, only the emulator - so the Jelly Star repeat stays an open risk (the spike APK is a debug build, never the kid's phone). **The bar's 230 ms accepted by the user 2026-11-10 ("det er greit"): step 0 counts as passed on the emulator alone; the cause is looked for during step 1, without holding it. STEP 1 (the mechanism, the tokens and Kalender; the kid Settings row in debug builds only) BUILT, CODE QA AND ITS FIXES DONE (2026-11-10 23:28 UTC): branch `kid-look-1`, three code commits from bc547c84 to 163aceb5 (2464 unit tests green). The code QA (`docs/review/qa-78-step1-code.md` on the branch) found no High, three Medium (Kalender in Mørk lost the 3-button scrim - kept as today now; two test gaps - closed, its five surviving mutations are caught) and nine Low, all handled. THE EMULATOR PASS on `jelly36` (`docs/testing/emulator.md` §6ah) RAN IN PART, 2026-11-10 23:54 -> 2026-11-11 01:38 UTC, AND STOPPED: the kernel killed the emulator for memory at the pass's second reboot (qemu 4.7 GB beside a 3.4 GB idle Kotlin daemon on 11.9 GB); `jelly36` restored and verified. Branch `kid-look-1` now at 4845b74d (the pass's notes, docs only), not pushed. **NOT MERGED, AND BLOCKED BY A FINDING (2026-11-11): after a reboot the launcher's own language (`nb`) was gone and Home, the PIN lock, kid Settings and Kalender were in the system's language (English); `LauncherLocales.applyIfSafe` asked again at Home's resume and the system refused five times (`IllegalStateException: Can't change activity type once set`) for as long as Kalender's task lived in Recents - which the kid cannot close under the kiosk. Seen once; the cause is inferred, not shown (the per-app record that carries the night mode is kept over a reboot without its languages - the control test was not run). main sets no night mode and has neither problem; a phone whose system language is the launcher's shows nothing. Open in design 78 §5.3. THE CAUSE READ FROM ANDROID'S SOURCE AND A FIX BUILT 2026-11-11 02:35 UTC (`kid-look-1` at 7b15ee70, 2470 unit tests green; NOTHING OF IT SEEN RUNNING): Android writes the per-app record without its languages once a night mode is set (read in AOSP `android-16.0.0_r1`, `PackageConfigPersister`), every boot, so the launcher now asks for its language at every unlocked start right after the night mode; a change refused at Home's resume closes Kalender's and Meldinger's tasks and asks once more. A rest stays (a parent's language change can stay refused until a PIN unlock, a page or a call - inferred). Its code QA (a fresh reviewer, 2026-11-11 02:55 UTC) confirmed the three source claims, no High, two Medium: the second ask is probably refused when the kid comes Home straight from the page, and a change that stays refused closed the two tasks again at every Home resume - both inferred, neither seen. Its fix round built (one late ask, the close at most once per wanted language, the Lows; 9ca55e8e) and THE SECOND EMULATOR PASS on `jelly36` RUN 2026-11-11, ended 06:15 UTC (branch `kid-look-1` at 67642f31, 2473 unit tests green, not pushed; `jelly36` restored and verified; the emulator was never killed - every reboot was a power-off, the memory read, a cold start). SEEN: the language stays `nb` over seven boots with Kalender's and Meldinger's cards left open, no English launcher frame pictured - the first pass's finding is gone. Its costs, seen: Home relaunched on two of the four boots whose record had lost the languages; the notification channels named in English on four boots (a fix built after the pass, NOT seen running); the two system calls at the unlocked start took up to 1.7 s and 2.6 s on loaded boots, and once the launcher got an ANR at boot (11.5 s unlocked setup on a heavily loaded emulator boot; a phone unknown). **A CHANGE OF LOOK CAN BE REFUSED ON A NATURAL ROUTE (seen twice): a process started at boot for a service, a parent's language change set at the unlock under the PIN lock - from then every look change from kid Settings is refused for that process's life (the old look's starting window ~330 ms; the pages themselves draw right). Step 1's release builds have no look row, so only the language half is there (a parent's change can wait for a resume right after a page, the PIN lock or a call). main has the refusal too, narrower (the control, seen).** Also seen: B.7, B.9-B.11, C (Mørk is main's picture; M1's 3-button scrim both halves), D (non-page screens are main's picture, no relaunch on a system theme flip); a finding in Lys with the system dark and 3-button navigation: white buttons on paper, almost invisible (not built). NOT RUN: TalkBack itself (the kiosk blocked enabling it), force-invert (not on the image), L4's death-between, L5, a `tel:` link from another app, the channels' fix, a release build, a phone. **STEP 1 IS NOT MERGED, BY MY DECISION (06:20 UTC): in a release it would put the per-app night mode's costs (the boot calls, Home's relaunch, the refusals) on the kid's phone with nothing for the kid to see, and the refused look change must be settled before step 2 anyway. THE ARCHITECT'S ANSWER (06:23-06:48 UTC, paper and source reading only, `kid-look-mech` d2812379 = 67642f31 + design 78 §12 "Revision 3", local, `kid-look-1` itself is pushed to origin as a backup): take the two-activities mechanism after a spike - no `setApplicationNightMode`, a night and a light manifest activity (literal window colours) for each page that can top a task of its own, the launcher picks the class, pages declare `uiMode`, a change removes the page tasks first and stores the look after; the gate inside the kept mechanism is rejected (it cannot make a refusal impossible, and the reboot record, the boot calls and Home's relaunch stay). The language fix would leave design 78 (main's own narrower refusal stays its own item); about 18 files, +300/-200, half in tests; a device that ran step 1 must be wiped. NOTHING OF IT IS BUILT OR SEEN. DESIGN QA OF §12 DONE (06:48-07:11 UTC, paper, `kid-look-mech-qa` 9b078b88, `docs/review/qa-78-design.md` "Revision 3"): the mechanism holds against Android 16's source and AppCompat 1.7.1's, 0 High, 2 Medium (the spike's recordings cannot show a wrong dark frame in Lys - the system's starting-window log lines must gate; the order "remove the tasks, then store" has failure rows the design called unreachable), 5 Low; the spike may start after four named changes. THE ARCHITECT'S REVISION DONE (07:11-07:28 UTC, `kid-look-mech` e3a3e175, local): every finding taken, the look stored only when a second read shows no page task left and only when the write succeeded, the two routes that stay reachable named (power lost mid-change; a waiting change between a page's start and its creation - the kid sees one starting window in the other look, the task closes, the next tap is right), and the spike as a run sheet (§12.7.1). I read R1-R7 and the run sheet; no second QA round. THE SPIKE IS IN WORK since 07:28 UTC (the step 1 implementer, throwaway branch `spike-78-twins`, debug build, `jelly36` restored clean first; design 83's pass is over, the emulator is free). A failed A2, A1, A5 or A6 means the mechanism is not built. THE USER, 2026-11-11 07:43 UTC (asked whether design 78 had brought much complexity - they had expected a simple change of background): a blink of the other look when a page opens is not acceptable - "isåfall dropper vi heller hele greien". So: no variant with a blink is built; if the spike fails, the light look is dropped (I confirm with the user before removing anything), and no more machinery is added to save it. THE SPIKE ENDED 2026-11-11 ~09:35 UTC: PASS ON EVERY GATING LEG (throwaway `spike-78-twins` eb398122, local, never merged; `jelly36`, Android 16, restored clean after). A2: eight held starting windows, the page area 100.0 % the stored look's ground in both phone themes. A1+A3: twelve recordings, no frame of the other look's ground, no relaunch line. A5: a change of look closes Dagen's task ~350-450 ms after the tap, never two classes or two cards. A6+A7: pass on the routes that could be run - the card routes could not be run as written (after a reboot the restored task leaves recents ~5 s after the kiosk locks; after a kill the overview shows no card), the task was brought forward another way; the language held over both boots. A4: the phone's own flips create no page. A8: no `Can't change activity type once set`, no `E PageLook` in 334,055 log lines; 95 page starting windows, none against a rule. Leg C (a notification made in the other look) passes. Leg B (the sound picker in Home's task; decides step 2's shape, does not gate): no starting window, both ways; but in Lys with the phone dark the list is a night ground with the light look's ink (`kid_sound_root` paints the fixed `@color/kid_ground` while the theme resolves notnight) - the build makes the non-page activities fully night in both looks, and the finding goes to the architect with step 2. Still as before: the status bar's ink late on the Lys starting window (~125-345 ms; the user accepted it), the 3-button icons near invisible in Lys with the phone dark, the two rare one-glimpse routes (told, unanswered). THE BUILD (step 1 reworked per §12, a branch from `kid-look-mech` e3a3e175) BUILT 10:06 UTC (`kid-look-2` 42d1bace, pushed as a backup, not merged; in work since 09:41): `assembleDebug testDebugUnitTest` green, 2478 tests; not seen running. The implementer's own list of what the design did not cover goes to the code QA (how the class-against-look check removes the task; what a refusal writes back; a refusal whose own write fails; a refused change has already closed Kalender's task with no word on screen - told to the user). CODE QA DONE 2026-11-11 10:24 UTC: no High, no Medium, R1-R8 hold, no third route to a frame of the other look, the five uncovered points accepted as built (a refusal is effectively unreachable, and the kid's control jumps back to the applied look); four Lows (a stale comment, R1's scan not reading `src/release/java`, two stale notes) FIXED 10:29 UTC (`kid-look-2` 1ae78e97, on origin as a backup; 2478 unit tests green; the same scan gap closed in `PageTokensScanTest`); THE `jelly36` PASS DONE 12:20 UTC (the AVD restored clean; every open path measured by frames in both looks - tile cold and warm, the glance's reminder row, the time-rule screen's row, day/event/edit and back, Home and in, the Recents card, after a kill, after a reboot, after a change of look and of language, five forced other-look tasks (B.17-B.20): NO FRAME OF THE OTHER LOOK, the starting window included, no crash; `docs/testing/emulator.md` §6ah, "The run of 2026-11-11"); STEP 1 MERGED 12:20 UTC (`kid-look-2` d287a69f), screenshots sent; two findings under the kiosk after a launcher process kill with Kalender's task alive, neither a look frame: a change of look then drops kid Settings to Home, and Back does not leave Kalender (listed under "Waiting on the user"; told to the user); the implementer also added the pass's new steps to the run sheet (`closeIfOtherLook` forced as a root and as a non-root, once under the kiosk); after it: the fixes, a `jelly36` pass (with a real tap on a restored card), the merge. The late status-bar tint and the white 3-button icons in Lys are not improved by either mechanism. The user is told of the decision not to merge; the architect's answer is told at 06:48.** Open for step 2, not built (N1, design 78 §5.3): a change of look from kid Settings can be refused the same way - to settle before the row shows in release.** Passed, seen: A (one `onCreate` per cold open, no frame of the other look, 12 recordings), B.1-B.8 (the change under the kiosk, `LOCKED` before and after, the upgrade from main's build), B.12 (the look's cost 14-58 ms, 332-405 ms under a loaded boot). The bar's late tint in Lys measured again: cold 255-498 ms, and warm too, 165-201 ms (new) - the system's bar follows the app transition late, no theme fix found, nothing changed. NOT RUN: B.7's `install -r` half, B.9-B.11, C (so M1's 3-button scrim is confirmed in neither half), D, E (TalkBack, font 1.3), F, L4, L5, most screenshots (the user has the day view and the Utseende row in both looks). A continuation needs the idle Kotlin daemon gone or a pass without reboots; its working copies stay in `/home/palchrb/kid-look-1-avd-used`, `-avd-pre-start` and `-work` until then. Then the merge. For the user's eye before step 2 shows the row in release: in Lys the date picker's unselected "today" number is 2.6:1 and the "+" disc is 2.3:1 on the paper ground**** (the user, 2026-11-10: "4 you decide", and the same evening "Du trenger ikke holde igjen mtp design 78" when asked about the week's usage) - it recolours the same screens design 82 and the mark are changing, so it follows them. First its step 0, the throwaway spike on the emulator (Meldinger, Kalender, the lists, the phone book in Lys, the contact card over a Lys phone book); the Jelly Star look of the spike is the user's. The note's build steps after their word on the spike.
- **The phone's own events name the phone, not the kid - ASKED 2026-11-11 ~21:33 UTC, MERGED 2026-11-11 21:59 UTC (`phone-words` a54721e7 + 526bca76: `i18n::phone_of`, `{phone}` in the phone's titles - pin_used, pin_lockout, foreign_access, unseen_boot, low_battery, phone_off, phone_on, app_updated, server_tls; the kid's own events keep `{name}`; Server CI green on the same tree, 43105621775); released in server-v0.79.0 (2026-11-14)** (the user on 0.74.1: a push read "<the kid> was turned on" - "Det høres rart ut"; wanted "<the kid>'s phone" / "<the kid>s mobil"): every push and inbox text about the phone itself (on, off, battery, out of touch, updates, safe mode, SIM and the like) says "Emma's phone" / "Emmas mobil" (the genitive by language: en `'s`, nb `s`, or `'` after s/x/z); what the kid does or asks (more time, arrived at a place, an emergency call) keeps the name. Server only, en + nb.
- **`server-v0.74.1`: RELEASED 2026-11-11, 21:18 UTC, on 808a3d31** (the user's go "Bare tag når du er klar og ping meg for signering" and their approval of the `release` environment; the first run's builds failed at "Upload the tarball" - GitHub's artifact service answered an HTML page - and passed on the rerun; the go is used up) - the night band's four points (`band-fix-1`). The user checks on the iPhone: the bounce at the top (also on a short page), Back to where the parent was, no ring on the row, the rounded top under the status bar.
- **The night band on the user's iPhone (server 0.74.0), four points - ASKED 2026-11-11 ~18:28 UTC, BUILT (`band-fix-1` c6ed2261 + docs 70ccbe82), CODE QA DONE (no High; M1 short pages' bounce, M2 where a page came from, L1 taps under the band's foot, L2 a stale entry - all fixed in 3e589b45; L3 a keyboard user's ring on a fragment page accepted), Server CI GREEN on 3e589b45 (43088072970), MERGED 2026-11-11 19:53 UTC; goes into `server-v0.74.1`** (the user's screenshots, not copied: "Natt båndet ser ganske bra ut men jeg har noen tanker"): (1) pulling down shows a light field between the status bar and the band - "kan vi kvitte oss med det?"; (2) back from a phone's Apps page (`/apps#phone-N`) lands scrolled with that row at the top - expected: where the page was; (3) on the way back the row wears an odd frame, likely on every `#…` back link; (4) the user's mockup: while scrolling, keep the sheet's rounded top corners under the status bar - my answer: agreed, built in the same round, shown to the user in browser pictures (not iOS's bounce, which the user checks on the phone). Server only; goes into 0.74.1.
- **`server-v0.74.0`: RELEASED 2026-11-11, 17:12 UTC, on 3ffc28ab** (the user's go "Do it!" and their approval of the `release` environment; tagged ~13:45 UTC; both `server-release-build` jobs failed three times at "Install musl-tools" - GitHub's runners could not resolve Ubuntu's archives - and went through on the fourth run, 17:02 UTC; the workflow unchanged; the go is used up) - design 83's server side (migration 0137), `mmsc-words`, design 84 the night band.
- **`launcher-v0.39.0-rc.5`: RELEASED 2026-11-11, 14:01 UTC, on 3ffc28ab** (the user's go "Do it!" and their approval of the `release` environment; versionCode 390005; the go is used up) - the kiosk-root fix, design 78 step 1 (the light mode row hidden), design 83's launcher half.
- **Design 84, the PWA's night band on every page (F2b): APPROVED 2026-11-11 09:51 UTC, MERGED 13:25 UTC, RELEASED in `server-v0.74.0` 17:12 UTC** (the user skipped the LAN iPhone walk, 13:40 UTC: "Må vi teste en hel testserver for PWA nå? Gir det mening?" - instead, once their own server has updated, four checks: the seam at the top of the installed app, the pull-down's light gap, a rule saved with the keyboard up, a long page scrolled; findings go into 0.74.1. the 320 px cut "Låseskjerm og PIN-ko…" beside the picture ACCEPTED by the user 2026-11-11: "Det er greit å evt måtte kutte lange headers ref 320px") (the user, after clicking through the LAN lab: "Jeg liker f2b! Sett den i produksjon!"). What was chosen, as the lab's `f2b/` shows it: a night band on every page with the round Vibb mark and the page's name beside it, the page's actions on the band with the bell last, the content a light sheet with rounded top corners; the band scrolls away with the page, and stays in place only on the editor pages, where "Lagre" sits on it; on a page under a tab a back chevron takes the mark's place. What goes: the "vibb" wordmark, the large title, the back link's visible parent name, the editors' own sticky bar. Server only (templates, `style.css`, the PWA's scripts, the shell tests), no API change. DESIGN WRITTEN 2026-11-11 10:15 UTC (`docs/design/84-pwa-night-band.md` on `pwa-band` 6c85a3fc, pushed as a backup, not merged: one partial `partials/band.html` with four macros, 49 of 54 templates get the band, the auth pages and `/lock` stay, one merge of about 64 files); DESIGN QA DONE 10:35 UTC: no High; six Mediums in the note's text (how a composed or conditional back href reaches the macro, the lab's five first-header selectors, the push banner's spacing, `jstest/locate.test.js`, two source scans that prove little, a spike and local checks before CI) and six Lows; the note revised (revision 2, `pwa-band` 48f4bd74, on origin as a backup) and the one revision check passed 10:52 UTC (all nine must-change items taken; five small corrections go with the build); **IN BUILD since 10:52 UTC** (an implementer on `pwa-band-1`, O1 as (c); BUILT 12:45 UTC (`pwa-band-1` 19b2582f, on origin; 49 templates with the band, O1 (c) as `band::phone`; 426 Chromium loads at 390/320 px with no sideways scroll; a save's scroll place kept on the editors; the Rust tests only in CI - Server CI dispatched 12:45 UTC); CODE QA DONE 12:58 UTC (one High - a test that would fail: `band.html`'s bare class words in the moved-templates scan - and three Lows, all FIXED by me 12:58 UTC, `pwa-band-1` 359fa6ba; Server CI GREEN on it); MERGED 13:25 UTC (`pwa-band-1` 359fa6ba); THE USER'S iPHONE WALK (§8) on a LAN build is next, then the tag question; the user 13:12 UTC: the next server tag carries the night band ("vil ha alt b med nattbåndet") - after its CI, the merge, main's CI and the user's iPhone walk (§8) on a LAN build; the tag itself still waits for their go. Asked of the user 12:45 UTC: at 320 px "Låseskjerm og PIN-koder" reads "Låseskjerm og PIN-ko…" beside the picture and the cut name; its cargo builds wait while design 78's emulator pass runs; the Rust tests run only in CI by dispatch, after it), then code QA and the merge. TWO POINTS ASKED OF THE USER 10:35 UTC with pictures from the lab's pages - no release and no iPhone walk before the answers: O1 below (now three options: no name / the name at the right, which cuts the title when the name is long / the phone's round picture at the right) and O2, a long phone name on the band - DECIDED by the user 10:38 UTC: cut with an ellipsis ("Langt telefonnavn kan trygt kuttes, det er et kjempe-edge case"). O1 DECIDED by the user 10:55 UTC: (c), the phone's small round picture at the band's right on the ten phone pages ("Enig i c!"); told to the implementer at once (always shown, the title gives way; at 320 px "Låseskjerm og PIN-koder" loses a letter or two - told to the user). Told, no question: the sign-in, 2FA, change-password and lock pages keep today's look; the band's title does not grow with 200 % text; one open point asked of the user 10:15 UTC, the build does not wait for it - O1: with the back link's name only spoken, nothing on a phone's ten pages says whose page it is; the architect recommends the phone's name at the band's right end. In design since 09:51 UTC (an architect writes `docs/design/84-pwa-night-band.md` from the lab; then one design QA, the build, code QA, Server CI by dispatch, the merge). Beside design 78's build (the launcher), which keeps running. A server release for it needs the user's word on the tag: the same release would carry design 83's server half and `mmsc-words` (asked).
- **A page task as the kiosk's root after the launcher's process is killed (seen in design 78's `jelly36` pass, 2026-11-11): APPROVED 12:27 UTC (the user: "Ja takk, please fix"), MERGED 13:03 UTC** (`kiosk-root` 246277a3) (branch `kiosk-root`). Under the kiosk, with Kalender's task alive when the launcher's process is killed, Kalender's task becomes the lock task's root: (1) Back and the page's back button do nothing (`Not finishing task in lock task mode`; Home works) - probably on main before design 78; (2) a change of look then clears every locked task and lands the kid on Home, not kid Settings. Both fixed, with the same check for the other own-task pages (`.messages`, `.call`), and run on `jelly36`. Cause: AOSP removes an activity that dies in front with no saved state; Home's record went, a new Home task joined the locked tasks last, and the stopped page task became the root. Fix at the cause: `android:stateNotNeeded="true"` on `HomeActivity` (as Launcher3), pinned by `HomeFrontTest`; on `jelly36` both findings reproduced on main and gone with the fix (eight kills: Home's record restarted in its own task, still the root; Back leaves Kalender and Meldinger; a change of look keeps kid Settings in front; `LOCKED` throughout); nothing visible and no gesture changed; `docs/notes/launcher/kiosk-escapes-and-updates.md`, `docs/testing/emulator.md` §6ai. Left: a Home crash loop (3 launches in a minute while not visible) still takes AOSP's old path.
- **The fingerprint while our lock face is lit (Android's lock) - the read of AOSP 12-16: DONE 2026-11-11 10:28 UTC** (`docs/review/fingerprint-over-the-glance-2026-11-11.md`; the user: "vi kan jo undersøke nr 3 som du sier, sett en agent på det"). The answer is no: `requestFingerprintAuthOnOccludingApp` is called only by SystemUI's own wallet, nothing reaches it from an app or a Device Owner; the one real switch (`config_fingerprint_listen_on_occluding_activity_packages`, 14-qpr1 on) belongs to whoever builds the system image. A fingerprint read by the launcher itself is not offered (it must never unlock, and as a mere "a finger is here" it is worse than the swipe). Nothing is built. What stock Android gives is the power key: a press with the enrolled finger kept on it turns the screen off, the keyguard listens from that moment and replays the match as wake-and-unlock - a device check, under "Waiting on the user or a device check".
- **The MMSC question's words in the PWA are too many (the user, 2026-11-11 08:21 UTC, on the emulator pass's pictures: "må nesten ha litt mindre wordy meldinger der? enormt mye tekst ... Kan den forenkles?") - APPROVED 08:22 UTC ("Ja takk juster ordlyden"), MERGED 2026-11-11 09:04 UTC (`mmsc-words` 50e8c034; Server CI on the branch green, run 42988374714: fmt, the tests, clippy, both images' smoke), IN NO RELEASE YET.** The row now asks "Hente bilder fra {host}?", names the picture ("Bildemelding fra {name} {time}.") and warns in orange only for an address Android's list does not have for the SIM ("Ukjent adresse for {operator}. Tillat bare hvis {name} virkelig sendte et bilde da."); what the list says and how long an allow lasts are folded under "Mer" after the buttons (`style.css?v=92`). Not in the approved wording, told to the user: `warn_unnamed` ("Ukjent adresse for SIM-kortets operatør. ...") when the SIM gives no operator name, so the bare MCC/MNC digits never stand in the warning. Noted, untouched: every question's fold is named "Mer"; an operator name ending in a full stop gives ".."; a removed contact's name is its number. SEEN IN A BROWSER 2026-11-11 09:46 UTC (main's binary on a throwaway server, Chromium at 390 and 320 px, light and dark: a listed and an unlisted address, "Mer" closed and open, no sideways scroll); three screenshots sent to the user. Not seen on an emulator. The inbox row's words (the emulator pass's finding 2) were offered in the same breath and not answered: not in this round.
- **Design 83's emulator scenarios - RUN 2026-11-11 on `handy36` (merged 07:14 UTC, `mmsc-emu` d17962d0, `docs/testing/emulator.md` §6v "design 83"; main's debug launcher, the server built from main with a throwaway database, `fake-mmsc.py` and the stand-in, the PWA through the host's Playwright).** Seen: the whole chain - a push kept with no GET, the report 2.1-2.6 s later (once 16.4 s, cause not looked into), the question under "Bildeadresser" with Android's five names and the warning, "Tillat" -> the phone's fetch 0.8-1.75 s later and the picture in the thread; the hint (no warning line, "Ikke tillat", a second push asks nothing); another port its own question; "Fjern" and "Tillat likevel"; a process kill between push and allow; an expiry between ("Bildet er ikke lenger tilgjengelig"); a stranger in the callback window (kept, no row, no question); the row kept across a reboot; a 253-character host at 320 and 360 CSS px wraps inside the card, both buttons on screen, no sideways scroll. Not run: the SIM's operator changing (the AVD cannot; the tests stand for it), the event's web push to a parent's phone. Unproven until a real SIM: what an operator's notification names, the network of the fetch, redirects, a real WAP push (design 77 step 0). §6v's recipe was corrected in three places (push ids already in the kept data, the fake's `?to=` for the own number, the question's heading). `handy36` restored and verified. The findings - all as designed, none blocking - are under "Asked for, scope not settled yet".
- **Design 83, the MMSC host list without a hand-kept table - APPROVED 2026-11-10, MERGED 2026-11-11 00:20 UTC (main d0f3fddc), IN NO RELEASE YET** (`mmsc-hosts`, 2c09ca5c: bc84646e the generator `scripts/mmsc-hosts.py` and the generated `server/testdata/mmsc_hosts.txt`, 8f12017b server + launcher + both `sms_contract.json` copies with migration 0137, aae5ca72 the docs, then the code QA's fixes d3d78b71, 34afc912, ffdba6f3 and the review `docs/review/qa-83-code.md` in 2c09ca5c; design `docs/design/83-mmsc-hosts.md` revision 2, `docs/review/qa-83-design.md`). Proven: CI on main's merge commit d0f3fddc green 2026-11-11 00:47 UTC (`launcher-build` and `server-build` ran; music untouched, its build skipped); the launcher on the branch 2448/0 with the QA's two surviving mutations caught; the server on GitHub for exactly 2c09ca5c (Server CI run 42927005410: fmt, 1865 tests, `clippy --all-targets`) - the server's test crate could not be compiled on the build machine that night (rustc killed for memory, on main too), so no server test ran locally. NOT proven: nothing of it has run on an emulator or a phone (`docs/testing/emulator.md` §6v's "parent's allow" scenarios are in the queue, before a release carries it), and step 0 on a real SIM (design 77 §9, "Design 83's gate, step by step") decides whether any address may ever go unasked. As built: the list ships with every entry a hint (`UNASKED = "none"`), so every SIM's first picture address is asked once on the Calls page (`#mmsc`, answers under "Bildeadresser"; at most 4 open questions per phone, 8 allowed per phone and operator, 32 declined per phone, an open question leaves after 30 days); an unlisted picture is kept, never fetched, and shown with the existing "Bildet kunne ikke hentes" bubble; no new kid-facing string. The code QA found no High and no way to fetch from an unlisted address. Accepted as built and told to the user: two parents answering differently - the last answer wins, both ways. The QA's H1 was the owner's choice; taken as the strict variant by the standing rule "no insecure fallbacks" and told to the user 2026-11-10: only an operator's internal names and private addresses from Android's list are trusted unasked, a public name from the list is a hint in the parent's prompt. And a gate: until design 77 step 0 on a real SIM shows the platform fetches on the MMS network alone, the file is generated with every entry a hint, so every SIM asks for its first picture's address once (the user: "jeg kan jo ikke hardcode alle forskjellige operatlrers MMS adresse? I norge kan det jo være mange forskjellige?", and to the proposal "OK, det høres bra ut"). Design 77 D10 keeps the host check before a received picture is fetched; the list stops being a compiled-in table with a server release per operator: a generated starting set from Android's public APN list (a pinned script), and a host outside it reported by the phone and allowed once per SIM by the parent in the PWA. Until a release carries it every received picture reads "Bilde kan ikke hentes"; sending is not gated. A real MMS both ways on the Jelly Star (design 77 step 0) is still the proof.
- **A failed picture's "Prøv igjen" sent its text alone and dropped the picture (in rc.3) - FOUND 2026-11-10 in `bubble-time`'s emulator look, FIXED, MERGED 2026-11-10** (`retry-picture`: 85e9800f the retry, 01938e0a the compose bar, 73ac58b7 the docs; launcher only; the coordinator read the diff; the emulator check on `jelly36` with the fake MMSC, `docs/testing/emulator.md` §6ag, on main plus the branch - 2444/0). As built and seen there: the retry sends the failed message's own pictures with its text (one POST with the JPEG and the text), a picture alone too; two quick taps send once; the failed message goes only when the accepted one carries the same text and pictures; with "Bilder i meldinger" switched off since the failure nothing is sent, the failed message stays and the kid reads the existing "Dette bildet kan ikke sendes"; a failed text retries as before. **The compose bar with a picture over the keyboard**, measured on `jelly36`: main's build had the field's last line and the send button 34 px under the keyboard with a picture and five lines of text; now the picture's preview gives way with four and five lines (96 x 85 and 96 x 63 dp) and the field and the send button are whole in every case; keyboard down nothing changed, and the five panels under the header sit pixel for pixel where they did. **The Settings gate's PIN dialog** (reported from `handy36` as having no room for its error line): on `jelly36` both the "no code set" reason (three lines) and "Feil kode. Prøv igjen." are whole - it was `handy36`'s 136 px status bar; the longer lines (one try left, the lockout) were not raised. Seen and not built: after a refused retry the line reads "Ikke sendt" without the offer until the thread is read again (`retryOffered` asks the text gate alone); a failed reaction's retry sends its words as plain text. NOT proven until the Jelly Star: the phone's own keyboard and its height, a real carrier's MMSC and refusal, two taps of a finger.
- **A pause of all restrictions could leave the kiosk stuck on Android's "App is not available" - FOUND 2026-11-10 in design 82's emulator pass, REPRODUCED on main (1 stuck of 11 starts), FIXED, MERGED 2026-11-10** (`pause-hang`: a735acf7 the fix, d9786b03 the docs; launcher only; the coordinator read the diff; on the branch 2440/0, and twenty starts from Settings and three from the PWA on the fixed build all clean, every end back in the kiosk; `docs/testing/emulator.md` §6af). The cause is a timing race inside Android's `setLockTaskPackages(admin, [])`: it restarts Home in the same call, while lock task still reads LOCKED with the app block set and an empty list, so Home's start became the system's blocked screen, which then locked the phone to itself (not a refused Home start as first read). A second race on main too, 1 of 23 starts: the restarted Home resumed before the stored kiosk switch went off and asked for lock task with nothing allow-listed - Android's screen-pinning request in the middle of a pause. As built: the kiosk's end writes the features without the app block first, then the stored switch off, then the empty list; a failed emptying puts both back; the way into the kiosk is unchanged, so the block is back whenever the kiosk is. To see on the phone: after a pause is started from Settings, Settings now often stays in front (Back goes to Home) instead of Home coming up by itself - both orders are safe. On the emulator the stuck screen's own OK got out without a reboot (seen once; the Home key does nothing). The proof is the order in the system's lock-task log, not the count: at 1 in 10, twenty clean starts happen by luck about one time in eight. NOT proven until the Jelly Star: whether either race happens there and how often, that OK gets a real phone out, how Settings staying in front reads in the hand. The same pass showed the buzz for a second message on the emulator (the vibrator service recorded one 40 ms play per message outside the 10 s gap, none inside it or at Off, and the screen stayed asleep for the second) - the emulator has no motor, so the feel is the phone's to show.
- **`server-v0.73.0`: RELEASED 2026-11-10, 17:56 UTC, on b548fa47** (the user's go "5 go" and their approval of the `release` environment; tagged 17:30 UTC; the go is used up) - design 82's server side: migrations 0135 and 0136, Meldinger's row on Apps and on the Lock Screen page, the buzz switch.
- **A buzz for every new message on a locked phone, not only when a row is gained - APPROVED 2026-11-10 ("1. enig"), MERGED 2026-11-10 (`buzz-every-message`, 4e631e3a, main 92530147; the launcher suite on main 2439/0; launcher only; the coordinator read the diff; shown on the emulator 2026-11-10 for SMS, `docs/testing/emulator.md` §6af: the vibrator service recorded the second message's 40 ms play with the screen left asleep, none inside the 10 s gap or at Off; NOT felt on a phone; unproven until then: what Element X posts for a second message (a quiet update or a count that does not grow gives no buzz), a `MessagingStyle` at its history cap (no growing count, so no buzz until the row is read), and W3's own open points on the Jelly Star)**: design 82's W3 buzzes when the lock gains a row, so a second message in a thread whose row is already there gave none (the emulator pass, §6ad). As approved: one 40 ms buzz for every new message at Name or Text with the switch on, the 10 s gap and every other gate kept (unlocked, Silent, Do Not Disturb, a rule's silence, a call). Launcher only, S. As written: Meldinger buzzes for every received message the lock shows; another app's row buzzes when its notification's message count grew (an edit or a post without a growing count gives none - what Element X posts for a second message is unchecked); a reaction shows nothing on the lock and buzzes nothing there. The wake ("Vekk skjermen ved varsler") is not part of the word and is unchanged: only a gained row wakes, a second message in a row already there does not - told to the user 2026-11-10, theirs to say if it should follow the buzz.
- **A message's time under its bubble, not inside it - ASKED 2026-11-10** (the user: "Skal vi skrive tidspunktet inne i snakkeboblen, eller heller i liten tekst under? jeg synes noe ala det siste jeg sa gir mer mening"), **APPROVED 2026-11-10 once per run ("per rykk er bra"), MERGED 2026-11-10** (main 4070ec9d, the launcher suite there 2436/0; `bubble-time`: 15f37355 and 42ae1ed4 - the emulator look, `docs/testing/emulator.md` §6ae, passed everything it could show and found one fault of the round, fixed: a time alone was clickable; not shown there: "Sender …" on screen, a carrier's late delivery report; the spacing and the cut row's 26 dp move are for the Jelly Star)**  (as built: a run is consecutive messages of one side, each at most 5 minutes after the one before it, ended by the other side, a new day or a clock set back; "Sender … · 10:47", "Sendt · 10:47", "Levert · 10:47"; a failed or uncertain send's red line stays as it was, without a time - the user agreed 2026-11-10 ("3 enig"); TalkBack hears the time in every bubble's description as before; found and not built: the last bubble cut by the list's lower edge moves the rows above it by the line's height when a message joins its run - with the reaction chip's Low below): today every bubble carries its clock time inside (`item_message_bubble.xml`), and the kid's own newest bubble has the delivery line ("Levert") under it. As approved: not under every bubble but once per run - under the last bubble of a run from the same side within a few minutes, joined with the delivery line on the kid's own ("Levert · 10:47"), so a thread of short messages does not grow a line per bubble. Launcher only.
- **`server-v0.72.1`: RELEASED 2026-11-10, 12:05 UTC, on a7b7544b** (the user's go "2 ok push den da" and their approval of the `release` environment; the go is used up) - it carries the iOS 27 fix and Devices' night band (A4). **The user after it, 2026-11-10: "ser bra ut nå"** - the app removed and added again, the pull-to-refresh glitch gone. Closed.
- **The iOS 27 PWA fix on the user's iPhone - CLOSED 2026-11-10 (the user on 0.72.1: "ser bra ut nå")** (merged 2026-11-10, pushed as b95783f0, server suite 1849 green; see Done): the user on A3, 2026-11-10: "Synes a 3 ser ok ut, men lurer på om vi skulle trukket den natt-bakgrunnen pittelitt nedover der også, som gir pittelitt mer luft under vibb logoen ... ubalansert at den svarte kanten er såpass nærme underkanten av vibb logoen, og så er det såpass mye luft ned til Devices" - on the lab as **A4** (Devices' night band 6 pt further down: 11 pt under the mark, 6 pt to the title's box; the titles do not move). **The user's look, 2026-11-10:** "Ser fornuftig ut med mengden svart og luft mellom vibb og Devices nå" - and asked whether there is more black above the mark too: no, nothing above it moved (17 pt from the status bar to the mark since A2: the 12 pt strip and 5 pt of the bar; under it 5 -> 11 pt); a lower top was offered and declined ("vi kjører med dette": A4 as it is, the top stays). **MERGED 2026-11-10** (918efd1d, branch `title-band-6`: the rule, its shell test pin, the note, `style.css?v=91`; `cargo test shell` 27/0 before the merge); released in `server-v0.72.1` (2026-11-10). Landscape (QA L6): nothing wrong in landscape; only "når den er i horisontal visning og så snur jeg den opp i vanlig stilling ... ser jeg den tåkegreien på toppen litt kort øyeblikk før den forsvinner" - iOS's own fade for a moment while it re-reads the page's top after the turn; no fix from the page is known, left as it is unless the user asks. What was open: (1) their go for `server-v0.72.1` - given, released; (2) after it: remove the app from the Home Screen and add it again once, then check the real app's pull-to-refresh (the text and the vibb mark "glitcher litt" on the lab, probably the lab's full reload alone) and whether grey shows above the night band on a hard pull; (3) not checkable by them now: iOS 26 with `default`, and an app added on iOS 26 and then updated to iOS 27. - the user, 2026-11-10: "ser bra ut nå"; (3) stays unchecked.
- **Meldinger as one more app: its lock-screen notifications and its icon set where every other app's are - MERGED 2026-11-10 (5b7e21a1; on main after the merge: server 1859/0, fmt and clippy clean, launcher 2434/0; design 82, `meldinger-app`: 0eaa77ea the lock level Off / Name / Text with migration 0135, eac835a5 the Apps row with W1's name, the icon, the colour and W2's "On Home", 17b2feda W3's one buzz switch with migration 0136, 64a14269 the code QA's fix round, 0952a091 main merged in, docs afdfc79e; the user's go for both tags 2026-11-10, "5 go", the server first)** - the emulator pass 2026-11-10 (`docs/testing/emulator.md` §6ad, design 82 §14) passed every point it ran: both migrations on an upgraded database, an older launcher against the new server (the orange "update Vibb One" lines, gone after the new launcher's report), the Apps row, the Lock Screen row, every save in place, the rename / icon / colour without a launcher restart, "On Home" off and on and under a pause, the lock rows at each level and a level change while the lock shows, one 40 ms buzz on a locked phone and none with the switch off, unlocked, inside the 10 s gap, on Silent, under Do Not Disturb, under a time rule's silence and during a ringing call, no second vibration from the notification's own channel, the Recents label, the TalkBack labels by content description. **Not run:** L1 (a failed engine read drops the lock rows - one line, no test), the Recents label after a reset; S1's redirect was staged by hand. **Put to the user 2026-11-10, answered the same day:** the buzz comes only when a row is gained - a second message in a thread whose row is already on the lock gives none (as the note's §9 says); the user agreed to a buzz for every new message with the 10 s gap kept ("1. enig" - its own entry in the queue); while Google Messages is allowed too there are two rows named "Meldinger" until W1's rename - "2 greit", left. **Unproven until the Jelly Star:** whether the 40 ms buzz is felt and as one with nothing else, Android playing it from the background on the real phone, TalkBack by touch. **Found in the pass, not design 82's:** a pause of all restrictions that left the kiosk on Android's "App is not available" (its own entry in the queue). The history of the item: asked 2026-11-10, researched the same day (verified against the code), the answer "yes" and the scope put to the user; design 82 written, through design QA (no High, 4 Medium, 10 Low, all fourteen in the note, its §13) and merged 2026-11-10 (`docs/design/82-meldinger-as-an-app.md`); APPROVED 2026-11-10 with every recommendation (the user: "1 jeg er enig med deg i dine anebfalinger"): W1 a name - yes; W2 the On Home switch - yes; W3 one switch for everything on the lock, default off, one short buzz, no sound; §12's points 4-7 as written. built on `meldinger-app` (code QA 2026-11-10, one reviewer per side: no High, no Medium, nine Low, all fixed in 64a14269 - server 1859/0, launcher 2433/0 on the branch; 0eaa77ea step 1 the lock level, migration 0135; eac835a5 step 2 the icon with W1 and W2; 17b2feda step 3 W3, migration 0136; server tests 1859/0, launcher 2432/0 on the branch, no emulator yet; two Fable reviewers, one per side): W3 built default off without waiting for its step 0 (the emulator already showed a locked phone silent; on the Jelly Star the look is then the switch itself, the user told so); no launcher RC between the steps** (the user's words in the entry above the two before this one). What is there today: Meldinger is in neither per-app place - its lock row is its own hard-wired `GlanceSmsRow` read from the SMS engine (`badges/GlanceRows.kt`, `sms/SmsGlance.kt`; the launcher's own notifications are dropped from the per-app path), with one switch on Calls & Contacts (`sms_lock_text`: the text or the name and a count, never off); its Home tile is fixed code (`ui/home/HomeGridAdapter.kt`: teal, the chat glyph, `messages_title`); the server has no row for it. The per-app places: Lock Screen & PINs > Notifications (`device_lock_glance_apps`, Off / Name only / Full text, the policy's `lock_glance.apps`) and Device > Apps > Name and icon (`device_app_display`, the policy's `launcher_ui.app_display`). The core as proposed: one grammar-valid key for Meldinger; a Meldinger row in the Notifications group while SMS is "Styrt av Vibb" with Off (new) / Name only / Full text, seeded from today's switch by a migration, the old switch gone from Calls & Contacts and `call_policy.sms_lock_text` still sent for an older launcher; a Meldinger row on the Apps page with only the icon form (glyph and colour; no "App logo", Automatic = today's teal chat glyph); the launcher reads both for its lock row, the wake and the Home tile; a capability so the PWA knows the phone honours Off. **Asked of the user, not settled:** (1) a name for Meldinger too (the label is spelled in six places of the launcher); (2) the form's "on Home" switch as the answer to "hide Meldinger although there is history" (the SMS-app-visible entry above); (3) a buzz while locked - no app has one today (a locked phone is silent for every app), so "like the other apps" means silent; one buzz for a new message on a locked phone would be a new switch, for Meldinger alone or as a start of a per-app one. Design 82's shape (the architect, against the code): the key `me.vibb.builtin.messages`, its prefix reserved on both sides; the level stored as a `device_lock_glance_apps` row and sent as a new `lock_glance.sms` (`off`/`name`/`text`; not an `apps` entry, since absence from an older server could not mean Off), read fail closed; one capability `messages_app_v1`; the contract in `sms_contract.json`'s new `app` section; two build steps (the lock level, then the icon), core M-L. The note changes the buzz question: the launcher can buzz for every alerting row on the lock, not Meldinger alone, so W3's recommendation is one switch for everything on the lock (default off, one short buzz, no sound), after a look at what the Jelly Star does today. More put to the user by the note: the row shows only while SMS is "Styrt av Vibb"; the old switch becomes a link row; an older Vibb One shows name and count for Off, said on the row; Meldinger sits under Vibb One on the Apps page.
- **SMS app visible or not - answered 2026-11-10, verified against the code; the open point answered 2026-11-10: design 82's W2 "On Home", MERGED 2026-11-10 (the entry above)** ("man må kunne velge hvorvidt denne SMS appen er synlig i det hele tatt ... Men det gjør man kanskje automatisk om vi slår av SMS?"): yes - Home's Meldinger tile shows only while SMS is "Styrt av Vibb" with the role held, or while there is history to read (`messagesTileShown`, `ui/messages/MessagesModel.kt`); the phone book's Message button hides when SMS is off (`calls/MessageButtons.kt`). So "Av" or "Ikke styrt" on a phone that never had SMS in Vibb shows nothing. Open: a phone with old history keeps the tile, read-only (design 77 D6) - asked whether they want a switch that hides it even then. Answered 2026-11-10: yes, as design 82's W2 "On Home" (merged, the entry above).
- **A locked kiosk phone neither sounds nor buzzes for any notification - answered 2026-11-10: design 82's W3 (one switch for everything on the lock, default off), MERGED 2026-11-10 with design 82 (the entry above)** (seen 2026-11-10 in design 81's emulator pass, not changed): `featuresWhileLocked` takes `LOCK_TASK_FEATURE_NOTIFICATIONS` away while the PIN lock is LOCKED (design 10: no shade, no heads-up), and Android then mutes every notification's sound and vibration - the launcher's own messages and reactions and every other app's. On the emulator: locked, no effects; unlocked, the buzz played. Known since design 17 (QA #1), and the lock already plays its own sound for a VoIP call (`lock/VoipRinger.kt`) and a calendar reminder (`calendar/ReminderPinger.kt`); design 77's messages and design 81's reactions have no such ping of their own (verified against the code 2026-11-10: nothing under `sms/` or `ui/messages/` vibrates or plays). To decide before SMS is "Styrt av Vibb" on the kid's phone: leave it (a locked phone is quiet for messages), or the launcher buzzes/sounds itself for its own messages while locked (as it rings for calls); check on the Jelly Star in step 0 what a locked phone does today for Signal. The user, 2026-11-10: "hva mener du med launcherens egne meldinger egentlig? det er uklart for meg!" - explained in plain words the same day (the SMS/MMS and reactions arriving in Vibb One's own Meldinger app; the question is only whether a locked phone should buzz or sound when one arrives); still theirs to decide, the recommendation is yes (one buzz, no sound, nothing shown). **The user's answer, 2026-11-10:** "Jeg ønsker å styre notifications for sms på låst telefon helt likt som jeg gjør for de andre appene? Lar det seg gjøre? Så jeg kan bestemme på samme sted for andre apper om den får vise notifs, om den viser full tekst eller hva. Ønsker også å kunne tilpasse logoen slik som for andre apper." - see the entry "Meldinger as one more app" below; whether a locked phone buzzes for it follows from what that per-app place can say.
- **A "Ny melding" mark in a thread - MERGED 2026-11-10** (approved 2026-11-10, "3 ok!"; branch `thread-new-mark`: c907dcd3 the build, d1fc6b65 the code QA's fix round - six Low, five fixed -, cc0e143f the emulator pass's one fault and the focus after the tap, docs 70bd1173 and e857af33; launcher only, one Fable implementer): while the kid has scrolled up in a thread and a message arrives, a small calm mark at the list's lower edge says so ("Ny melding" / "Nye meldinger"); a tap goes to the end, and it leaves when the end is shown (the thread is then read, `threadReads`). No sound, no buzz of its own, nb + en, TalkBack. The emulator pass (`docs/testing/emulator.md` §6ac) passed every point it could run and found one fault: a drag that begins on the mark scrolled nothing - the mark sits where a thumb starts its way down. Fixed in cc0e143f (the mark hands the gesture to the list's own `onTouchEvent`; its tap stays a tap) with the keyboard's and TalkBack's focus after the tap, which fell to the header's back button; the re-check on the emulator passed all five points (rows move the same from the mark as beside it, no stuck touch state). `take()` keeps no `submitList(null)`: every real way in makes a new activity. **Shown to the user, theirs to decide (screenshots sent, no answer yet):** over a received bubble the pill has the bubble's own colour and covers part of its text until tapped (advised: a slightly lighter fill, or look on the phone first); the list's "new conversation" action reads "Ny melding" too (advised: leave it). **Unproven until the Jelly Star:** TalkBack by touch, the keyboard-up view, a real thumb's timing, a second finger, the focus when there is no compose bar. In no release until the next `launcher-v*` tag, which needs a new go.
- **Launcher batch `sms-follow-book-ground` - MERGED 2026-11-10** (b2f5ebad + the fix round ee9ef7a1, docs 99586836 and 4bcb9e20; light code QA no High / 1 Medium / 4 Low, all fixed; launcher unit tests 2428/0 on the branch; two emulator passes, `docs/testing/emulator.md` §6ab; goes into `launcher-v0.39.0-rc.3`). As built: a thread follows its newest message while its end is shown (the cause of the miss: `AsyncListDiffer` drops the commit callback of a list a newer one overtakes; the QA confirmed it in RecyclerView 1.4.0), scrolled up nothing moves and **the new message stays unread until the end is reached** (QA M1: it was marked read unseen; Home's badge and the list's dot say so, no mark in the thread itself), the kid's own send always goes to the end (a running fling is stopped); the phone book on `kid_ground` with the wallpaper code gone; the reaction buzz one 40 ms pulse (an install that already made the reactions channel keeps 80 ms until its data is cleared); and, not asked for, found in the pass: the phone book's first search hit no longer lies cut against the list's bottom when the list is shorter than a row (`scrollToPositionWithOffset(0, 0)`). **Not proven, the Jelly Star decides:** that the original miss is gone (main's build followed every burst on the AVD too); whether 40 ms is felt; the keyboard-up views at the real 36 px status bar. Open to the user: a "new message" mark in the thread while scrolled up (asked 2026-11-10). Ideas not built: Kid Settings is still on the wallpaper. (the user, 2026-11-10, after rc.2 on the phone): (a) **an SMS thread follows the newest message** - "i sms samtaler så er det ikke alltid at skjermen scroller ned om man sender en ny melding eller mottar en ny melding; det burde den ... med mindre man scroller oppover i historikken da; men når man er på bunnen allerede, så bør skjermen alltid følge med": at the bottom the list stays at the bottom through every change (a message in or out, a bubble growing, the keyboard, the compose field), scrolled up it stays where the kid is (today `ThreadActivity` scrolls only when the newest `seq` grows, and then also while the kid reads older messages); (b) **the phone book on a plain ground** - "bare lys/mørk bakgrunn akkurat som for kalender, er litt rotete med bildebakgrunn som launcher": `PhoneBookActivity` drops the kid's wallpaper (`showsSystemWallpaper`, `WallpaperGround`/`KidInk`) for the calendar screen's ground; (c) **the reaction buzz as one short tap** - "Reaksjonsvibrering vil jeg ha som en puls ... samme som" iOS: Apple publishes no length for its Tapback tap and none is invented here; today's single 80 ms pulse (`SmsNotifier.REACTION_VIBRATION`, in no release yet) becomes one 40 ms pulse, the nearest a notification channel's pattern gives on Android 13, and the user feels it on the Jelly Star (shorter or longer is then a new channel id). **As built (the implementer's report, 2026-11-10):** (a) the cause was found in RecyclerView's `AsyncListDiffer`: the scroll hung on one `submitList`'s commit callback, which never runs when a newer list overtakes it - and a new message is almost always followed at once by a second read (`markRead`, the send's own updates); now a pure rule (`threadFollows`/`threadAtEnd`/`threadEndGap`, `MessagesModel.kt`): following while the last bubble's bottom is within 8 dp of the end, every committed list and every layout then goes to the end, the kid's own send always does, scrolled up nothing moves. (b) the calendar screen has one ground, night (`@color/kid_ground`; the launcher has no light mode until design 78), so the phone book is night now, by the same resources; its rows were the calendar's tiles already. (c) one 40 ms pulse. Not built, the implementer's ideas: a quiet "Ny melding" pill for a message that arrives while the kid reads older ones; Kid Settings is now the one inner screen left on the wallpaper.
- **PWA on iOS 27: the top is wrong and the tab bar is cut off - MERGED 2026-11-10** (`pwa-ios27` c2a40178 + the fix round `pwa-ios27-b` 9ff6b07a, the coordinator read the round's diff; released in `server-v0.72.1` 2026-11-10; the app is removed and added again once, the user's part) (the user, 2026-11-10, on iOS 27.2 with server 0.72.0, the app removed and added again, with a screenshot: "det ble ikke bra i det hele tatt ... Skyggelinjen er der fortsatt, og nå ser vi ikke tab bar ... støtte det slik at det ble pent på flere ios utgaver ... den var bra på 26 ... researcher nøye"). What the screenshot shows: with 2026-11-09's `apple-mobile-web-app-status-bar-style` = `black` (79a6ef5c, out in 0.72.0) iOS 27 paints no black bar - the status bar is the light page colour with dark text, a grey-to-night fade lies over the top of the night header, and the page's bottom is below the screen by about the status bar's height (the tab bar half out of sight). So that fix made it worse and its two assumptions were wrong (an opaque bar; the 100vh shell being the screen below the bar). **Research done 2026-11-10 (read from WebKit's source and Bugzilla, not measured on a device):** (1) the top fade is UIKit's scroll pocket on the web view; WebKit hides it only when the page has a real `position: fixed`/`sticky` element at the top edge, at least 90 % of the width, with an opaque background (`WKWebView _updateHiddenScrollPocketEdges`, `LocalFrameView::fixedContainerEdges`) - our night strip is an absolute `body::before`, which never counts, so the status area takes the page's light background and the fade stays; (2) without `black-translucent` the page starts below the status bar while `100vh` is still the whole screen (WebKit 316008, open), so the `100vh` shell is one status bar too tall and the tab bar falls off the bottom; (3) Apple calls `black-translucent` deprecated and says to remove the key (WebKit 317153). **The fix, in build on branch `pwa-ios27`:** a real fixed night strip element first in every page's body instead of the pseudo-element; the installed app's shell height from the layout viewport, right under either bar (`min(100vh, 100% + the top inset)`); the bar style `default` for iOS 27 and later, `black-translucent` kept for Safari 26 and older (the one combination the user has seen good). **Not known until the iPhone shows it:** the clock's colour over a night strip in light mode, and iOS 26 with `default`. So a throwaway lab (four variants, outside the repo) is served on the LAN for the user's iPhone; nothing is merged before their check of it, and a `server-v0.72.1` tag needs their go. An app already on the Home Screen keeps its bar until it is removed and added again. **The user's lab check, 2026-11-10 (iPhone, iOS 27.2, light mode, variant A = the planned fix, a screenshot with the readout):** the status area is night with a white clock, no fade, the tab bar in place (screen 852, inner/dvh 793, 100vh 852, top inset 0, bottom 34, body 0..793, strip 0..12, tab bar 676..745) - "Lab a er nærmest". Two remarks: the night top is taller than on iOS 26 (the 12 px strip was added to main's 8 px: 12 pt more) - to do: main's top padding `max(8px + inset, strip)`, 4 pt more than iOS 26, on the lab as A2 for their second look; and a pull down showed the grey ground (the lab page had no pull-refresh.js, which takes that drag in the real app; A2 has it - if grey still shows there it is a real fault to fix). **The user's second look, 2026-11-10: "A2 ser bra ut!"** - so A2's top padding goes into the branch. Two more remarks: at the pull-to-refresh the text and the vibb mark "glitcher litt" (probably the lab alone: its server sends no-cache and reloads the whole page, the real app reloads in place with cached files - to check in the real app after the release); and "pittelitt mer luft mellom det svarte båndet og teksten som sier Devices ... så alle sidene har samme høyde på overskriften" - to do in the same round: the page title's top margin 4 -> 12 px on every page (one rule, so every tab's title sits at the same height), on the lab as A3 first. **Code QA of c2a40178 (2026-11-10): no High, no Medium.** Low, all into the fix round: L1 the test's Content-Length check can never fail; L2 the non-HTML pass-through (CSS bytes, an event stream) and a POST page through both rewrites are unpinned; L3 the auth page's min-height and the lock mark's margin are unpinned; L4 the lock mark's `100dvh` beside the body's `100%` - the iPhone's readout shows they agree (dvh 793 = inner 793), kept, the device fact goes into the comment; L5 a third-party iOS browser with a `Version/` token (EdgiOS, DuckDuckGo) would get `black-translucent` - also require no `...iOS/` product token; L6 the 12 px night band in iPhone landscape (no status bar there) - left as built, a device look (rotate the lab) decides. Then one QA verification, the merge, the full server suite once on main. A `server-v0.72.1` tag needs the user's go; then the app is removed and added again once. **The fix round as merged (9ff6b07a):** main's top padding `max(8px + inset, --top-strip)` (the sticky editor bar needed no change, measured in headless Chromium for a browser tab, iOS 26 and iOS 27); `.page-title`'s top margin 12 px, and the device page's avatar header (`.summary-head`) with it in the merge commit so it sits at the titles' height; L1-L5 and the nits fixed, L6 left as built; pwa.md has the device readout and what is not known yet; `style.css?v=90`. One gap kept: an iOS browser that sends Safari's `Version/` without an `iOS/` token (DuckDuckGo) is still read as Safari.
- **Spotify audiobooks supported for real** (the user, 2026-11-09: "vi kan jo like godt støtte det i vibb når vi teknisk kan det, og så får det være andre sin sak å ha det abonnementet eller ikke"): an audiobook's chapters listed without the spike (the flag goes), the free "sample" chapter left out, the music app's tracker taking a later item of our own list as ours instead of UNREACHABLE after 10 s, a clear message when Spotify won't play the book (no listening time), and a PWA line that a child's account needs audiobook hours (Premium Family: "Audiobooks+ for Plan Members") - server + music app, M, **MERGED 2026-11-09** (`spotify-audiobooks` 8f8f0d7d + b0c3121b, light review by the coordinator; server filtered 333/0, music 754/0; migration 0131 `music_listings.spotify_audiobook`; `music_library.json` regenerated; the sample recognised by its whole title (sample/smakebit/preview) - Spotify's chapter object has no field for it; chapter uri `spotify:episode:<id>`; the kid's line "Lydboka trenger lyttetid"). Ships with the next server and music tags; no emulator check yet (the fake's `--audiobook`/`--no-book-time` are ready).
- **Spotify audiobooks: the chapters listed under the spike** (the 0.69.1 line on the user's server, 2026-11-09: `entry=10 total=10 items=10 nulls=0 other=10 other_type="chapter"`, entry 8 the same): with `SPOTIFY_AUDIOBOOK_SPIKE=1` a show's `type: "chapter"` items are listed so the phone can try one; without it such a show gets the audiobook sentence instead of `no_items` - server, S, **MERGED 2026-11-09**, built by the coordinator on `spotify-chapters` (7ee8d562), tagged as server 0.69.2 on the user's word ("Do it"). The user's test 2026-11-09: the phone's web player decodes and plays (Widevine key loaded, audio delivered), but Spotify skipped the book and played only its free "sample" chapter - on Premium Family only the plan manager gets audiobook hours (Spotify's support pages; the kid needs the "Audiobooks+ for Plan Members" add-on), confirmed by the user ("Åja, jeg ser det nå!"). Offered if the kid gets access: the tracker accepts a later item of our list as ours (today: UNREACHABLE after 10 s), and "sample" chapters left out. Not approved.
- **The lock's calendar lines: the Neste line keeps its time, the reminder row names its calendar** **MERGED 2026-11-09** (`glance-calendar-lines` 86600ab2 + the spoken wording, light review by the coordinator; targeted launcher tests 158/0; launcher only) (the user, 2026-11-09: the time lost to the ellipsis; "kun prikk har ikke så stor verdi"; no horizon): "Neste 17:30: G2017 Trening…" / "I morgen 09:00: …" (only the title is cut); the reminder row's second line "17:30 · <the calendar's display name>" (a parent-named link is that name), TalkBack "…, kalenderen <name>".
- **Spotify: a show that lists nothing says why in the log** **MERGED 2026-11-09** (`spotify-show-diag` b0e970e5, light review by the coordinator; server 1769/0; server only; tagged as server 0.69.1 on the user's word "Kan du ikke bare tage når den diagnostic log line er klar") (the user's "Håpets reise" `/show/` link: `no_items` with no `unplayable` line): one info line "a Spotify show listed nothing" with `total items nulls other other_type unplayable reason playable next market` (`spotify::Tally`, `Page.shape`); Check Now goes the same way.
- **The contact sheet's black band at the bottom** **MERGED 2026-11-09** (`sheet-edge` 29f5f70b, light review by the coordinator with the before/after screenshots; launcher 2102/0; launcher only) (the user, 2026-11-09: "ser litt rart ut at det er svart på bunnen vel?"): `KidSheetDialog` is no longer a floating window, so the sheet's surface runs behind the navigation bar, and `KidSheet.kt`'s `setUpKidSheet` pads the content by the bar's inset - the contact sheet and the emergency sheet, gesture and 3-button navigation.
- **MET's new minimum caching per location** **MERGED 2026-11-07** (`met-floor` 22250417 + 85274193, light review by the coordinator; server 1767/0; server only): the forecast's floor per cell - 30 min inside the Nordic region, 3 h outside, after every success (200, 203, 304, a cut body), a longer `Expires` still winning; the region is 15 per-country boxes (`weather::NORDIC_BOXES`: Norway, Sweden, Finland, Denmark with Bornholm, Svalbard, Jan Mayen, Iceland, the Faroes; the Baltics, Russia, Shetland, Greenland, Germany and Poland out - compliance first, unsure = 3 h); Nowcast unchanged. (MET's mail to API users, 2026-11-05, which the user forwarded: Locationforecast's caching now at least 30 min in the Nordic region, at least 3 h for the rest of the world, the response's cache headers an absolute minimum; the same mail explains 4 Nov's 502/503 and cut 200 bodies - a HAProxy bug on MET's side, so the "cold-point 503 with Retry-After: 5" seen that evening was most likely the incident, not MET's normal behaviour; the user: "vi kan spørre oftere enn hver 3 time utenfor Norden, for forskjellige locations?" - yes, per location): `weather_cache`'s floor (30 min today, `max(Expires, 30 min)`) becomes per cell: 30 min inside the Nordic region, 3 h outside (a bounding box, named and tested), after every successful fetch, a cut body and a 304 included; Nowcast is Nordic only and unchanged. Server only, S.
- **An emergency call leaves Attention** **MERGED 2026-11-07** (`emergency-attention` 337474c7, light review by the coordinator; server 1745/0; server only) (the user, 2026-11-07: "etter en emergency call så blir det en permanent attention melding på brukerens device meny i pwa? Virker ikke som den går bort! Det burde den jo etter litt, det ligger jo allerede et push varsel i innboks for dette?"): the Attention row (`CallReport::problems`, every reader) goes once any parent has read the call's `emergency_call` inbox event (`parent_events::emergency_call_read`, matched on the event's dedup key = the call's instant), at the latest 24 h after the call (`EMERGENCY_ATTENTION_HOURS`); no matching event (the switch off, a phone clock ahead) = the 24 h alone; the calls page keeps its warning for 7 days.
- **Pin the server's certificate issuer in the launcher (design 76 steps 1+2)** **MERGED 2026-11-07** (`server-issuer-pin-2` ae3006fa, built clean of the first branch's committed test key; security QA (Opus) `docs/review/qa-76-code.md` 0/1/1/5/4, fix round f69ecce9, independent verification 3ac7ae9f 0/0/0/3/5, its fixes 2b0e99ce; migration 0129 `device_server_tls`, contract `server_tls_report.json`, `PHONE_RECOVERY_URL`, policy `server_tls` with `recovery_url`; merged tree server 1764/0, launcher 2099/0). **Before the release: step 0 on the user's server** (the curl answered `ok` 2026-11-08; the user opens the tailnet binding only when a phone needs the recovery, `PHONE_RECOVERY_URL` unset) - the server starts switching phones on after 72 h; the remote branch `server-issuer-pin` (held a throwaway test key) gone from GitHub (2026-11-09). Owed on the emulator: §6's rows (watching then on, mitmproxy with the check off/on - lock, calls, 112 -, the tailnet recovery end to end, a QR re-scan). (The user, 2026-11-04: "Ja gjør det!", after the eMudhra follow-up; the user runs Caddy -> Let's Encrypt, ZeroSSL as Caddy's fallback, a new key at each renewal): at the provisioning QR Vibb One remembers the root of the server's chain and always also accepts Caddy's defaults (ISRG Root X1/X2, ZeroSSL's USERTrust root); any other issuer (emSign) is refused on the https path; the tailnet path is exempt (WireGuard); the parent changes it in Vibb One Settings with the parent code. Design first, security QA by Opus. The user's answers 2026-11-04: all via Caddy; the parent's extra roots / allow list / self-check deferred ("glem steg 2 per nå"); no-QR recovery only via the server's tailnet address (no on-phone accept button). Design 76: built-in roots (ISRG X1/X2/YR/YE, USERTrust RSA/ECC, Sectigo R46/E46); phones report what they see; the server turns `server_tls.enforce` on per phone after 72 h over at least three days (Wi-Fi and mobile); the parent's one off button. Step 0 (the real chain, the emulator's root) owed in the next emulator session.
- **MET: a quick retry after a 5xx; the "unreadable" cell** **MERGED 2026-11-07** (`met-retry` 4acf065c, code QA (Sonnet) `docs/review/qa-met-retry.md` 0/2/5/3, fix round 084f55de; server 1744/0; server only): 500/502/503/504 retried inside the fetch - `Retry-After` 0-60 s honoured (seconds or a date), else 5 s then 15 s, at most 3 requests, then the old 5-60 min backoff (a longer `Retry-After` its floor, capped at 2 h); the server-wide lock released while waiting; a phone with stored hours answered at once; `unreadable` was MET's cache serving bodies cut short (E9 §3) - the whole hours of a cut body are kept as `cut` without a validator until `Expires`, and every window past the last stored hour is unknown, not dry; `unreadable` now logs the length. (The user's server log: `status_5xx` for the Vollen cell several times a day; the user's curl 2026-11-04 against `lat=59.80&lon=10.48`: 503 ×4 in ~40 ms each, then 200 after ~1 s; once made, 200 from MET's Varnish (age, expires) - MET's edge answers 503 fast while the point is cold, then serves it; our fetch is scheduled right after `Expires`, when a rarely asked point is cold, so it often lands on the 503 and then waits 5+ min): after a 5xx retry the forecast within seconds (e.g. 5 s, 15 s, 30 s, honouring MET's `Retry-After` - checked 2026-11-04 from the dev VM on a cold point (64.53/12.92): `HTTP/2 503`, `retry-after: 5`, a 285-byte body, then 200 two seconds later; today the server reads `Retry-After` only on a 429) before the 5-60 min backoff, within MET's terms. MET's status-code page (docs.api.met.no/doc/StatusCodes.html, read 2026-11-04) documents neither the cold-point 503 nor `Retry-After` - it calls 503 "not operational or ... not configured" - so the fix may not depend on it: honour a `Retry-After` when sent (seconds or a date, capped at ~60 s), fall back to the fixed short ladder when not, at most ~3 quick tries per fetch, then the old backoff; and find why the Mountain View cell (the emulator's 37.42/-122.08) and E9's area answered `unreadable` (a body our reader doesn't take). Server only.
- **The evening weather card: "I kveld" | "I morgen"** **MERGED 2026-11-04** (`evening-card` bb7233bc, code QA (Sonnet) `docs/review/qa-evening-card.md` 0/1/3/3, fix round d9f0fcb6 (M1 the words built, L1 the evening symbol from the span's own hours); server 1734/0, launcher 2081/0; "I kveld" from 18:00 to midnight, a `tonight` period on each `hours[]` entry, `weather.json` contract rewritten; a "maybe" evening counts as not dry; the user 2026-11-04: "1 OK, enig med deg. 2. Tonight høres bra ut") (the user, 2026-11-04: "Enig i å blande kortet som du sier, og fortsatt vise kvelden nå"; design 75 switched the card and the words to tomorrow at 18:00, which felt early): from 18:00 to midnight the card shows two groups split by a line: "I kveld" once (the rest of this evening, marked as now) and "I morgen" once over Morgen | Ettermiddag (the user: write each once, no card title), the foot tomorrow's low/high and dark; the words cover the rest of the evening first and tomorrow only when the evening is dry. Mockup https://claude.ai/artifact/8LVfZ2DHQhSaAPfS3isZsR. Both dirs + `weather.json` contract. Owed on the emulator: the foot at font scale 1.3-2.0, the divider's contrast.
- **Ending a pause on the phone reaches the PWA** **MERGED 2026-11-04** (`pause-end-report` 245758e2, code QA (Sonnet) `docs/review/qa-pause-end-report.md` 93eca25f, fix round b9bea0ad; server 1732/0, launcher 2078/0; cause: the phone-side end asked no sync, the report waited for the 30-min backstop) (the user, 2026-11-04: "når jeg slår av en full pause fra restrictions via home screen på appen, så plukker ikke PWA opp idet hele tatt at pausen er av? den maser videre om at det er pause"): the phone syncs at once when a pause ends (the parent's own ends - Home's and the notification's "Lock again", the Settings switch - always; a timer, screen-on or boot relock when it cleared); the server reads a paused report as over 2 h after it was made (`DeviceStatus::paused_at`, `RESTRICTIONS_PAUSE_MAX_MS`), the 2 h pinned on both sides by `server/testdata/restrictions_pause.json`; Quick actions says "Ifølge telefonens siste rapport, {age}." when the newest report is 35 min or older. Left: a pause re-entered while the phone is offline shows only with its next report.
- **Push never lost silently** **MERGED 2026-11-04** (`push-never-silent` fbc79364, security QA (Opus) `docs/review/qa-push-never-silent.md` 0/0/1/6/8, fix round 0835133f, verified 32493de5 with R-1 fixed in 5708e230; migration 0128 `push_removed`; server only; cargo 1728/0, node 321/0) (the user: "å miste push sub er kritisk"; the parent's iPhone lost its subscription silently, likely "Kun passkey" on or three refused app-lock unlocks): the PWA never unsubscribes a subscription made for the server's key and re-posts it (`POST /notifications/subscriptions/sync`, full auth + unlocked + CSRF); it re-subscribes on open when permission is granted, else a banner "Varsler er av på denne telefonen" (Slå på / Skjul); a deliberate Off (Turn Off, Hide, an active row's Remove) holds by `push_removed` (never pruned) and the HttpOnly `__Host-handy-push-off` cookie until a tap; the device making a password / 2FA / passkey removal / "Kun passkey" change keeps its own row (kept inside the change's transaction; Sign Out Everywhere and the lockout keep none); /logout without an endpoint drops the session's noted row; a gone row warns on Devices and raises a `push_off` inbox event pushed to the remaining devices; every deletion is in the security log (`push_subscriptions_cleared`, `push_unsubscribed`); the app lock no longer counts server-caused refusals (coalesced "challenge ×N" lines); the lock screen's Sign Out asks first; gone rows lose their keys and stay 180 days. Owed on the iPhone: whether iOS lets the PWA subscribe without a tap (else the banner shows).
- **A per-app "media player on the lock" switch; the E9b leftovers** **MERGED 2026-11-04** (`lock-media-switch` 628a7864, light QA `docs/review/qa-lock-media-switch.md` 0/0/4/6, fix round 8501befb; migration 0127 `device_lock_media_apps`; policy `lock_media_apps` in both dirs; server 1711/0, launcher 2075/0) (the user: "En bryter per app i pwa, tastefully implementert"): Device › Apps › an app's Tillatelser has "Vis på låseskjermen som mediespiller" (off by default; only for allowed, installed third-party apps; "on" refused otherwise, "off" always taken, stale rows pruned; the help says the title and picture show on the lock before the code); the phone counts a switched-on app as a lock media app (`lockMediaApp(category, browser, chosen)`); a read error sends `[]`. E9b: the refusal's example IP no longer breaks at a dot; the tile hides previous/next only when a session's actions are known and lack the skip. Not checked on a device: AntennaPod with the switch on (next emulator session / the Jelly Star).
- **Round S-i18n slice 5: the server's own rule names go to the phone blank** **MERGED 2026-11-04** (`pwa-i18n-5` 86a986e9, light QA `docs/review/qa-s-i18n-5-code.md` 0/0/4/4, fix round 3a76f528; server only, no contract change, policy keys unchanged; cargo 1708/0). Design 70 §7: `time_rules::phone_name` sends `name: ""` for a SERVER_NAMES name (the suffix stripped first) and drops a typed " (morning)"/" (evening)"; the PWA words them (`shown_name`, `device.time.rule.default.<n>`, `device.time.rule.morning`) in the lists, the editor, Pause a rule, lifts, the badge and Diagnostics (by `active_rule_id`); a save of the shown name in either language keeps the stored name; the log words a rule's name at read time (`security::Detail::rule`; older rows stay English until they age out). The S-i18n round is complete.
- **E9 fixes: the lock tile drives third-party players, the whole server-URL refusal, glance sizes, back labels** **MERGED 2026-11-04** (`e9-fixes` d107e9b3 + ae0f2a8a, QA fix round edd4255c + 7c0652ce; launcher 2072/0, server 1706/0; light QA `docs/review/qa-e9-fixes.md` 0/1/3/6, all fixed; emulator check `docs/testing/2026-11-04-e9b-run.md` 5/5 pass): non-Vibb sessions get the tile's play/pause/next/previous as media-button keys (skips only when advertised, else the transport control; play/pause a 1 s transport fallback that only the newest tap may fire - `tileMediaKey`/`tileFallback`), the refusal in a dialog (Settings edit, Sync now, the QR scanner), the own-line alarm glyph follows a font change, "Nødsamtale" shrinks instead of breaking (`glanceEmergencyScale`), no soft hyphen in spoken weather, "Du har nyeste versjon", KidHeader back labels per caller ("Tilbake til Innstillinger", "Tilbake"), no chevron on read-only sound rows, the PWA wallpaper picker in even columns (style.css ?v=81). Still for the Jelly Star: the tile with Spotify, YouTube and NRK.
- **Emulator session E9: the 2026-11-04 merges on `jelly36`** **DONE 2026-11-04** (`e9-check` a4c766ab, run doc `docs/testing/2026-11-04-e9-run.md`; built from main cd2e62f3; F-Droid Fossify Music / AntennaPod / Fennec as third-party players): the lock media tile, the calmer glance with I morgen and the 18:00/noon alarm edges, no place name (MET only), https-only in the debug build, the kid's sound picker and the PWA at 390 px in nb pass; the music notification SECRET can't be judged on the emulator (Android's lock never showed a player) - left for the Jelly Star. Findings M1, M2 and L1-L8 in the queue ("E9 fixes").
- **The kid picks ringtone and message sound in Kid Settings** **MERGED 2026-11-04** (`kid-sounds` 24b832fe + fix round 6ba6013b; launcher only; 2060/0; light QA `docs/review/qa-kid-sounds.md` 1 H (the preview's listeners called MediaPlayer.stop(): focus never returned, an error could crash) / 2 M / 4 L, fixed but L4). The user approved the mockup https://claude.ai/artifact/51wRYDkVCPi2XRyLeLhtH9: "Ja den ser grei ut". Rows Ringetone and Meldingslyd under the parent's sound bit; our own picker (`KidSoundPickerActivity`, not exported; the system picker banned by `KidScreensEscapeTest`): the phone's built-in sounds only, a tap plays (USAGE_MEDIA, only with audio focus) and selects, no "Ingen"; saved via `RingtoneManager.setActualDefaultRingtoneUri` through the WRITE_SETTINGS grant, read back; read-only without the grant. **Not built: the vibration rows** - since Android 13 call vibration follows only the hidden `RING_VIBRATION_INTENSITY` (`VIBRATE_WHEN_RINGING` unread), notification vibration `NOTIFICATION_VIBRATION_INTENSITY`, both refused for third-party apps; the user: "Jeg er fornøyd så lenge telefonen alltid vibrerer" - Android's own Ring/Notification vibration settings in `docs/setup/android-settings.md`. Device checks owed: the list on the Jelly Star, the preview while silent, a real call and message, whether Element X and Google Messages follow the default sound.
- **No host actions from the server at all** **MERGED 2026-11-04** (`no-host-actions` 40d120e0 deploy + 09ce4337 server + ebcfcbd7 docs, fix round 216e8654 + 8c07044d; no migration; cargo 1707/0, node 309/0; branch CI 41818307422 green; security QA `docs/review/qa-no-host-actions.md` 0 H / 1 M (the systemd restore command pasted a backup's name unquoted into a shell) / 5 L, all fixed). The user: "PWA skal ikke ha rettigheter til å gjøre noenting som helst på host vel??" / "jeg ønsker å gjøre ting på host selv, og jeg kjører docker og watchtower". Gone: the root watcher (path unit, actions.sh, watcher.sh), scheduler.sh and backup_sync.sh with their timers, the routes for self-update, restart, apt, Tailscale update, reboot, drive format, schedules, the apt/tailscale reads; update.sh's `remove_host_helpers` removes them from an old install; the server deletes old host files in data/ at start. Software Updates: versions only + how to update (Docker/Watchtower; systemd: the fixed project command, a warning when `SERVER_RELEASE_REPO` differs). systemd restore: an SSH command (shown only for plain backup names, single-quoted). Ships as **server-v0.67.0** (the docs say 0.67.0).
- **S-M1: the phone takes only an https server URL (http only to a Tailscale IP, only through the tailnet)** **MERGED 2026-11-04** (`https-only` d73c39a6 + fix round 12079671; contract `server/testdata/server_url_vectors.json` (65 rows) in both dirs; launcher 2058/0; branch CI server 41816689328 (cargo 1717/0), launcher 41816694093 and 41820080847 (the real tsnet.aar + the Go test); security QA `docs/review/qa-https-only.md` 1 H (tsnet's Loopback proxy fell back to an OS socket for a non-peer 100.x) / 3 L, all fixed, H1 re-checked: fixed). Security review S-M1 / M11 closed; it had sat in the proposed order and never reached the queue (told to the user). The user: "jeg foreslår at vi kun godtar https flat out, med mindre det er en tailnet IP direkte? Med advarsel?". The phone: cleartext off app-wide (network security config; the one exception the made-up `tailnet-server.vibb.invalid` of the tailnet client), every server client through `ServerTransport.toServer`, http to a tailnet IP only through our own SOCKS5 in tsembed (netstack, current peers only), never direct; a refused address sends nothing (the bearer never leaves). The server: `check_server_url` (https / tailnet http with a warning / refused) for the saved address and `PHONE_SERVER_URL`. **Release note:** release the server first; phones with a stored plain-http address that isn't a Tailscale IP (a LAN address or `http://<name>.ts.net`) stop reaching the server once Vibb One updates - type the https address in Vibb One's Settings › Server URL (parent code); a phone without a parent code needs a factory reset and a new setup QR. Device checks owed: tailnet http path, a non-peer refused with nothing on Wi-Fi, the debug emulator loop on http://10.0.2.2.
- **The weather card without a place name; no Kartverket lookup** **MERGED 2026-11-04** (`weather-no-place` 8b5a3b05; migration 0126 `DROP TABLE IF EXISTS weather_places`; contract `weather.json` without `place`, both copies; cargo 1714/0, launcher 2052/0, node 309/0; light QA `docs/review/qa-weather-no-place.md` 0 H / 0 M / 3 L - L1 design 75's superseded note and L2 DEPLOY's pre-migration copy line done in the merge). The user: "Vi dropper navnet" (they had not understood that design 75 D13 asked Kartverket once per cell: "Det skjønte jeg ikke og det ønsker jeg ikke"). Only MET receives a cell's centre now; the card shows low/high + "Mørkt kl."; an old launcher reads the answer (place null) and re-pulls once per process; a new launcher ignores an old server's place.
- **A calmer lock glance and "I morgen"; the lock's browser test; the music notification SECRET** **MERGED 2026-11-04** (`lock-glance-calm` 7854af15 + d2122590 + 089004d2; launcher 2051/0, music 1249/0; light QA `docs/review/qa-lock-glance-calm.md` 0 H / 1 M / 6 L). The user approved the mockups https://claude.ai/artifact/RABFjpjmCbxFwbnzdwGXb8: "Jeg synes de ser bra ut". (A) date + weather on one line, the weather the tap target; (B) the weather on its own line only with words, never a cut date; (C) the open card right under its line, pushing the rest down; (D) the wake-up alarm (rings before noon the next day) only from 18:00, " · <glyph> 07:00" on the date line or its own line, until it rings; "I morgen: <first timed event> 09:00" once today's timed events have started and from 18:00 (`glanceNext`, `GLANCE_EVENING_HOUR`). The browser probe: https ∩ http. Music: `SecretMediaNotification` sets the playback notification VISIBILITY_SECRET (ships with a `music-v` tag) - QA M1: it acts only under a secure system keyguard (the android face), and SystemUI's lock player may follow the session and the setting instead; **not device-checked - try on the Jelly Star in both faces**. Left Lows: L1 the card gap after a minute-tick alarm line, L2 the own-line glyph size, L4 `measure(UNSPECIFIED)` on live views - cosmetic, for the emulator session. Emulator/device checks owed: the date-line widths at font 1.0/1.3/2.0, the glyph baseline, the 18:00/noon edges with the screen on, TalkBack.
- **Round S-i18n slice 4: the words Rust builds, in nb and en** **MERGED 2026-11-04** (`pwa-i18n-4` 80a44202 + b1827b1a, fix round 80ac40be + c07d008e; server only, one launcher string (`wallpaper_navy` "Mørkeblå"); cargo 1716/0, node 309/0; code QA `docs/review/qa-s-i18n-4-code.md` 0 H / 2 M / 10 L + nb word fixes, all taken). Design 70 §6/§8: summaries, relative times, dates, plurals, error bodies and helper status lines worded per reader (466 keys per catalog, 3827 each); `i18n::number` (decimal comma); `tests::seeded_family` + `tests::i18n_pages::nb_pages_have_no_english` over ~75 pages, every flash code and two refused forms (fails on English words, unfilled `{param}`); `no_status_answers_with_literal_words`. nb terms: "Oppdateringssperre", "Ga vei", «Varseltall på apper», "Neste/Siste oppstart", "Play Butikk". Also the user's bug "Sett en regel på pause…" running out of its button (2026-11-04): the quick buttons' labels wrap to two lines (`.act-label`, style.css ?v=79). Left on purpose: raw technical details behind ⓘ, server-made rule names (slice 5).
- **Round L4b - the launcher's security Lows, the rest** **MERGED 2026-11-04** (`launcher-sec-lows-b` d3ca4925 S-L9, 6789adea S-L10 + Leave Tailnet, 95e3ee94 P-L2/P-L3, 4537a0f9 S-L5, fix round cb5976a3; migrations 0124 `device_policy.bluetooth_any_device`, 0125 `device_status.tailnet_joined`; cargo 1711/0, launcher 2047/0; security QA `docs/review/qa-launcher-sec-lows-b.md` 0 H / 2 M / 4 L, all fixed). The user: "Kjør den backlog listen du har!". S-L9: DoT connections pooled (10 s idle, 4 per server, a kept connection's timeout is silence). S-L10: a Go crash (EXIT_SELF status 2, signal, native) within 10 min of a connect counts; `leave_tailnet` command (contract `leave_tailnet.json`, both dirs) - the phone refuses when its server URL is a tailnet IP; the Advanced row shows only when the phone reports `tailnet_joined` (the user: "Hva gjør forlat tailnettet egentlig?"). P-L2/P-L3: only `locate` forces a fix, Location switched back off after it (also after a death mid-fix), no fix older than the interval, Off clears the cached fix. S-L5: pairing only for audio devices (never a phone/computer class) unless Device › Home's "Alle Bluetooth-enheter" or the pause/override. S-L4 still waits for a device capture. Device checks owed: DoT on Wi-Fi/mobile switches, Leave on a real tailnet, Location back off, Bluetooth with a friend's phone and a speaker.
- **The lock's media tile: music, video and browser only; every app paused before a suspension** **MERGED 2026-11-04** (`lock-media-apps-2` 71c75448; launcher only; 2039 tests green; light QA `docs/review/qa-lock-media-apps-2.md`: 0 H, 2 M, 3 L). The user: "1. Absolutt ikke! Dette er bare for musikk apper og evt videoer og musikk som spilles av i browser. 2 ja!". `lockMediaApp`: `ApplicationInfo.category` AUDIO or VIDEO, or a browser (resolves VIEW `https://example.invalid/` BROWSABLE); everything else (messengers, undefined category) never shows. `sessionsToPause`: every app's playing sessions paused before a suspension, one lazy `getActiveSessions` per enforcement pass; the budget's Vibb music never paused. QA M1 (a real player may declare no category and never show - check Spotify, YouTube, NRK, Storytel on the Jelly Star; the fix then a parent/server override, not a looser rule) and M2 (tighten the browser test: also resolve `http://`) - M2 queued as a follow-up, M1 a device check before the next launcher tag.
- **The lock's media controls for other apps** **MERGED 2026-11-04** (`lock-media-apps` 272621cb + cfd2d0d4; launcher only; 2038 tests green; code QA `docs/review/qa-lock-media-apps.md`: 0 H, 1 M (main-thread binder reads per playback update - the callbacks' own state/metadata now, the cover only on new metadata, the lock's lookup only at start/session-list change/suspension change, other apps watched only while the lock listens), 6 L (L1 the full check at the tap, L2 bidi/format chars + combining runs, L3 the position capped, L5 no headset window for other apps; L4/L6 noted)). The user: "Legg mediekontoller for andre apper i køen, men kun for lock screen!". The PIN lock's media tile (`LockSessionPick.lockTilePick`) shows the session of any app that is allowed, not hidden, not our own and not suspended now; playing beats paused, Vibb music first among equals; another app's tile only while it plays or within 10 min of its own last state change; transport controls only, nothing opens from the tile; untrusted text cleaned and cut to 120 chars, art at most 512 px. Home's Now Playing card unchanged (Vibb music by name and signer). Owed: an emulator check with a real player, Spotify on the Jelly Star. Asked the user 2026-11-04: a messenger's voice-message session (title, play without the code) - fine, or only apps marked as music/audio?; and whether to pause every app's playback before a time rule or the budget suspends it (today only Vibb music's, `pauseBeforeSuspend`).
- **The PWA opens on Devices after 10 min away** **MERGED 2026-11-03** (`pwa-home-after-away` f994eab2 + f63e8795; server only; node 309/0, cargo 1707/0 on the branch; light QA `docs/review/qa-pwa-home-after-away.md`: 0 H, 1 M (a due lock still kept `next` = the page - app-lock.js's own handler runs first; app-lock.js now picks `/` itself after >10 min hidden, `handyLock.homeAfter` is the one constant), 5 L (L5 tested, L2-L4 left, L1 not a regression)). The user, asking whether the passkey lock broke the return to Devices (it never did: since 2026-10-24 a return after >1 min reloads in place): "10 minutter er bra, legg det inn ved leilighet". Back after more than 10 min hidden -> `/`; 1-10 min keeps the reload in place; never while a save, a POST answer, an open `details` or an edited field would be lost; a due lock -> `/lock?next=/`; `/lock` untouched. `reconnect.js?v=10`, `app-lock.js?v=4`. Left: a browser that runs a hidden page's timers can lock while hidden, and then `next` stays the page left (pwa.md).
- **Contact photos keep a downscaled original for a re-crop** **MERGED 2026-11-03** (`photo-original` 7f7bc4e7 + 07c79cd7 + 4302f4fc; migration 0123, after lock-weather's 0122; server only, the phones still get only the square; cargo 1707/0, node 304/0 locally; QA `docs/review/qa-photo-original.md` 8c3c9315: 0 H, 1 M, 8 L, all fixed - EXIF/GPS/XMP/ICC verified gone). The user: "Ja takk". The server keeps a re-encoded JPEG original (≤2048 px long, ≤~3.1 MP, no metadata) and the parent's view; "Adjust crop" reopens it at the saved view and can widen again; `/contact-photos/original/{hash}` is parent-only, no-store, never on the phones' listener; prune, recovery and backups carry originals. Also: every photo refusal (`photo.error.*`) is now nb + en (contacts, wallpapers, music covers, the phone's picture). Ships in server-v0.66.0.
- **Weather on the lock glance (design 75)** **MERGED 2026-11-03** (`lock-weather`: step 1 4fa6bfdb + 625fbfe4 + fixes 5f02ddbf/84ba14ba/c9cdba28, step 2 c255176f + e7c8b418 + fixes 072550b0, E8 fixes dd612482 + e0ad1163; migration 0122; CI on the merged head 7b7293fb green: server 41719662170, launcher 41719668285, music 41719672598; code QA qa-75-code-server/-launcher/-step2, emulator run docs/testing/2026-11-03-e8-run.md on jelly36). The user, from the idea round: "en YR-type widget som kan vise dagens vær ... basert på hvor man er geografisk". The server asks MET Locationforecast/Nowcast for a ~2 km cell (User-Agent with the repo, `MET_CONTACT`), Kartverket for the place, works out 9 word rules and civil dusk; the phone shows yr's symbol + degrees + words by the date (own line on a narrow phone), a tap folds out the day in three with "Mørkt kl." and the nowcast line ("Regn om ca. 25 min" / "Opphold om ca. N min", the user: "Nei vi beholder opphold"). Position: a fresh fix, else the parent's zone (step 3, the last-known cell, dropped by the user). Settings: a "Vær" group on Device › Låseskjerm (the user: "legge vær options under lock screen menyen"; `/devices/{id}/weather` redirects), and the lock page's "Nylig bruk" is gone (the user: "helt unødvendig"; single wrong Parent PIN tries now show nowhere in the PWA, lockouts still do). Location Off erases the weather caches. yr symbols CC BY 4.0 credited (NOTICE.md, the licences screen). Ships in server-v0.66.0 + launcher-v0.38.0-rc.5.
- **Home's contacts: a white ring and a soft shadow (mockup A)** **MERGED 2026-11-03** (`home-contact-ring` cdb934fb + 6922ba0d; launcher only; 2009 tests green; light QA `docs/review/qa-home-contact-ring.md` e0a2b016, 0 H / 0 M / 4 L, all fixed; emulator E8 on jelly36 passed: ring + shadow on Vibb night, dark and light wallpapers, the hairline only on light ones, 112 keeps its red ring only, the phone book unchanged, the pressed shade). The user, with a screenshot of "Pappa" over a busy photo: "Er det noe vi kunne gjort for å få kontakter til å se litt bedre ut på hjemskjermen?" → mockups VKioLzytiUa5UGgVFwrrF9, "foreslår A"; then "kan vi risikere at den forsvinner helt i en veldig lys bakgrunn?" → a 1 dp dark hairline (black ~28 %) outside the ring on a light wallpaper (`InkChoice.darkInk`). Home's 76 dp avatar (photo and initial) gets a 3 dp white ring inside its edge and 4 dp elevation (the phone book keeps 2 dp). Ships in launcher-v0.38.0-rc.5.
- **The parent picks a contact photo's crop in the PWA** **MERGED 2026-11-03** (`photo-crop` 8a39441d + e74716c5 + 9551722c + 10d6ab69 + 386cc5b0; server only, the phones and the API unchanged; node 298/0, CI 41697900889 green (cargo 1674/0); QA `docs/review/qa-photo-crop.md` b4fe3120: 1 H (the admin CSP refused the picked photo's blob: URL, so the cropper never opened - `blob:` added to img-src, pinned by a test), 2 M, 8 L, all fixed). The user: "jeg ønsker at forelderen selv velger utsnittet i PWA" (with Home's ring A). Picking a photo on Device › Calls opens a round cropper (`static/photo-crop.js`: drag, pinch, slider, wheel, keys; the circle as the phone shows it); Save draws a ≤512 px JPEG and submits it through today's route, so scroll-restore keeps the row in place; "Adjust crop" refines the stored 512 px square, its zoom capped so the square stays ≥230 px. Without JS, canvas or DataTransfer the plain upload as before. Keeping a downscaled original for a re-crop: approved, in build (see the queue). Ships in the next server release.
- **The lock's Face ID starts on its own again** **MERGED 2026-11-03** (`lock-auto` f8cc9c13 + 9fbe6a7d, Server 0.65.1 3ef3a914; server only; CI 41679586335 green: cargo 1674/0, node 279/0; code QA `docs/review/qa-lock-auto.md` 52bb0173, 0 H / 2 M / 2 L, all fixed; no re-check round, the user: "trenger vi så mye tester for dette?"). The user, after trying server-v0.65.0: "den nye trykk hvor du vil på skjermen egentlig bare legger til et trykk til ... bedre å trigge login med last used passkey uten trykk først". The Pust lock asks with the hinted passkey as soon as it shows (once per showing; Safari 16+ gives a tab one gesture-free get, a later refusal stays quiet), so iOS's own passkey sheet is the only tap; the full-screen tap asks again with every passkey. Research 2026-11-03: iOS's Continue sheet can't be skipped by a web page (Apple's documented flow; conditional mediation needs a tapped field; immediate mediation is Chrome-only). Fixes: the tap's challenge stays live across showings (M1), reconnect.js leaves /lock alone (M2, no second sheet on Android), a failed automatic try stays quiet (L1), no /lock?next=/lock (L2). Ships in server-v0.65.1.
- **The PWA lock screen on the night ground ("Pust")** **MERGED 2026-11-03** (`lock-pust` 451b0ebf + 9cfdab09 + 0e707b45 + fb5d08b9; server only; cargo 1674/0, node 272/0, `pwa-startup-images.py --selftest` ok; code QA (Opus) 0/1/4 then a revision check 0/1/2, all fixed) - the user picked mockup A: "La oss kjøre pust dal, alt A. Men husk at du må bruke det ordentlig pust ikonet vårt". /lock is night in both schemes with the real Vibb mark breathing (inline `partials/vibb_breath.html`, written by `launcher/scripts/vibb-icon.py` from palchrb/vibb pi/art; CSS keyframes with the Pust SVG's timing, still under reduced motion), "Vibb er låst", "Trykk hvor som helst for å låse opp" and Logg ut. **No automatic prompt any more** (iPhone's Continue sheet): the whole screen is one button whose tap starts Face ID in-gesture with the passkey this browser used last; after a cancel, every passkey; a try the page left (backgrounded) is replaced by the next tap; a late answer from a replaced try is ignored. **The hand-off from the iOS start-up splash** (the user: "sklir over i den modusen etter at original loading screen er passert"): the mark at the splash's exact size, centre and resting state (one source, `mark_share` in `pwa_startup_screens.json`), the words fading in; short screens and landscape fall back to the flow; a page that becomes due shows night with the resting mark (`app-lock.js` `?v=3`). **Owed: the user's iPhone check** (open the PWA cold, lock and come back, cancel Face ID, background the app mid-prompt).
- **S-i18n slice 3e - Settings, accounts and sign-in in nb and en; every template moved** **MERGED 2026-11-03** (`pwa-i18n-3e-a` 78c31184 + `pwa-i18n-3e-b` cf768483, built in parallel; server only; 3315 keys per catalog (460 + 49 added); light code QA of both: no High/Medium - keys, params, field values, status codes, escaping and the sign-in `?lang=` whitelist checked; one nb word fixed (Web Filter's backup reason "kan ikke nå serveren din der telefonen er")): part A the Settings hub, Notifications and their settings, Time Rules with a rule's and Daily Limit's pages, Web Filter and its log, Wallpapers, Software Updates, Backups and Phone Setup, with the handlers' titles, flashes, refusals and error bodies; part B the sign-in pages (language from `?lang=`, `PWA_DEFAULT_LANG`, then `Accept-Language`), 2FA, Account, the Security Log, the passkey partials and Device › Calendar. `MOVED` lists every template and `i18n::tests::every_template_is_moved` keeps it so. Visible besides the words: "Mark 1 as read" for one; an unnamed wallpaper upload is named in the parent's language. Left for slice 4: `PhotoError::message`, `backups::human_size`, a push failure's raw text, the render-failure 500 of Time Rules, built-in wallpaper and list names (data).
- **S-i18n slice 3d - the Music tab in nb and en, and the Musikk tab faster** **MERGED 2026-11-03** (`pwa-i18n-3d` d8712d36 + 86f500bb + fix round 34d9a7e8; server only; 652 keys per catalog; cargo 1664/0, node 263/0 on the branch; light code QA: no behaviour findings, the nb wording and the Add-link/Import refusals fixed): 13 templates moved; the covers `loading="lazy" decoding="async"` with their sizes and `/music-covers/{hash}` cached `private, max-age=31536000, immutable` (the user's timing: Musikk 640 ms vs ~110 elsewhere); the page-timing line wraps. Left for slice 4: `check_link`, `Skip::reason`, the sweep/Storytel/Spotify error sentences, storage sizes, the summary's Music row, the stored default names.
- **The app lock asks for Face ID at once, with the passkey this device used last** (the user, 2026-11-03: "sånn som min bank-app funker"; "Bygg det for låseskjermen alene") **MERGED 2026-11-03** (`lock-faceid` bd05dd61; server only; cargo 1666/0, node 266/0; the server side read by me - the hint only narrows within the parent's own passkeys here, a 1 KiB body limit): `/lock` starts the modal passkey prompt on load (once per load, one more on a bfcache return; on visibility if loaded hidden), the button asks with all the parent's passkeys; the id used is kept in localStorage `handy-lock-passkey`. Sign-in pages unchanged. **The user's iPhone check** (installed PWA + Safari + after a passkey removal): the agent's list in `docs/notes/server/connectivity.md` "The app lock".
- **The PWA faster: Software Updates never waits on GitHub, gzip on the admin listener, plain Security Log rows** (the user, 2026-11-03, iPhone page timing; "ville heller hatt mer plaine innføringer") **MERGED 2026-11-03** (`updates-cache` 313acd82, c9e981dc, 5e19b4d8; server only; cargo 1665/0; screenshot checked by me): the newest server tag cached in `AppState` (startup, hourly in the background, 5 min after a failure; Update Now still reads fresh), the OS checks run in parallel; text responses over 1 KiB gzip'd (never the `no_store_path` pages with secrets, images, ranges, the phone API; SameSite=Strict + header CSRF, so BREACH has no lever); every security-log event one plain row (label, time; who · IP · detail; the exact time) - the page 193 KB -> 3.6 KB on the wire.
- **"Skjulte apper" in Kid Settings - the kid shows again what the kid hid** (the user, 2026-11-03) **MERGED 2026-11-03** (`kid-unhide` 0e0b77c8; launcher only; 2007/0; the Home filter refactor read by me - equivalent): a card under Kid Settings' switches, only while the kid has hidden an app Home would otherwise show (`homeApps(.., EXCLUSIVE)`, the same filter as Home's grid; the parent's `on_home: false` apps never listed outside a window); tapping an app shows it again at once. Ships in the next launcher RC. Device check: the agent's list in `docs/notes/launcher/kid-settings.md`.
- **Small round small-d MERGED 2026-11-03** (`small-d` 6443c5a6; server only; cargo 1663/0, node 261/0; screenshots checked by me; ships in `server-v0.63.1`, the user: "Gjør det"): (1) the tab bar as mockup A - the stray `.tab-icon` peach rule from 269ff8aa removed, the other tabs grey outlines again (style.css v74); (2) Device › Apps shows the name the kid sees (per-phone, else the catalog's, else the phone's label) with the app's own name first in the sub-line; (3) "På Hjem"'s long line behind an (i); (4) a CalDAV 403 reads "Kontoen har ikke tilgang til kalenderne" (401 keeps "password refused"); (5) "Legg til fra familien" on a phone's Calls page - a family contact not on this phone, added with every switch on, last in the order (the user, 2026-11-03).
- **E7's findings fixed; R2, CalDAV write-back, the alarm fix, Stille and the emergency callback MERGED 2026-11-02** (`e7-integration` 8f89bbf0 into main; emulator sessions E7, E7b, E7c). (1) `android-r2` 4c3f46ca + E7 M2/L3 3a0a7ae6 + E7b M-B1 3fab1114: with the kiosk off and Android's lock the glance takes no lock task; a policy's kiosk or face change re-runs the lock's entry check at once; the glance stops a lock task only when it is its root (no restart loop). (2) `caldav-write` (design 72b, migration 0120) + E7 L4-L6 72cc9b11: the kid's own events written to the marked CalDAV calendar both ways; a 403/507 stop retried at the next set and fetch; the page counts only writes still to make. (3) `rule-silence` (migration 0121, with `alarm-dismiss`) + E7 M1 68896e67 + QA e7198fc3 + E7b L1 fc411a62: a per-rule "Stille" by our own AutomaticZenRule; an allowed alarm app's ringing screen always reachable under a rule (re-sent each screen-on, the time-rule screen hands it the front, a "Slå av alarmen" row, a Clock row while the notification listener is off); the card reads "Du kan bare ringe nødnumre nå.". (4) `emergency-callback` 56466f4b + 20b8e5e2 + bc32d3ee + 79b98d77 (E7 H1, main's call code): 112's callback gets through a no-calls rule - the launcher records the emergency calls it places (10 min, the hour once the call connected 20 s, never shortened) and Telecom's emergency-callback mark on a SIM call lets it through (recorded and told). Gates on the integration branch: cargo 1660/0, node 261/0, launcher 2001/0 (E7b); CI on the merge.
- **Passkeys step 3 - the app lock "Lås med Face ID" (design 74)** **MERGED 2026-11-02** (`passkeys-3` 2177a962 + fix round 7658c641; server only, no migration; cargo 1611/0 on the merge, jstest 261/0; security code QA in two parts - server 0/0/2/4, client 0/1/2/5 - fixed and revision-checked mergeable, `docs/review/qa-passkeys-3-*-code.md`): Av / 1 / 5 / 15 min / 1 time per browser (a switch that turns on at 5 min when off, a picker when on), every change behind a passkey; the server locks (303 to `/lock?next=`, 423 otherwise, an exempt list walked by a test), `used_at` moved only by a tap, a save or the beacon; the lock screen shows nothing private, unlocks with this parent's own passkey, back where you were (`next` local only, also after a passkey sign-in); `no-store` on every page of a lock-on session so Back can't show a cached page; a new sign-in starts unlocked. Owed: manual QA on the iPhone PWA (veil, bfcache, Face ID in standalone), Android Chrome, Mac Safari. Ships in the next server release.
- **Kalender usable during a time rule; one usable app or Kalender opens straight after the PIN; Exceptions offer only allowed apps** (the user, 2026-11-02) **MERGED 2026-11-02** (`calendar-in-rules` 0ad8f174 + fix round f516925e; server + launcher, migration 0119, policy `time_policy.rules[].calendar_allowed` in both directories; cargo 1593/0, launcher 1955/0; code QA 0/1/4/5 `docs/review/qa-calendar-in-rules-code.md`, fixed: the "Ny avtale" fields' text actions shut (`NoTextActions`, shared with the re-enrol dialog), a stale hand-over ignored, the Kalender row on the Home tile's own condition): "Vibb-kalender" first in "Apper som fortsatt virker" (rules and bedtime), a "Kalender" row on the time-rule screen, the day view, event page and "Ny avtale" open under a rule that allows it; exactly one usable app or Kalender opens after the unlock (the phone book never auto-opens - the coordinator's call); the list offers a phone's allowed apps (everyone rules: the union), saved ones outside stay ticked with a note. Told the user: "Ny avtale" brings up the keyboard (Gboard's own screens) under such a rule. Emulator check owed (the combined session with R2 and CalDAV). Ships in the next server release and launcher RC.
- **Passkeys step 2 - Kun passkey, the password-free sign-in page, the ways back (design 74)** **MERGED 2026-11-02** (`passkeys-2` c04fd933 + fix round 92d4ccb8; server only, migration 0118; cargo 1592/0, jstest 231/0; security code QA in two parts - flows 0/1/3/3, limiter 0/0/4/4 - fixed and revision-checked mergeable, `docs/review/qa-passkeys-2-flows-code.md`, `qa-passkeys-2-limiter-code.md`): the switch with 1 passkey and the "two recommended" warning, password + TOTP gone while on (`'!passkey'`), the dummy hash at /login, the last passkey kept, Reset/`reset-2fa`/`reset-sign-in` clearing it, `/account/password-sign-in` back to passwords, /login with only the passkey button when no account can use a password; a passkey-only actor's re-auth takes only the passkey (`NeedsPasskey` counts nothing); IPv6 keyed per /56, tailnet addresses each their own key, a valid passkey signs in through a ban on a shared address (/login stays banned), the sign-in pool evicts from the busiest neighbourhood. **Release note:** running IPv6 bans are lifted at the upgrade. Accepted residual (RL1): someone actively sitting on the parent's own address can hold the banned slot - switch network or the shell. Manual QA owed with step 1's. Ships in the next server release.
- **Passkeys step 1 - passkeys on Konto og foreldre, sign-in and re-auth by passkey (design 74)** (the user, 2026-11-01/02; mockup approved) **MERGED 2026-11-02** (`passkeys-1` 5cccaeaa + fix round 8ce305da; server only, migration 0116; cargo 1568/0, jstest 224/0; security code QA in two parts - verifier 0/2/3/7, web flows 0/3/7/7 - both fixed and revision-checked mergeable, `docs/review/qa-passkeys-1-verifier-code.md`, `qa-passkeys-1-web-code.md`): own verifier on ring (ES256, EdDSA, RS256 2048-4096), challenges in two pools, `PASSKEY_RP_ID` = only that host + `PASSKEY_ORIGINS`, the options call a POST counted once per pre-session, Add/Remove behind re-auth, re-auth by passkey on every re-auth form, Reset/Remove/`reset-2fa`/`reset-sign-in` delete the passkeys, `passkey_added` push to every parent, the Parents count. Owed: manual QA on the iPhone PWA, Android Chrome, Mac Safari, a security key, 1Password/Bitwarden, a second hostname, http on the LAN. Ships in the next server release.
- **Missed Element X calls on the contact card and the phone book's badge (round L5)** (asked 2026-10-25; no capture needed after Element X Android's source was read 2026-11-02) **MERGED 2026-11-02** (`missed-voip-calls` a284c0bd + fix round 42613749; launcher only; 1953/0; light code QA 0/2/4/5, fixed: a re-judge when the call rules' Element contacts change, an early return before parsing, redacted `toString`, the privacy scan over every source file): the room's `missed_call` notification marks the contact through the learned DM room or the caller's MXID; the badge counts both kinds; the sheet says "1 tapt anrop i Element X i dag 13:05"; cleared when the notification goes, flips back to `message`, or the sheet is opened. Not with an app PIN in Element X. The user's check on the Jelly Star owed. Ships in the next launcher RC.
- **The kid's calendar app is called "Kalender" (en "Calendar"), not "Dagen"** (the user, 2026-11-02: "Navnet på appen, bruk Kalender istedet") **MERGED 2026-11-02** (`calendar-name` becc7cde; launcher + server strings only; launcher 1943/0, server 1526/0; XS, the coordinator's review of the diff): `day_title` (Home tile, Recents, the glance) and the reminder row's "Lås opp og åpne Kalender"; Device › Calendar's group "Kalender-appen"/"Calendar app" and its words; code names, `#dagen`, routes and keys stay "dagen". Ships in the next launcher RC and server release. Still open from the same entry: the colour nit (berry next to peach, 1.21:1), no work.
- **S-i18n slice 3c - the Locate tab in nb and en (design 70 §5.1)** **MERGED 2026-11-02** (`pwa-i18n-3c` 372534f8 + fix round f61892db; server only; cargo 1528/0, node 212/0; light code QA 0/0/3/3, fixed: `locate.zones.full` takes `{max}`, `error.install_send` → `error.command_send`, six nb wordings) - device_locate, device_locations, device_zone, locate.js (data-w-*, `?lang` on locate-result.json), every command row worded (`models::command_label` returns a key); 210 new keys per catalog, the nb list sent to the user. Left for slice 4: `kid_lock::PinAnswer::words`; 3e: the Notify Me About heading must read exactly "Varsle meg om" (Locate's footer quotes it). Browser pass at 390 px owed. Ships in the next server release.
- **Apps › Navn og ikon: the long hint cut, "Vibb-stil" renamed** (the user, 2026-11-02, iPhone screenshot: "Kutte ut denne teksten fra app tab?") **MERGED 2026-11-02** (`app-display-words` 3c43aacd; server only; server 1526/0; XS, the coordinator's review of the diff): the paragraph under the picker gone (and `reset_hint()`); chips "Automatisk"/"Automatic" and "Appens logo"/"App logo", preview "Logo", the describe and error words to match. Ships in the next server release.
- **Ended pauses and extra time in the phone's Activity list** (the user, 2026-11-02: "Ja det er greit" to showing ended lifts there after the Tidsregler tidy took "Nylig" away) **MERGED 2026-11-02** (`activity-lifts` 6122c75f + fix round 953940b6; server only, no migration; server 1526/0; light code QA 0/0/2/2, both Lows fixed): every `time_lifts` row shows once at its grant time, read-only, except a rule pause still running (that one stays in Quick actions with End now, the same `datetime('now')` boundary); "Skole satt på pause i 30 min · Sluttet 14:30", "Avsluttet tidlig 14:20", "+15 min ekstra skjermtid · Lagt til". Ships in the next server release.
- **"Sist sett" again, and an env default language for the PWA** (the user, 2026-11-01: "Sist sett gir mer mening. Ønsker btw å kunne sette en env variabel som gir pwa som engelsk som utgangspunkt?") **MERGED 2026-11-01** (`pwa-default-lang`; server only; 1524 tests, CI 41360789671 green; light QA 0/0/3) - nb "sist sett {at}" / "sist sett for N min siden" (en "last seen"); `PWA_DEFAULT_LANG=en|nb` (`ForkConfig::pwa_default_lang`, invalid = unset, logged): `?lang=` > the account's language > the env default > Accept-Language > English, and the first signed-in request stores the env default; the account switch still wins. The sign-in page itself stays English until slice 3e. Ships in the next server release.
- **The chosen tab's icon filled with peach, same outline and stroke** (the user, 2026-11-01: "fersken fargen skulle fylle det som er grått eller tomt i taben"; mockup A approved: "A ser bra ut, kjør på med den! Med samme str på ikonene som i dag") **DONE 2026-11-01** on main - the same 24 px icons and 2.1 stroke; the chosen/pending tab fills `.ti-body` and the note's beam (`.ti-fill`) with `--tab-on-icon`, and cuts `.ti-detail` (the phone's bar, the pin's dot, the gear's middle) out in `--bar`; style.css v70. Ships in the next server release.

(Everything before shipped in `server-v0.62.0` / `launcher-v0.38.0-rc.2` / `music-v0.6.0-rc.1` on 2026-11-01/02 and moved to `docs/plan/plan-archive.md`.)
