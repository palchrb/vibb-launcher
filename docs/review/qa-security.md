# QA & security review: kids-launcher-mdm + kid-phone-server

Reviewed 2026-10-04 against the forks in `/workspace/handy` (read-only, nothing was built or run).
Threat model: a motivated 10-14 year old with physical access, lots of time and YouTube; a
remote attacker on the network or tailnet.
Legend: **[V]** verified in code, **[I]** inferred (platform behaviour or runtime, not proven
here; test it on a device). Paths are shortened to `L/` = `kids-launcher-mdm/app/src/main/java/com/kidslauncher/mdm/`,
`S/` = `kid-phone-server/src/`.

## 0. Summary

The core idea is sound: local enforcement via suspend+hide, lock task, cached policy, and an
offline PIN. But:

1. **None of the baseline hardening restrictions are set.** Upstream sets no safe-boot,
   debugging, factory-reset, add-user, date/time, USB, VPN-config or Settings-app restrictions [V].
   The only `addUserRestriction` calls are Private DNS (`L/server/AppEnforcer.kt:335`) and unknown
   sources (`:365-366`).
2. **Many paths fail open.** An empty allowlist, a missing policy, a corrupt cached policy, or a
   server DB error all mean "everything allowed".
3. **The schedule (bedtime/screen-time) is a UI overlay, not enforcement.** Apps are not suspended
   during bedtime, so Recents or a notification tap may escape it.
4. **When no PIN is set, a "pause all restrictions" switch sits in the launcher's own Settings with
   no gate.**
5. **Testing is minimal:** one Kotlin unit test file and zero Rust tests.

## 1. Kid bypass vectors

| # | Vector | Status | Evidence / notes |
|---|--------|--------|------------------|
| 1 | Safe mode boot | **Open** | No `DISALLOW_SAFE_BOOT` [V]. In safe mode the launcher (not a system app) is disabled, so no LockActivity, no VPN filter, no lock task. Package suspension and user restrictions are system-side and should persist [I]. The stock Launcher3 has no LAUNCHER-category activity, so `controllablePackages` (`AppEnforcer.kt:57-66`) never suspends it and it becomes HOME in safe mode [I]. |
| 2 | Recovery-mode factory reset | **Open (detect only)** | `DISALLOW_FACTORY_RESET` is not set [V], but it does not stop a recovery wipe anyway. FRP needs a Google account. A wipe is a total bypass, but a visible one: the device stops reporting. The server has no "device silent" alert (only `last_seen_at`, `S/handlers/device_api.rs:380`) [V]. |
| 3 | ADB / developer options | **Open** | No `DISALLOW_DEBUGGING_FEATURES` [V]. If the phone was provisioned with `adb shell dpm set-device-owner`, USB debugging stays on. Upstream provisions **`app-debug.apk`** (`S/handlers/provisioning.rs:55`), and the debug build is `isDebuggable = true` (`app/build.gradle.kts:58-61`) [V]. With adb, `run-as com.kidslauncher.mdm.debug` can edit SharedPreferences, e.g. set `restrictions_paused=true` or read the device token, Tailscale key and PIN hash [I, standard Android]. `adb install` is not blocked by `DISALLOW_INSTALL_UNKNOWN_SOURCES` [I]. |
| 4 | System Settings app | **Mitigated if not allowlisted** | Settings has a launcher icon, so it is suspended unless allowed (`AppEnforcer.kt:62-66,107`) [V]. Every "open settings" intent from allowed apps, notification long-press and QS gear then hits a suspended package [I]. Allowlisting Settings is a total bypass; the server UI should warn about this. |
| 5 | Launcher's own Settings, "pause all restrictions" | **Open if no PIN set** | `SettingsActivity` is gated only when a PIN is configured (`L/ui/settings/SettingsActivity.kt:52,62`) [V]. It is reachable from the app drawer (`L/ui/list/AppListActivity.kt:32-33`) and exported via `APPLICATION_PREFERENCES` (`AndroidManifest.xml:103-112`). The toggle (`SettingsFragmentLauncher.kt:149-173`) lifts everything indefinitely. It is not reported to the server (only `offlineOverrideUsed` is, `MdmSyncWorker.kt:127`) and the server cannot clear it [V]. |
| 6 | Share sheets / intents / links / in-app browsers | **Partial** | Intents into suspended packages are blocked [I]. System packages *without* a launcher icon are never suspended (`AppEnforcer.kt:62-66`) [V], e.g. DocumentsUI, WebView, the print spooler, OEM helpers. In-app WebViews in allowed apps rely only on the DNS filter. Browser lockdown applies only to `com.kidsmdm.browser` (`AppEnforcer.kt:392-420`) [V]. |
| 7 | Notification shade / Quick Settings | **Partial** | With kiosk on, features = HOME, OVERVIEW, NOTIFICATIONS, GLOBAL_ACTIONS, KEYGUARD, SYSTEM_INFO (`S/handlers/devices.rs:189-194`); in lock task Android keeps QS disabled [I]. **With kiosk off, full QS is available** (airplane mode, hotspot, Wi-Fi, location, user switcher). Kiosk is on by default for new devices (`devices.rs:109`) [V]. No `setStatusBarDisabled` is used. |
| 8 | Accessibility / input methods | **Open-ish** | No `setPermittedAccessibilityServices` / `setPermittedInputMethods` [V]. Configuring them needs Settings (suspended), so the risk is low [I]. |
| 9 | Split screen / PiP / Assistant | **Partial [I]** | Suspended apps cannot launch. The voice assistant (long-press power) depends on the OEM; if the Google app or Gemini has a launcher icon it is suspended. Test this on the Jelly Star. |
| 10 | Lock task escape | **Partial** | Pinning needs an allowlist plus `kioskDesired` (`AppEnforcer.kt:182`) and is entered only from `HomeActivity.onResume` (`L/ui/HomeActivity.kt:205-219`) [V]. OVERVIEW is enabled, so recents can switch between allowed apps (intended). If features fail to verify, kiosk is skipped for that cycle (`AppEnforcer.kt:207-211`), which fails open, but deliberately (boot-deadlock history). |
| 11 | **Bedtime/screen-time escape** | **Open [I, high confidence]** | The lock is `LockActivity` drawn on top. `AppEnforcer.apply` never looks at `lockReason`, so allowed apps stay runnable during bedtime [V]. LockActivity is re-launched only from `HomeActivity.onResume` or when the `lockReason` pref *changes* (`HomeActivity.kt:46-52,185`) [V]. Recents (OVERVIEW on) or tapping a notification should open an allowed app that then stays usable until Home is pressed [I]. When the window starts while the kid is inside an app, there is a delay of up to 5 min (sync interval, `CommandListenerService.kt:34`), and the lock only appears if `HomeActivity` is still alive to receive the pref change [I]. |
| 12 | Clock/timezone manipulation | **Partial** | `KidModeEnforcer.evaluate` uses `Calendar.getInstance()` (device wall clock and timezone, `MdmSyncWorker.kt:105,449`) [V]. The PIN lockout and override expiry also use `System.currentTimeMillis()` (`OfflineOverride.kt:46,65,101`) [V]. No `DISALLOW_CONFIG_DATE_TIME`, no `setAutoTimeEnabled`, no server-time sanity check [V]. Date settings are reachable only through Settings (suspended) or adb [I]. Setting the clock forward would skip the PIN lockout; setting it back would extend an override. |
| 13 | Offline PIN brute force (on device) | **Mostly mitigated** | The server enforces 6 or more digits (`S/handlers/devices.rs:665`). On the phone, 5 failures cause a 15-min lockout (`OfflineOverride.kt:19-20,99-105`), about 480 guesses a day, so ~1000 days on average for 10^6 [V]. The lockout uses the wall clock (see #12). PBKDF2-SHA256 with 210k rounds and a 16-byte salt (`S/security.rs:232-251`) [V]. **Offline attack is cheap:** a leaked hash (prefs via adb/run-as, a leaked device token calling `/api/devices/policy`, which returns hash+salt at `device_api.rs:182-183`) falls in minutes on a GPU (10^6 × 210k). PBKDF2 does not help with a 6-digit keyspace. |
| 14 | Disabling the VPN/DNS filter | **Partial** | Always-on without lockdown (`AppEnforcer.kt:305`) [V]. Gaps: (a) DNS is unfiltered whenever the service is down (crash, `establish()` null, boot race), during override/pause, or when the admin toggles the filter off. (b) Only IPv4 UDP/53 to 192.0.2.2 is intercepted (`KidVpnService.kt:58-61,95-97`) [V], so any app with its own resolver, DoH or hardcoded 8.8.8.8 bypasses it (Firefox, many games and video apps), as do IP literals. (c) No `DISALLOW_CONFIG_VPN` [V]; low risk while Settings is suspended. (d) Each query opens a new DoT socket to 1.1.1.1:853 (`DnsFilterEngine.kt:92-126`). A network that blocks port 853 (school, captive portal) means **no DNS at all**: fail-closed, but an availability bug. |
| 15 | Uninstall / clear data of the launcher | **Mitigated by platform [I]** | Android blocks uninstalling or clearing data of the active device owner. No explicit `setUninstallBlocked` is needed. Force-stop needs Settings or adb. |
| 16 | SIM swap | **Open, low impact** | No telephony restrictions [V]. A new SIM only gives connectivity, not apps. That matters later for the call allowlist (PLAN phase 2): check `DISALLOW_CONFIG_MOBILE_NETWORKS` and the SIM-change event. |
| 17 | Second user / guest | **Unverified** | No `DISALLOW_ADD_USER` / `DISALLOW_USER_SWITCH` [V]. Android may set `DISALLOW_ADD_USER` by default on fully managed devices, but I am not sure; set it explicitly. |
| 18 | USB file transfer | **Open, low/med** | No `DISALLOW_USB_FILE_TRANSFER` / `setUsbDataSignalingEnabled` [V]. Media can be copied on/off (e.g. videos for an allowed gallery or player). APKs are blocked by the unknown-sources restriction. |
| 19 | Airplane mode to avoid sync | **Enforcement OK, visibility open** | The cached policy keeps applying (`MdmSyncWorker.kt:102`) [V]. Remote lock/wipe/locate stop working, and there is no alert server-side. With kiosk on, QS is unavailable [I]; with kiosk off, the airplane tile is one tap away. |

## 2. Remote / server risks

- **Device token** [V]: 256-bit `OsRng`, stored server-side only as SHA-256 (`S/security.rs:62-72`).
  Good. No expiry or rotation; revoked only by re-enrolling or deleting the device. Stored in
  plaintext SharedPreferences on the phone with `allowBackup="true"` (`AndroidManifest.xml:73`).
- **Transport / policy integrity** [V]: policies, commands and app updates are **unsigned**. They
  rely entirely on transport. `usesCleartextTraffic="true"` (`AndroidManifest.xml:80`) and
  `createMdmApi` falls back to the plain network if tsnet is not connected (`L/server/MdmApi.kt:109-115`).
  The tsnet crash guard deliberately skips connecting after 2 crashes (`TsnetClient.kt:169-176`).
  With the intended `https://…ts.net` URL (`templates/provisioning_settings.html:17`), a MITM needs a
  valid cert, so the risk is low. With an `http://` server URL, an attacker on the phone's network
  (e.g. the kid's own laptop hotspot) could serve a forged policy. That includes `allowlist:null`,
  i.e. full unlock, plus a `wipe` command and arbitrary tracked-app APKs installed silently through
  Device Owner `PackageInstaller` (`AppInstaller.kt:51-53`) [I]. **Fix:** require https, and add
  network security config pinning or a signed policy.
- **Admin auth** [V]: Argon2 defaults (`security.rs:30-45`), minimum password length 12, forced
  change, mandatory TOTP (`require_full_auth`, `security.rs:372-392`). TOTP is a separate pending
  step (`auth.rs:129-134`). Account lockout after 5 failures / 15 min.
  Weaknesses:
  - `client_ip` trusts the *first* `X-Forwarded-For` entry (`security.rs:77-85`), so the IP ban is
    bypassable by spoofing the header. Account lockout then becomes a way to lock the parent out
    (DoS).
  - The session ID is not cycled on login (`auth.rs:133,147,277`, no `cycle_id`).
  - No TOTP replay tracking and no recovery codes [I].
  - Unknown usernames skip Argon2, which leaks via timing.
- **CSRF** [V]: no CSRF tokens anywhere (`grep csrf` is empty). Protection depends on
  tower-sessions' default `SameSite=Strict` [I, library default; verify the `Set-Cookie` header].
  Acceptable on a tailnet-only server; not acceptable if it is ever exposed via Funnel.
  Sessions last 30 days of inactivity (`main.rs:102-104`).
- **Enrollment code** [V]: 8 chars from 31 symbols (~39.6 bits), slight modulo bias
  (`security.rs:50-56`), 30-min expiry (`devices.rs:15`, `provisioning.rs:125`), single use.
  `/api/devices/enroll` is public with no rate limit (`main.rs:355`), but online guessing is
  infeasible. The check-then-update is not atomic (`device_api.rs:26-55`); impact is minor.
- **SSE endpoint** [V]: behind `require_device_token` (`main.rs:366-405`) and filtered to its own
  device id. Events carry no payload (`device_api.rs:608-619`). Fine.
- **Wipe authorization** [V]: needs a full admin session plus typing the device name
  (`S/handlers/locate.rs:193`). No TOTP step-up, so a stolen 30-day session can wipe. The phone
  executes only from a fresh fetch, never from cache (`MdmSyncWorker.kt:91-95`). Good. Wipe is
  `wipeData(0)` (`LocateCommands.kt:421-426`).
- **Tailnet exposure** [V/I]: one global Tailscale auth key is embedded in every QR and stored on
  the phone (`S/handlers/settings.rs:38`, `Provisioning.kt:30-32`). If it is reusable and gets
  extracted, anyone can join the tailnet and reach the admin login. Use one-off, tagged,
  pre-approved keys plus an ACL: `tag:kidphone` → server:443 only. Admin and device API share one
  port, so also consider a separate listener for `/api/devices/*`.
- **Supply chain** [V]:
  - QR provisioning downloads upstream's rolling `pre-release/app-debug.apk` (`provisioning.rs:55`).
  - Launcher CI uses `gomobile@latest` and `tailscale.com/tsnet@latest` (`.github/workflows/android.yml:43-51`).
  - Server self-update pulls upstream GitHub releases (`S/handlers/system_update.rs:30`), and the
    install hint is `curl … master/deploy/install.sh | sudo bash` (`security.rs:305`).

  Forks must point all of these at our own artifacts and pin versions.
- **Secrets at rest on the phone** [V]: device token, Tailscale auth key, PIN hash+salt, cached
  policy and `restrictions_paused` sit in default SharedPreferences; tsnet node state is in
  `filesDir/tailscale`. Nothing is in Keystore. Protected only by app sandboxing, which is
  defeated by a debuggable build plus adb.
- **XSS** [V, spot check]: Askama autoescapes. The only `|safe` is the server-generated QR SVG
  (`templates/provision_qr.html:44`). Backup filenames reject `/` and `..` (`backups.rs:183-185`).

## 3. Fail-safe behaviour (does anything fail OPEN?)

| Situation | Result | Evidence |
|---|---|---|
| Server/network down | Cached policy keeps applying. **Good.** | `MdmSyncWorker.kt:102` |
| Never synced / no policy | Everything open, but the VPN is on | `KidModeEnforcer.kt:18`, `AppEnforcer.kt:98,157` |
| **Corrupt cached policy JSON** | **OPEN.** Decode returns null, so all apps are unsuspended and kiosk is turned off | `MdmSyncWorker.kt:420-427` → `AppEnforcer.apply(null)` |
| **Empty allowlist `[]`** | **OPEN.** Treated as "no restriction". Unchecking the last app in the UI writes `[]` | `AppEnforcer.kt:98`, `S/handlers/devices.rs:562-599` |
| **Server SQLite error / missing `device_policy` row** | **OPEN.** Returns a default policy as a fresh 200: no allowlist, no schedule, and the cached PIN hash is overwritten with null | `S/handlers/device_api.rs:68-82,108-114`; `MdmSyncWorker.kt:83-84` |
| Unparseable `allowlist_json` on server | **OPEN** (allowlist → None) | `device_api.rs:84-87` |
| HTTP error from server | Cache is kept (Retrofit `body()` is null). **Good.** | `MdmSyncWorker.kt:407-418` |
| Launcher crash loop | Suspension and restrictions persist in the OS. Schedules, LockActivity and the VPN stop working. The global handler exits the process (`Application.kt` uncaught handler). As the pinned HOME app, the device becomes unusable; recovery needs adb or a factory reset [I] | |
| Failed self-update | PackageInstaller is atomic, so the old version stays; retried after 1 h (`MdmSyncWorker.kt:252,271-276`) [V]. A *successful* update to a broken build leads to the crash loop above. No canary or rollback | |
| tsnet crashes | Connection skipped, cached policy applies (fail-closed). Possible cleartext fallback, see §2 | `TsnetClient.kt:169` |
| Override PIN entered | Everything open for 2 h, including VPN and sideloading; cleared on the next successful sync | `OfflineOverride.kt:82,170-177`, `AppEnforcer.kt:93,157-164` |

## 4. Test coverage and a minimal strategy

**Today** [V]:
- **Android:** `app/src/test/.../KidModeEnforcerTest.kt` with 7 tests (null policy, no fields,
  overnight bedtime, start==end, weekday window, weekend window). No `androidTest` sources.
  CI runs `./gradlew build` (`android.yml:72`), which includes unit tests and lint.
- **Rust:** **zero** `#[test]`. CI only runs `cargo build --locked` and `cargo fmt --check`
  (`kid-phone-server/.github/workflows/ci.yml:16-17`). No clippy, no tests.

**Unit tests first (JVM / `cargo test`, cheap, no device):**
1. `KidModeEnforcer`: DST change days, midnight edges (23:59/00:00), Fri→Sat across a wrapped
   window, bedtime overlapping screen time, timezone set on the `Calendar`.
2. **Extract a pure `computeEnforcementPlan(policy, installed, overrideActive, paused) -> {suspend, unsuspend, kiosk, vpn}`**
   out of `AppEnforcer.apply`. Then test: `[]` vs null allowlist, own package never suspended,
   override → open. Write explicit tests that pin the *desired* fail-closed semantics before
   changing them.
3. Policy parsing: `ServerJson` with a missing field, unknown fields, `{}`, a truncated blob. Today
   these all mean "open"; the tests should document that, then the fix.
4. `OfflineOverride`: needs a prefs/clock seam. Cover the server↔client PBKDF2 vector (share one
   fixed test vector between Rust `hash_pin` and Kotlin `verifyPin`), lockout after 5, lockout
   expiry, odd-length hex.
5. `DnsFilterEngine.classify`: suffix matching, trailing dot, case, `evil-example.com` vs
   `example.com`.
6. Rust: `security.rs` (token hash, enrollment alphabet, `client_ip`), the `policy()` handler with
   `#[sqlx::test]` (default row, global vs custom schedule, DB error should become 5xx), enroll
   expiry and single use, the auth flow (password → pending → TOTP), and a device-token-required
   check on every `/api/devices/*` route.
7. Add `cargo test` and `cargo clippy -D warnings` to CI.

**Instrumented / emulator (API 34 image, `dpm set-device-owner` via adb in the test setup):**
suspend/hide round-trip including un-hide of a hidden app, lock task entry with the feature mask,
`addPersistentPreferredActivity`, VPN establish plus a blocked lookup, override activation and
expiry, policy-change → LockActivity, and self-update over the same signing key.

**Manual checklist on the real phone (Jelly Star), per release:**
- [ ] Safe-mode boot (power menu long-press and hardware key combo): what is reachable?
- [ ] Recovery menu reachable? (Document it; it cannot be blocked.)
- [ ] Developer options: tap build number (if Settings is reachable). Is adb off after provisioning?
- [ ] Bedtime starts while inside an allowed app; Recents → allowed app during bedtime;
      notification tap during bedtime.
- [ ] Kiosk on: pull down shade (QS should be absent), power menu items, Assistant
      (long-press power / corner swipe).
- [ ] From each allowed app: share sheet, "open link", "settings" links, file picker, camera
      intent, print.
- [ ] Unlock-code dialog: 5 wrong → lockout; reboot during lockout; airplane mode then override.
- [ ] Launcher Settings reachable without a PIN? Pause toggle reported?
- [ ] VPN: kill the launcher process; after a reboot, DNS before the VPN is up; DoH-capable app
      bypass; network blocking port 853.
- [ ] Airplane mode / Wi-Fi off for a day: does the schedule still apply? Does the server show the
      device as stale?
- [ ] USB to laptop: MTP offered? adb authorised?
- [ ] Guest/user switcher visible? Add user?
- [ ] Factory reset from Settings (if reachable) and from recovery; FRP behaviour.
- [ ] SIM removal or swap.
- [ ] Wipe/lock/ring end-to-end; server-down for 24 h; restore a server backup.
- [ ] Self-update to a deliberately crashing build (on a test phone!).

## 5. Prioritized fixes (U = worth upstreaming, H = handy-only)

**P0, before giving the phone to a kid:**
1. **U** Always-on hardening in `AppEnforcer.apply` (not lifted by override): `DISALLOW_SAFE_BOOT`,
   `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_FACTORY_RESET`, `DISALLOW_ADD_USER`,
   `DISALLOW_USER_SWITCH`, `DISALLOW_CONFIG_DATE_TIME` + `setAutoTimeEnabled(true)`,
   `DISALLOW_CONFIG_VPN`, `DISALLOW_USB_FILE_TRANSFER`, `DISALLOW_MODIFY_ACCOUNTS`,
   `DISALLOW_CONFIG_DEFAULT_APPS` (later), `DISALLOW_INSTALL_UNKNOWN_SOURCES` (keep).
   Use a server-side "maintenance mode" toggle rather than the PIN to relax these.
2. **U** Fail closed:
   - Treat `allowlist == []` as "only the launcher".
   - Corrupt cache → keep the last applied state (don't call `apply(null)`).
   - Server `policy()` must return 5xx on DB errors and never default to open.
   - Never clear the cached PIN hash on a response that looks like a default.
3. **U** Real schedule enforcement: during `LockReason != NONE`, suspend everything except the
   launcher, dialer and emergency apps (`setPackagesSuspended`), or `setLockTaskPackages([own])`.
   Re-launch LockActivity from the sync service, not only from HomeActivity.
4. **U** Gate the launcher Settings even without a PIN (refuse to open, or require server-side
   unlock). Report `restrictions_paused` in the status report and let the server clear it.
5. **H/U** Build and provision a **release** (non-debuggable) APK signed with our own key, from our
   own release URL. Pin `tsnet` and `gomobile` versions.

**P1:**
6. **U** Refuse `http://` server URLs; set `usesCleartextTraffic=false` and a network security
   config; never fall back from tsnet to the plain network for an `*.ts.net` URL.
7. **U** Don't trust the first XFF hop: use the peer address, or the *last* hop only when the peer
   is 127.0.0.1. Call `session.cycle_id()` on login. Add a TOTP step-up for wipe and PIN change.
8. **U** Protect the PIN hash at rest: HMAC it with an Android Keystore key on receipt, store only
   that. Consider 8+ digits. Use elapsed realtime plus a persisted counter for the lockout, not the
   wall clock.
9. **H** Tailscale: one-off tagged auth keys, an ACL limiting phones to the server, and a separate
   listener for the device API.
10. **U** Server alert when a device is silent for more than N hours, or reports override/pause.
11. **U** Tests and CI per §4 (`cargo test`, `clippy`, the pure enforcement-plan extraction).

**P2:**
12. **U** DNS filter: answer TCP/53, add an IPv6 DNS route, reuse the DoT connection, and fall back
    to a plain/second upstream when 853 is blocked. Consider blocking known DoH endpoints by
    domain. Pick between a lockdown-capable design (default route through the VPN) and
    parent-settable Private DNS (PLAN).
13. **U** Self-update safety: a canary device first, and rollback to the cached previous APK if the
    new version fails to report within X minutes. Not trivial with the PackageInstaller downgrade
    rules.
14. **U** Server UI warning when Settings, Play Store, a file manager or a browser without the
    managed config is allowlisted.

## Uncertainties

- Safe-mode, bedtime-escape, QS-in-lock-task, Assistant and guest-user behaviours are inferred
  from Android semantics. Verify each on the Jelly Star (OEM builds differ).
- The CSRF assessment rests on the tower-sessions default SameSite; check the actual `Set-Cookie`.
- I did not review: journal/browser-history ingestion, UnifiedPush relay internals, the backups
  restore path, the root-side watcher scripts in `deploy/`, or the Go `tsembed.go`.
