# Upstream review, 2026-11-20

What `siesta5787/kid-phone-server` and `siesta5787/kids-launcher-mdm` committed from 2026-11-10 to 2026-11-20, read
against our `main` (branch `home-name-pill` at 773e61ee1). Each commit was read with `gh api
repos/siesta5787/<repo>/commits/<sha>` (message, files, patches), and our side was checked in `launcher/`, `server/` and
the notes. Nothing was ported, and no code was changed.

The earlier round (PLAN, "Upstream's recent commits", 2026-11-12) read upstream up to server f9e4e9671e and launcher
946ff1fec9. It took three launcher fixes (`upstream-fixes`, launcher-v0.39.0-rc.8). Its "Lock now" became design 93.
Two commits below (c0eab6e63 and 8a6027cc7, both 2026-11-10) may have been part of that round. They are re-checked here
because the lock-screen bypass is a security question.

## Licence

- **kid-phone-server**: GPL-3.0. Compatible, and code can be copied with attribution (NOTICE.md already names the fork).
- **kids-launcher-mdm**: GPL-3.0-or-later for everything except `ui/list/` and `apps/`, which are MIT
  (`LICENSE-MIT-UPSTREAM`, the same split as ours). None of the commits below touch the MIT directories.
- **Vendored `gpapi`** (EFF's Google Play API client, in 9d71b48dd and a3bd33aa8): MIT, which is GPL-compatible. It would
  be a new third-party crate, with its own audit, `Cargo.lock` and supply-chain cost.

So licensing does not block anything. The questions are design and policy.

## The commits

Size: S is under a day with QA, M is a design note and one round, L is a design plus several steps.

### kids-launcher-mdm

| Commit | What it does | Do we have it? | Recommendation | Size |
|---|---|---|---|---|
| **c0eab6e63** (0.53.0) Close the recents/notification bypass of the lock screen; parent lock override | Upstream's bedtime/screen-time `LockActivity` kept the policy's normal lock-task features (OVERVIEW and NOTIFICATIONS always on), so a kid could go back into an app through Recents or a notification tap. The fix pins only the launcher and the default dialer, with KEYGUARD, SYSTEM_INFO and GLOBAL_ACTIONS, while locked. `LockActivity` starts lock task itself. Also adds `lockOverride` "lock"/"unlock" until a time. | **Yes, and stronger.** (1) A time-rule lock suspends every controllable app except the lock's usable apps, alarms and calendar sync (`EnforcementPlan.kt:241-244`). Android hides a suspended app's notifications and refuses its tasks from Recents ("app paused"), whether the kiosk is on or off. (2) With the kiosk on, the list of pinned apps shrinks during the lock to our package, the usable apps, the allowed system dialer and the allowed clocks (`EnforcementPlan.kt:258-261`), so Android closes every other app's task. (3) Vibb's own PIN lock removes NOTIFICATIONS, HOME and OVERVIEW (`featuresWhileLocked`, `PIN_LOCK_FEATURES_KIOSK_OFF`, `LockTaskHelpers.kt:157-174`). (4) With Android's PIN lock, the keyguard belongs to the system. Recents and a notification's tap both need the credential, so this bypass has no counterpart there. The override exists as design 93 ("Slå på regel", "Pause regler") and "Åpne for oppsett". | **Skip.** On the Jelly Star, add one device check to the next lock round: under a rule, tap an exempt app's notification and press the Overview key. Neither should reach a suspended app. | - |
| **be507e1d9** (0.53.1) Reconnect the push stream when it goes silent | The SSE client had `readTimeout(0)`, so a stream that died silently was never noticed. It is now 45 s against the server's 15 s keep-alive. | **Yes, already built.** `SSE_READ_TIMEOUT_MS = 300_000` against `SSE_KEEPALIVE_SECS` 240 (`push/SyncSchedule.kt:49`, `CommandListenerService.kt:127`). Every byte is stamped (`LastByteInterceptor`), a stale watchdog fires at `SSE_STALE_MS = 480_000`, and the stream follows network changes (design 121). The longer interval is a deliberate battery choice (design 19). | **Skip.** | - |
| **f7dea2083** (0.54.0) App groups: shared time limits, hours, pause | Groups of apps, each with one daily limit, one allowed-hours window and a Pause/Allow override. Usage is counted on the phone from `UsageEvents`, which needs the usage-access app-op (a one-time grant in Settings). A blocked group's apps are suspended. A per-minute check sends the kid home. | **No.** Our screen time is one budget for the whole phone (`ScreenTime.kt`: counted while the screen is on and unlocked). Our time rules have exempt apps but no per-app or per-group budget. We don't declare `PACKAGE_USAGE_STATS`. | **Ask the user.** Parents often want it ("YouTube and games 1 h, the rest free"). Ours would be built differently: (1) the server owns the groups and sends them in `time_policy`; (2) the per-minute check is replaced by our single-alarm timing (`TimeRuleAlarm`); (3) the usage-access grant gets a row on the pause screen, never an adb-only path; (4) without the grant, a group's apps are blocked rather than left unlimited (fail closed, no insecure fallback); (5) a shared contract file. | L |
| **92a7b9e17** (0.55.0) Play sign-in, split APKs, pause Phone in app groups | A PIN-gated WebView on Google's EmbeddedSetup page gives the server Google's one-time `oauth_token`, which it uses for server-side Play downloads. The phone can now install split APKs in one session. Groups can pause the dialer. | **No.** Play is the phone's own Play Store with a dedicated account (decided 2026-10-05; `PlayRuntime`, install mode). Our installer writes one APK per session (`AppInstaller.kt:89-92`). | **Skip** the sign-in: it hands a Google account's master credential to our server, and moving it through a kid's-phone WebView is the wrong trust shape. Split APKs only follow if we take Play as a server source (9d71b48dd). | - |
| **bc95be0d7** (0.55.1) Report why an install failed | The failure report carries Android's `EXTRA_STATUS_MESSAGE` (for example `INSTALL_FAILED_OLDER_SDK: ...`) or the download error. | **Partly.** We read the message and only log it (`AppInstallReceiver.kt:34`, `:77`). The server gets `InstallProgressReport(percent = 0, failed = true)` with no reason, so the PWA shows "Install failed" alone. | **Adapt.** Send a short fixed code, not Android's free text: the `INSTALL_FAILED_*` name parsed from the message's prefix, or our own download/verify kind. That keeps data minimal and avoids free text such as paths. The server words it, with the code behind the (i). Both directories go in one commit with a contract row. | S |
| **1b956afce** (0.55.2) Download progress and time left | A progress bar in the install notification ("45% - about 6 min left"), counted in bytes across a split app's files. A report every 2%. | **Mostly.** `app_downloads` already reports bytes/total and the state (design 13). The PWA shows a live percentage from a progress row less than 10 minutes old, plus "120 of 326 MB". There is no time left, and the kid's notification has no progress bar. | **Skip** on the phone (fewer words for the kid). On the PWA, "about N min left" could be worked out on the server from two reports. Low value. | S |
| **07518ac91** (0.56.0) Download Play/GitHub apps straight from the source | Before each update the phone asks the server for direct links, fetches from Google Play or GitHub with a separate client (no tsnet, no token, https only) and keeps the file only if its SHA-256 and size match the server's copy. Otherwise it downloads from the server. | **No.** By design the phone downloads only from our server (design 13: resumable, Wi-Fi only, hashed). | **Skip for now.** See "Direct download" below. | M |
| **e4c1db965** (0.56.1) Crash when restrictions are bypassed while the filter starts | Every `startForegroundService()`, even to a running service, makes Android wait for a `startForeground()`. A `stop()` in that gap crashed the launcher. The fix: `start()` talks to a running instance in-process and never sends a second start while one is pending. | **Yes, already built.** We took upstream's earlier c88216cfe0 (stop only once created and with no foreground start pending: `vpnStopsNow`, `VpnForeground.kt:141`). Separately, `vpnStartKind` makes `start()` a plain `startService` whenever an instance runs (`KidVpnService.kt:713-731`, qa-lfix-notifs-code #2). A plain start has no `fgRequired`, so exactly upstream's new case (a sync's start on a running service, then a pause's stop) cannot crash us. The `IllegalStateException` fallback counts its foreground start as pending. | **Skip.** | - |
| **547c77e1d** (0.57.0) Leave chosen apps out of the filter VPN | The policy's `vpnBypassPackages` go to `addDisallowedApplication`, and the tunnel is rebuilt when the set of those apps that are installed changes. For apps that refuse to run under any VPN: Android then reports no VPN to them, and their traffic isn't filtered. | **No.** Only the launcher itself is excluded (`KidVpnService.kt:265`). Design 95's D4 chose no per-app exception, not even for the music app, and design 115's hold rule assumes it ("the launcher is the only app excluded from the tunnel"). | **Ask the user.** It matters only if a real app refuses a VPN. Vipps is the likely one (it checks for "unsafe" setups). The cost is real: an excluded app escapes the DNS filter, the Bare Vibb/roaming sink and the always-on lockdown (it would have to join lockdown's allow list), and design 115's hold rule would need another look. If wanted: only allowlisted, non-browser apps, the filter shape only, and the PWA marks the app as "Unfiltered" as a parent's choice (a quiet note, not a warning). | M |

### kid-phone-server

| Commit | What it does | Do we have it? | Recommendation | Size |
|---|---|---|---|---|
| **8a6027cc7** (0.22.0) Lock now / Unlock now | A device-page card: lock or unlock for 30 min, 1, 2 or 4 h, or until changed, plus "Back to schedule". Stored as `lock_override` and `until_ms`. | **Yes, better.** Design 93 ("Slå på regel": a dated copy of a real rule with `until_ms`/`card_first`, and `lock_now.json`), "Pause regler" (design 98 rule pause, lifts) and "Åpne for oppsett". | **Skip.** | - |
| **e11ec64b7** (0.23.0) App groups with shared time limits | The server half of f7dea2083: an `app_groups` table, a management page, Pause/Allow buttons, `PolicyResponse.app_groups`, and usage back in the status report. | **No.** | **Ask the user**, as for f7dea2083 (one design across both sides). | L (with the launcher) |
| **9d71b48dd** (0.24.0) Google Play as an app source, split APKs | The server signs in to Play with a spare account (through a phone's WebView or a pasted cookie), looks apps up and downloads them through a vendored `gpapi` (MIT), and serves base + splits to the phone. | **No.** Play on our phones is the Play Store with a dedicated account, kept hidden and suspended outside its install/update windows (`PlayRuntime`). Our catalog is GitHub, F-Droid and uploads, signer-pinned on both sides (design 90 holds). | **Ask the user, leaning skip.** What it would give: Play-only apps without a Google account on the phone, under our pins and holds. The costs: an unofficial API that impersonates a device (Google may ban the spare account or break the protocol at any time), a Google credential stored on our server, a ~1,500-line vendored client to maintain, and split-APK installs on the phone. Play Integrity apps (Vipps) still need Play on the phone anyway. | L |
| **a3bd33aa8** (0.24.1) Play lookups failing with "error sending request" | gpapi's HTTP client: no connection reuse, timeouts, one retry with a fresh session, a full error chain. | n/a (no gpapi). | **Skip** (goes with 9d71b48dd). | - |
| **84b182246** (0.24.2) Background update checks with live progress; install failure reasons | "Check now" and first downloads run in the background, the page polls, one check per app at a time, GitHub downloads are streamed with progress, busy buttons. The device page shows why an install failed, kept for a day. | **Mostly.** "Check now says what it is doing" was done 2026-11-19 (`server-small`). There is one sync per app (`AppSyncs`), and downloads go to a `.part` file with a random name and are hashed on the way. The missing piece is the failure reason. | **Adapt** only the failure reason, together with bc95be0d7. | S (counted there) |
| **80bd0fd7a** (0.24.3) Verify Play downloads, repair damaged releases | Every downloaded piece must be a readable zip with an `AndroidManifest.xml` before it is published, and a damaged live release is downloaded again. The cause was two checks of one app writing into one folder. | **Yes, stronger.** Every release goes through `apk::read_identity` (the v2/v3 signature over the whole file, so a truncated or damaged file fails) and `check_pins` (`tracked_apps.rs:2097`, `:4389`, `:5327`) before it is taken. One sync per app plus `.part` names rule out upstream's cause. | **Skip.** | - |
| **9e57a8fb4** (0.24.4) Big downloads freezing the Pi Zero | Flush to disk and `fadvise` the page cache away every 8 MB, drop sent bytes from the cache, one big download at a time server-wide. | n/a. Our server runs on a Proxmox VM; the Pi Zero is out (memory: server host). | **Skip.** | - |
| **67ac44e0c** (0.24.5) Progress for uploads, downloads, installs | A manual APK upload is streamed to disk (it was read whole into memory with a 200 MB cap that failed with a misleading message), the limit raised to 4 GB, an upload bar, "N min left", live install status, file sizes for splits. | **Partly. The same upload flaw is ours:** `/apps/tracked/{id}/upload` has `DefaultBodyLimit::max(200 MB)` (`main.rs:1457`), and the handler reads the APK whole with `field.bytes().await.ok()` (`tracked_apps.rs:4325`). An APK over 200 MB is therefore refused as "missing file" (`apps.app.upload_missing`), not as too large. Live install status: we have it (`app_downloads`). | **Adapt** the upload half: stream to a `.part` file, hashing on the way like `stream_to_file`, raise the cap (1-2 GB), and give an over-limit upload its own message. The upload bar is optional. | S |
| **4b64c551e** (0.25.0) Phones download Play/GitHub apps from the source | The server still downloads and checks every release and records each file's SHA-256. `GET /api/devices/apps/{id}/direct` gives fresh source links plus the hashes. A phone keeps only a matching file, otherwise it uses the server copy. The device page shows the download's source. | **No** (see the launcher's 07518ac91). | **Skip for now.** See "Direct download". | M (with the launcher) |
| **08af9149b** (0.26.0) Apps skip the content filter | A device-page card ticks allowed apps into `vpn_bypass_packages` (migration 0029). | **No** (see the launcher's 547c77e1d). | **Ask the user**, as for 547c77e1d. | M (with the launcher) |

## Bugs that might affect us

- **The VPN start/stop crash (e4c1db965): not affected.** `vpnStartKind` never sends a foreground start to a running
  instance, and `vpnStopsNow` defers a stop while a foreground start is pending.
- **The silent push stream (be507e1d9): not affected.** We have a 300 s read timeout against a 240 s keep-alive, plus
  the byte stamp, the stale watchdog and the network follow (design 121).
- **The lock-screen bypass through Recents or a notification (c0eab6e63): not affected** as far as the code shows.
  Suspension does the blocking whatever lock-task features are set, and the kiosk list is narrowed during a rule. With
  Android's PIN lock the keyguard is the system's. Worth one device check on the Jelly Star (see the table).
- **The manual APK upload (67ac44e0c's starting point): affected.** The upload is read whole into memory, the cap is
  200 MB, and an over-limit upload says "missing file". This is a real but small bug: a large game APK can't be
  uploaded, and the message misleads.
- **Install failures without a reason (bc95be0d7): affected in the same way.** The PWA shows "Install failed" with
  nothing behind it, and the reason sits only in logcat.

## Direct download (4b64c551e + 07518ac91): the trade-off

Upstream's reason was a Pi Zero on 2.4 GHz Wi-Fi: every byte crossed it twice, and a 1.2 GB game took 15 minutes.

- **Trust: equal, if built as upstream did.** The server still downloads, checks and pins the release, and the phone
  keeps a direct file only when its SHA-256 and size match the server's. Our phone-side `CatalogApkCheck` (package and
  signer) would still run. A mismatch falls back to the server's copy. That fallback is not insecure, because the
  server's copy is the checked one.
- **Data: the same bytes on the phone.** It saves the server's uplink. That uplink matters only when the phone is away
  from home and pulls over Tailscale, and catalog updates are Wi-Fi only by default.
- **Exposure: worse.** Today the launcher's downloads come only from our server (its other outbound traffic is the DNS filter's chosen upstream). With direct download it would
  also contact `github.com`, `objects.githubusercontent.com` and Play's CDN. That shows the kid's IP and installed apps
  to GitHub and Google, it adds hosts the DNS filter has to let through, and it adds a TLS path that design 76's issuer
  pin doesn't cover. Each new host weakens "the phone never contacts arbitrary hosts", even when the bytes are checked.
- **Complexity:** a second download path with links that expire (GitHub's signed asset URLs last minutes), its own
  resume, and a report of the source.

**Verdict:** not worth it on a Proxmox VM with a wired uplink. Revisit only if the user sees slow installs from a large
catalog app.

## Priority list

1. **Manual APK upload streamed to disk** (server, S): fixes a real bug (an APK over 200 MB is held in memory and
   refused as "missing"). It can be done without asking, as a fix.
2. **Install failure reason** (launcher + server, S, one commit with a contract row): a fixed code, worded on the PWA.
   It helps with the first real failures on the Jelly Star.
3. **Ask the user: app groups with shared time limits** (L; the most-wanted parental feature upstream added; needs
   usage access granted on the phone, failing closed).
4. **Ask the user: per-app filter exclusion** (M; only if an app such as Vipps really refuses a VPN; it reverses design
   95's D4).
5. **Device check on the Jelly Star:** under a time rule, a notification tap and the Overview key reach no suspended
   app.
6. **Skip:** direct download, Play as a server-side source with split APKs and the Play sign-in, Lock now/Unlock now
   (design 93 has it), the Pi Zero memory work, the gpapi fixes, the APK verification (we verify signatures), the SSE
   timeout and the VPN race (both already built).
