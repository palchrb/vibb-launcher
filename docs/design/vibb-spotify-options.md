# Vibb on the handy phone: Spotify options

Research 2026-10-06 for the Vibb Android app (PLAN.md "Vibb integration", vibb `PLAN-android.md`). NRK/RSS, Storytel and own
files port as planned; this is only about Spotify. Markers: [S#] = source list at the end, [unverified] = not confirmed from a
primary source, [needs device test] = must be tried on the Jelly Star. Kiosk facts are from docs 09 (B4) and 11 (section 1).

## Background

**The Pi today**: a pinned go-librespot fork (Connect receiver, zeroconf login, on-disk audio cache) on the box's *old* account.
**What broke** (why the Android draft dropped Spotify):
- **Audio-key denial for new accounts.** Since ~Nov 2025 Spotify refuses the legacy AES audio key ("audio key error 0 1") to
  accounts created after a cutoff (user reports: mostly accounts from late 2024/2025 on, e.g. Jun 2024 works, Nov 2025 fails; one
  pre-2020 account reported failing too; enforced around Dec 2025). Login succeeds, every track fails. Maintainers: official clients have moved to Spotify's other DRM for all media,
  a new account added to an existing Family plan fails at once (2026-03-03); the issue is open with 142 comments [S1]. The
  go-librespot owner calls it "out of our control"; the user's own two new kids' accounts fail there (2026-07-11) [S2]. The only
  fix is implementing PlayPlay DRM, which gets DMCA takedowns [S3]. **No open client works for new accounts, and none is
  expected to.** Account age decides, not plan type: Family/Premium does not help [S1, vibb `docs/PLAN-soloistd.md`].
- **Auth churn on top**: password login gone (2024, librespot#1308), OAuth/zeroconf only; Aug 2026 Spotify killed login5 with access tokens from
  a non-desktop client ID [S4]; 2026-09-29 a login5 outage stopped librespot while the official app worked [S5].
- **Project status** (GitHub, 2026-10-06) [S6]: librespot (Rust) v0.8.0 2025-11, active; go-librespot v0.10.3 2026-10-03, active;
  spotifyd v0.4.2 2025-11; librespot-java marked DEPRECATED (last release 2024-12); librespot-android is a 2023 demo of
  librespot-java. Their caches hold encrypted audio and still need a live session for keys, so no real offline (vibb README).
- **Soloist** (Spotify's official headless Connect client, 2026-08-13): Linux/glibc only (arm64/armv7/x86_64, PipeWire or
  PulseAudio), a Premium account's personal API key, builds expire after 90 days, no redistribution [S7]. Not usable on
  Android; it is the Pi's contingency (vibb `docs/NOTES-soloist.md`).
- **Official Android SDK**: App Remote 0.8.0 (2023-07, still "beta", no release since) + auth library (maintained, 5.0.0
  2026-07) [S9, S10]. Your app sends commands; the Spotify app does playback, caching, audio focus and lock-screen controls.
- **Web API, Development Mode** (Feb/Mar 2026) [S12]: app owner needs Premium; 1 dev client ID per developer (25 since Jul 2026
  per a third-party summary [unverified]); 5 users per client ID; playlist `items` only for playlists the user owns or collaborates on; `GET /users/{id}/playlists` removed (vibb's
  "follow a profile", `spotify_web.user_playlists`, likely breaks on the Pi too [unverified]); batch endpoints gone; search max 10.
  Refresh tokens expire 6 months after authorization (Jun/Jul 2026) [S14]. Extended quota: organisations with 250k MAU only [S13].
- **Connect / eSDK**: partner program for organisations only, NDA + certification + devices sent to Spotify [S19]; not for a
  hobbyist. "Phone as Connect receiver" = the Spotify app itself (own account) or librespot (dead for new accounts). "Phone as
  controller" via Web API needs the network for every command; App Remote does the same locally.
- **Policy**: Developer Policy III.8 "Do not build products and services which are targeted to children" and III.11 (no
  replicating Spotify's core experience) [S15]; Developer Terms v10 IV.3.2 allows only temporary caching / Spotify's own
  "Conditional Downloads" [S16]; User Guidelines forbid reverse engineering, circumvention, ripping and moving cached content [S17].
- **Offline in the Spotify app**: Premium downloads, 10,000 tracks on each of up to 5 devices, online at least every 30 days [S18].
  No SDK or Web API call starts a download; it is a toggle in the Spotify UI.
- **Kids**: *managed accounts* (under 13, music only, explicit filter on, block artists/songs, video off; Free tier since
  2026-07-15) are **not offered in Norway** (not in any country list up to the 2026-07-28 wave) [S20]. *Spotify Kids* (Premium
  Family app) looks unavailable in Norway: spotify.com/no/kids redirects to the Premium page, /se/kids exists [S22, unverified].
  What a Norwegian Family manager has: explicit filter per member, and since 2026-04-09 video/Canvas off per member [S21].
  Podcasts, audiobooks and the algorithmic home feed stay in an ordinary member account.

## Options

### A. Spotify app as a hidden engine, driven by App Remote (+ Web API on the home server for listings)
- **How**: Spotify (`com.spotify.music`) installed from Play, logged in with the kid's Family member account. Vibb gets a
  `SpotifySource`: `SpotifyAppRemote.connect(showAuthView=false)` while Vibb is in front or has its playback FGS; `play(context)`,
  `skipToIndex(uri, i)`, `seekTo(ms)`, `subscribeToPlayerState`, `ImagesApi` for covers [S9]. App Remote can't list a playlist by
  URI (ContentApi only browses recommendations), so the home server resolves listings with the *parent's* Web API token
  (owner of the dev-mode app; playlists the parent owns) and ships tracks + covers in `library.json` via managed configuration.
- **Kid UX**: same cover carousel and resume as other sources; Spotify tiles carry Spotify attribution (Policy II.4). Free
  accounts can't play single tracks on demand [S9]; Premium Family members can.
- **Offline**: plays Spotify downloads offline, but "apps cannot connect and start communicating with Spotify unless there is an
  internet connection" [S9]: after a reboot or Vibb restart without network, Spotify tiles are dead until the network returns.
  The phone has mobile data, so this mainly matters in dead zones/cabins. With Spotify's own offline mode switched on,
  calls that need the backend fail (`OfflineModeException`) [S9].
- **Parent setup**: dev-mode app at developer.spotify.com (parent Premium; Vibb package + signing SHA-1 + redirect URI; kids'
  accounts on the 5-user list [unverified: list applies to App Remote consent]); client ID to Vibb by managed config; once, in
  a PIN "Spotify setup mode": log in, approve the `app-remote-control` consent (Spotify's own screen), follow the curated
  playlists and toggle Download. Re-authorize the server's Web API token every 6 months in the PWA [S14].
- **Kiosk/lock task**: Spotify must be installed, **not hidden** (hidden = uninstalled for the user), **not suspended** (suspension
  blocks its activities, hides its notifications; whether its service and audio keep running is [needs device test]), not on
  Home and not pinned. With the kiosk app block on, every Spotify activity - login, consent, notification and media-control taps -
  becomes `BlockedAppActivity` (doc 11 section 1). New for handy: an "engine" app state (like Play's treatment in doc 09 B4) and
  a setup mode that pins Spotify for a few minutes (like install mode pins Play). Time rules: blocking Vibb does not stop Spotify,
  so handy pauses Spotify's session via its NotificationListener (`MediaSessionManager.getActiveSessions`) or suspends it [needs
  device test]. Spotify's ongoing playback notification can't be cancelled by the listener (`FLAG_ONGOING_EVENT`), its promo
  notifications can (step 11 rule). Residual: kiosk off → a notification tap opens full Spotify. DNS filter must allow Spotify.
- **Legal/ToS**: official SDK used as designed, but a kids' player contradicts Developer Policy III.8 [S15]; the realistic
  sanction is a revoked client ID, i.e. the Spotify source stops. Private dev-mode use (≤5 users) only; a public Vibb build should
  ship this source off and unbranded [opinion].
- **Maintenance**: App Remote unchanged since 2023 and known to get "stuck" occasionally [S9, android-sdk#348]; Web API keeps
  shrinking (Feb 2026); Spotify app auto-updates via Play. Medium.
- **Effort**: Vibb source + reconnect logic ~1-2 weeks; handy engine state + setup mode + bedtime pause ~1 week; server OAuth +
  listing ~3-5 days. Preceded by a 1-2 day spike (checklist below).

### B. go-librespot (vibb's fork) embedded in Vibb on Android
- **How**: cross-compile the Go fork for android/arm64, pipe PCM into AudioTrack/ExoPlayer via gomobile/JNI; same HTTP API as
  `spotify.py`. **Kid UX** as on the Pi. **Offline**: encrypted cache, needs a live session (no real offline).
- **Blocker**: works only with pre-cutoff accounts. The kids' accounts are new [S2]; the old account is the Pi box's, and one
  account streams on one device at a time, so phone, box and parent would interrupt each other [unverified for this setup].
- **Kiosk**: Vibb only; needs Spotify AP/CDN hosts through the DNS filter. **ToS**: reverse-engineered client, against the User
  Guidelines [S17]. **Maintenance**: high and rising (cutoff, login5 changes [S4]). **Effort**: 2-3 weeks. **Reject.**

### C. The Spotify app as an ordinary allowlisted kiosk app (no Vibb integration)
- **How**: Spotify from Play on Home, pinned in lock task, kid's Family member account. Vibb keeps NRK/podcasts/Storytel/own files.
- **Kid UX**: full Spotify: search, algorithmic home, podcasts, audiobooks; explicit filter and video off set by the Family
  manager [S21]; no curation (managed accounts/Spotify Kids not in Norway [S20, S22]). **Offline**: Spotify downloads [S18].
- **Parent setup**: log in once, set member controls in the Family hub. **Kiosk**: a normal allowlisted app; time rules apply as
  to any app. **ToS**: none. **Maintenance**: low. **Effort**: 1-2 days (catalog entry from Play, runbook). Fine for an older kid;
  it is exactly the open catalogue Vibb exists to avoid.

### D. Own library: bought or ripped files, served by the home server, synced to Vibb
- **How**: DRM-free purchases (Qobuz/Bandcamp/iTunes-store AAC, all download-to-own) and CD rips land in Vibb's "own files"
  (already supported on the Pi: tags, per-track art). On the phone Vibb downloads them to app storage and plays with ExoPlayer.
  Transfer path is open: share the launcher's tsnet SOCKS5 loopback proxy (`TsnetClient`) with Vibb, or let the launcher
  download and hand files over via a FileProvider.
- **Kid UX**: identical to other tiles, instant start. **Offline**: always (files on the phone). **Parent setup**: buy/rip, upload
  in the PWA. **Kiosk**: nothing new. **Legal**: private copies of lawfully acquired works are allowed (åndsverkloven § 26),
  circumventing DRM is not (§ 99) [S26]; recording Spotify streams is out [S17]. **Maintenance**: low (our code). **Effort**: ~1
  week, mostly part of the planned port; the ongoing cost is buying/ripping.

### E. Other services (checked, mostly dead ends)
| Service | Third-party playback on Android | Verdict |
|---|---|---|
| Tidal | Official SDK player (ExoPlayer, has an offline engine) but third parties get 30 s previews; full playback needs Device Login/Auth Code, not opened for third-party apps (no staff answer, Apr 2026) [S23] | not today; watch |
| Deezer | Native SDK deprecated [S24]; new API apps reportedly closed to individuals [unverified] | no |
| Qobuz | Partner-only API, no SDK [unverified]; its *store* sells DRM-free files → option D | store only |
| Apple Music | MusicKit for Android plays inside your app, needs a subscription and the Apple Music app for login; SDK docs date from 2019, no offline [S25] | stale, no |
| YouTube Music | no playback API | no |

| | New kids' accounts | Offline | ToS risk | Maint. risk | Effort |
|---|---|---|---|---|---|
| A App Remote engine | yes | downloads, only if Vibb connected while online | Policy III.8 | medium | 3-4 wk |
| B go-librespot | **no** | cache needs network | high | high | 2-3 wk |
| C Spotify app in kiosk | yes | yes | none | low | days |
| D Own library | n/a | yes | none (legal copies) | low | ~1 wk |

## Recommendation

1. **Core = D + NRK/podcasts/Storytel.** It is the only source that is fully offline, curated and risk-free, and Vibb already
   handles own files on the Pi; build it as part of the planned port.
2. **Spotify = A, as an opt-in source for this family, after a spike.** It is the only way to show curated Spotify content in
   Vibb with the kids' new accounts. Ship it off by default in public builds (Policy III.8).
3. **Fallback = C** for an older kid if the spike fails or the parent prefers the plain app (explicit filter + video off).
4. **Reject B** on the phone (new accounts can't play), and eSDK/Soloist/E for now. Keep the Pi on its fork; Soloist stays the
   Pi's contingency.

**Spike for A (kill criteria = items 1-3)** [all needs device test], Jelly Star, kiosk on with the app block:
1. App Remote connects with Spotify not pinned, consent given earlier in setup mode; no Spotify activity starts.
2. Playback continues with Vibb in the background, screen off, for 1 h; Vibb reconnects after Spotify is killed.
3. Reboot with mobile data on: Vibb connects without Spotify ever being shown. Then airplane mode: does a downloaded playlist
   still play, and what happens after a reboot offline (expected: no connect)?
4. Resume accuracy: `play(context)` + `skipToIndex` + `seekTo` lands on the bookmarked track and second, silently.
5. Notification and media-control taps → `BlockedAppActivity`; promo notifications cancelled by the step 11 rule.
6. Bedtime rule: handy pauses Spotify via `MediaSessionManager`; does suspending Spotify also stop its audio?
7. Volume cap holds for Spotify's stream; DNS filter log shows which Spotify hosts must be allowed.

## Open questions for the user
1. Must Spotify be inside Vibb, or is own library + NRK/Storytel (+ plain Spotify app for an older kid, option C) enough?
2. Accept the Developer Policy III.8 risk (revoked client ID) for private use, with Spotify off in public Vibb builds?
3. Kids' Spotify accounts: Premium Family members (needed for on-demand play in A and for downloads)?
4. How much offline matters (cabin weeks with no coverage?) given A only plays downloads after connecting online.
5. OK for the parent to open Spotify's UI on the kid's phone in a PIN setup mode (login, consent, download toggles)?
6. Willing to buy (Qobuz/Bandcamp/iTunes) or rip CDs for the kids' favourites? Shared library with the Pi box?
7. Own-file transfer: share the launcher's tsnet proxy with Vibb, or let the launcher download and hand over files?

## Sources (read 2026-10-05/06)
[S1] github.com/librespot-org/librespot/issues/1649 (opened 2025-11-21; maintainer comments 2025-12-29, 2026-01-09, 2026-03-03)
[S2] github.com/devgianlu/go-librespot/issues/279 (owner 2026-01-23; palchrb 2026-07-11) · [S3] .../go-librespot/issues/317
[S4] github.com/devgianlu/go-librespot/issues/364 (owner 2026-08-12) · [S5] github.com/librespot-org/librespot/issues/1771
[S6] GitHub API repo/release data for the repos named · [S7] developer.spotify.com/blog/2026-08-13-introducing-spotify-soloist,
developer.spotify.com/documentation/soloist, .../soloist/reference/downloads-and-updates
[S9] github.com/spotify/android-sdk (README, app-remote-lib/README.md, ERRORS.md, releases, issue #348);
spotify.github.io/android-sdk/app-remote-lib/docs (PlayerApi, ContentApi) · [S10] github.com/spotify/android-auth/releases,
developer.spotify.com/documentation/android/tutorials/authorization
[S12] developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security,
developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide
[S13] community.spotify.com "Updating the Criteria for Web API Extended Access" (2025-04/05)
[S14] developer.spotify.com/blog/2026-06-18-refresh-token-expiration · [S15] developer.spotify.com/policy
[S16] developer.spotify.com/terms (v10, 2025-05-15) · [S17] spotify.com/us/legal/user-guidelines
[S18] support.spotify.com/us/article/listen-offline · [S19] developer.spotify.com/documentation/commercial-hardware
[S20] newsroom.spotify.com/2025-10-14/spotify-family-plan-managed-accounts, newsroom.spotify.com/2026-07-15/managed-accounts-for-
families-expansion (+ 2026-07-28 update), 9to5mac.com/2026/07/15 ("music only")
[S21] techcrunch.com/2026/04/09/spotify-now-lets-everyone-turn-off-all-videos-in-its-app
[S22] spotify.com/no/kids → /no-nb/premium (curl, 2026-10-06); en.wikipedia.org/wiki/Spotify_Kids
[S23] github.com/tidal-music/tidal-sdk-android player/README.md; github.com/orgs/tidal-music/discussions/179
[S24] support.deezer.com "Deezer FAQs For Developers" · [S25] developer.apple.com/musickit/android;
github.com/assembleinc/kids-tunes-android · [S26] lovdata.no/dokument/NL/lov/2018-06-15-40 (§§ 26, 99)

## Decision (user, 2026-10-06)

Option A — the Spotify app as a hidden playback engine controlled by Vibb through the App
Remote SDK — is the main plan for music in Vibb on Android. NRK/podcasts/Storytel/own files
remain built into Vibb. Accepted: Developer Policy III.8 risk (family-only dev-mode app, ≤5
users), Play account on the phone (Spotify comes from Play), and that a cold start without
network can't connect until it is online (downloads play once connected).
First step: the device spike (§ test plan) on a Play emulator image / the Jelly Star, with the
kill criteria in this doc. handy needs an "engine app" state for Spotify (installed, never
hidden or suspended, not on Home, not launchable by the kid; activities only in PIN setup mode;
paused by time rules).
