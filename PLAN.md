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
- Offline override PIN (PBKDF2, verified on the phone), PIN-gated settings.
- Tailscale embedded in the launcher (`tsnet.aar`), no Tailscale app needed.
- DNS filtering via the launcher's own `VpnService` + blocklists from the
  server, private DNS lock, DNS log.
- Find my device: locate, ring, lock, wipe.
- Server→phone nudges over SSE held by a foreground service (plus periodic
  sync); launcher is also a UnifiedPush distributor for other apps.
- Journal, browser history, install progress, blocked-event log.
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

## Repos

- Two GitHub forks, kept close to upstream so changes can go back as PRs:
  `kids-launcher-mdm` (phone) and `kid-phone-server` (home server).
- One feature branch per change, upstreamable first (calls, hardening,
  camera/permissions are generally useful); handy-specific bits (Vibb
  integration, own branding/config) kept small and separate.
- Features that span both (e.g. call allowlists: server schema + PWA + phone
  enforcement) land as paired PRs with the same branch name in both forks.
- This repo (`handy`) holds the plan, setup notes and anything that is
  neither launcher nor server.
- Talk to upstream early (issue in each repo) about calls/SMS support before
  building it, so the design fits what they would merge.

## Architecture

- **Home server** (fork of kid-phone-server): defines all rules, serves the
  parent PWA, reachable over Tailscale. Runs on a Pi or similar.
- **Phone** (fork of kids-launcher-mdm): subscribes to the server (SSE nudge
  today; ntfy/UnifiedPush is an option) and fetches policy on a nudge or on
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
  account is needed for updates. Element X from Play uses FCM; the F-Droid
  build can use UnifiedPush via the launcher's distributor.

## Status (2026-10-05)

Done in code (branch `handy` in both forks, QA-reviewed): own build and release
signing, fail-closed policy, calls/phone book/message buttons, direct boot,
hardening. Not frozen: remaining features continue in parallel with device
testing on the Jelly Star. Not built yet: full Tailscale (design in
`docs/design/03-tailscale.md`), Vibb integration, AdGuard Home, launcher UI
redesign. Play account: decided 2026-10-05 to start WITHOUT one (apps via the
server catalog); add later only if Play-only apps are needed.

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
- **Calendar**: an open-source calendar app in the server catalog (e.g. Fossify
  Calendar) plus ICSx5/DAVx5 to subscribe to a family calendar by ICS/CalDAV URL
  without a Google account. Later maybe "next event" on Home.
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
- **i18n from the start**: Norwegian (nb) and English. Launcher: all strings in
  resources with `values-nb`, per-app language (generateLocaleConfig is already
  on), language chosen per device by the parent. Server PWA: string catalog with
  nb/en and a per-admin language setting.

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
- Keep SSE for nudges, or switch to ntfy/UnifiedPush?
- Messaging: own Matrix homeserver so Element X contacts are enforced there?
