# kids-launcher-mdm: architecture review before adding features

Reviewed: fork at `/workspace/handy/kids-launcher-mdm` (HEAD `946ff1f`, 1124 commits, v0.23.6 / versionCode 116),
plus the contract side of `kid-phone-server` (`src/handlers/device_api.rs`, `src/models.rs`).
Paths below are relative to `app/src/main/java/com/kidslauncher/mdm/` unless they start with `app/`, `.github/` or `mobile/`.
Nothing was built or run. Android platform claims are marked **[verified]** (checked against
docs or AOSP source during this review), **[belief]** (from memory, not checked), or **[test]** (has to be checked on a device).

## 1. Architecture map

**One process, one Gradle module** (`app/`): the home screen, the Device Owner agent, a VPN DNS filter,
the embedded Tailscale (tsnet), a UnifiedPush relay and journal forwarders all run in it. There are
8.7k lines of Kotlin in total.

| Concern | Where |
|---|---|
| DO receiver | `server/MdmDeviceAdminReceiver.kt` (only `onProfileProvisioningComplete` does anything, :35-63); `res/xml/device_admin.xml` declares only `force-lock` |
| Provisioning | QR admin-extras bundle -> `server/dto/ProvisioningExtras.kt` -> `server/Provisioning.kt:26` (store URL and auth key, connect tsnet, `POST api/devices/enroll`, store bearer token). Manual path: `adb shell dpm set-device-owner ...` plus "Scan setup QR" in Settings |
| Process start | `Application.kt:105-179`: installs a crash handler that calls `exitProcess(1)` (:110), loads prefs, registers the LauncherApps callback (new package -> `AppEnforcer.enforceOnNewPackage`, :54), starts `CommandListenerService` (:159) and `KidVpnService` (:168) |
| Push and poll | `server/CommandListenerService.kt`: a foreground service holding an SSE connection to `api/devices/commands/stream` (:150-183). Each event is a content-free nudge that triggers `performMdmSync`, journal sync and browser sync (:163-171). It also runs a 5-minute `Handler` timer for the same three syncs (:186-197). There is no BOOT_COMPLETED receiver; after a reboot everything starts when Home first launches after unlock. `server/PackageReplacedReceiver.kt` restarts things after a self-update |
| Sync heartbeat | `server/MdmSyncWorker.kt:56 performMdmSync` (no longer a WorkManager worker despite the name), serialized by `syncMutex` (:42). Steps: tsnet connect (:68) -> `GET api/devices/policy` -> on success clear the offline override, cache the PIN hash and DNS settings, refresh the blocklist, dispatch `pendingCommand`, run uninstalls (:74-101) -> fall back to the cached policy (:102) -> `KidModeEnforcer.evaluate` -> `lockReason` pref (:104-107) -> **`AppEnforcer.apply(context, policy)`** (:109) -> status report -> tracked-app updates (self-update included) -> DNS events |
| Policy DTO | `server/dto/PolicyResponse.kt:34-52`: flat, 17 fields, every one defaulted; `ServerJson` uses snake_case naming and `ignoreUnknownKeys` (`server/MdmApi.kt:36-39`). Server mirror: `kid-phone-server/src/models.rs:244-280`, built in `device_api.rs:64-197` |
| Offline policy cache | The entire `PolicyResponse` is stored as JSON in the pref `mdm.kid_mode_policy` (`MdmSyncWorker.kt:411`). `cachedPolicy()` (:436) and `reevaluateLockReasonFromCache()` (:445) read it. The PIN hash and salt are also kept in separate prefs. Everything is in default (credential-encrypted) SharedPreferences through the kapt-generated `LauncherPreferences` (`preferences/LauncherPreferences$Config.java:40-140`; each key also needs an entry in `res/values/donottranslate.xml`) |
| Enforcement | `server/AppEnforcer.kt:81 apply()`. If the device is not DO it does nothing. Under an override or pause, `effectivePolicy` becomes null. It then runs: default HOME (:480), suspend + hide everything off the allowlist across `controllablePackages()` (:39-69, :104-138), kiosk (`setLockTaskPackages` + verified `setLockTaskFeatures`, :173-244), clear radio restrictions (:254), always-on VPN (:296), Private DNS lock (:329), sideload restriction (:364), browser managed config (:392). Schedules: `server/KidModeEnforcer.kt` (pure function, the only unit-tested code: `app/src/test/.../KidModeEnforcerTest.kt`) |
| Lock task | The policy decides it; `HomeActivity.reconcileKioskMode()` (`ui/HomeActivity.kt:205-220`) actually calls `startLockTask()` on `onResume` or when the `kiosk_enabled` pref changes. Kiosk is never engaged when the allowlist is empty (`AppEnforcer.kt:182`) |
| Overrides | Offline PIN (`server/OfflineOverride.kt`, PBKDF2 at 210k iterations, 2 h window, 5 tries then a 15 min lockout); `restrictions_paused` kill switch in Settings (`ui/settings/launcher/SettingsFragmentLauncher.kt:149-170`). Standing rule from CLAUDE.md:113: **every new restriction must be liftable by both** |
| Self-update | The launcher is one more row in the server's `tracked_apps`. `MdmSyncWorker.kt:254 checkForTrackedAppUpdates` downloads it, then `server/AppInstaller.kt` installs it silently through PackageInstaller; the result arrives at `AppInstallReceiver` and state lives in `TrackedAppUpdateState`. The launcher is always installed last (:262-264) |
| Home UI | `ui/HomeActivity.kt`: a RecyclerView of text rows (`ui/minimalist/MinimalistHomeAdapter.kt`), swipe up for the app list, swipe left for Quick Controls |

## 2. Code quality verdict

**Overall:** usable as a base. The structure is simple and flat, and no file is huge (largest are
`server/QuickControls.kt` 588, `AppEnforcer.kt` 493, `MdmSyncWorker.kt` 463, `LocateCommands.kt` 428).
Much of it was debugged against real devices, and the authors were careful about some failure modes:
`controllablePackages` (:39-69), fail-closed kiosk verification (:207-212), and the sync mutex.
Several patterns, however, make new enforcement risky.

1. **Comments are excessive.** About 40% of the lines in the core files are comments (AppEnforcer 211 of 493,
   MdmSyncWorker 149 of 463), and CLAUDE.md is 85 KB of incident narrative. The code itself is fine to read.
   Upstream will expect new code to follow the same habit.
2. **Several paths fail open.**
   - `AppEnforcer.apply(null)` means "no restrictions" (:94, :98). So does a cached blob that fails to decode
     (`MdmSyncWorker.kt:420-427`). A future DTO change that breaks decoding of the old cache therefore unlocks
     the phone until the next successful fetch.
   - On the server, `device_api.rs:68-79` swallows DB errors with `.ok().flatten().unwrap_or(default policy)`.
     A transient SQLite error returns `allowlist: None, kiosk_desired: false`, and the phone treats that as a
     fresh, authoritative policy. This breaks PLAN's "a server outage must never unlock anything". **Fix this before relying on it.**
   - Settings is gated only when a PIN is configured (`ui/settings/SettingsActivity.kt:52,62`). With no PIN, the
     kid can reach Settings (swipe up, then the settings entry in `ui/list/AppListActivity.kt:33`) and turn on "pause all restrictions".
   - Schedules and the override expiry use the wall clock (`KidModeEnforcer.kt:20`, `OfflineOverride.kt:46`),
     and `DISALLOW_CONFIG_DATE_TIME` is not set, so changing the clock bypasses bedtime.
3. **Threading.** `AppEnforcer.apply` is serialized only when it is called from `performMdmSync`. Other callers
   bypass the mutex: Settings' pause toggle (`SettingsFragmentLauncher.kt:167`), `OfflineOverride.activate`,
   and `enforceOnNewPackage` (`Application.kt:55`). `OfflineOverride.activate` runs **on the main thread** from
   `ui/LockActivity.kt:90` and from there calls `apply()` (`OfflineOverride.kt:115`), including the `KidVpnService`
   start/stop. That is the same ANR that was already fixed for the pause toggle (CLAUDE.md:99).
   PBKDF2 PIN checks also run on the main thread. There are 13 ad-hoc `CoroutineScope(Dispatchers.X).launch`
   sites with no lifecycle or error handling.
4. **TsnetClient is not thread-safe.** `connectFromPreferences` (`server/TsnetClient.kt:140-199`) has no
   synchronization, yet it is called from `HomeActivity.onResume` (:174), from every sync (`MdmSyncWorker.kt:68`)
   and from provisioning. Two concurrent calls can each build a `Tsembed` client, and the crash-loop guard can
   count a "crash" that is only an in-flight call. `up()` blocks for up to 30 s (:89,103) **while holding `syncMutex`**,
   which delays enforcement.
5. **Errors are logged and swallowed everywhere** (`setRestriction` :457-472, and others). Nothing is reported
   to the server, so the parent cannot tell whether a restriction actually took effect. New features should
   report the applied state in the status report.
6. **Server contract quirks.** `pending_command` is marked delivered when it is serialized (`device_api.rs:118-127`),
   so it is lost if the phone dies before dispatching it. The policy is a flat struct, and every field needs
   matching edits in the Rust model, a migration, the handler, the PWA template and the Kotlin DTO.
7. **Leftovers and dead code.** Legacy µLauncher preference migrations (`preferences/legacy/Version*.kt`), the
   app-list context menu (`ui/list/apps/ContextMenuActions.kt`), `allowBackup="true"`,
   `usesCleartextTraffic="true"` (`app/src/main/AndroidManifest.xml:72,80`), and an unpinned `go get tailscale.com/tsnet@latest`
   in CI (`.github/workflows/android.yml:47`, which makes builds non-reproducible). `device_admin.xml` lists only `force-lock`
   (harmless for a DO, but untidy). Only one unit test exists. Lint has `abortOnError = false` (`app/build.gradle.kts:99`).
8. **Provisioning gap (likely, [belief]).** The manifest has no `ACTION_GET_PROVISIONING_MODE` or
   `ACTION_ADMIN_POLICY_COMPLIANCE` activities, which Android 12+ expects from a DPC in QR/NFC provisioning. This is a
   plausible cause of the QR failure on the GMS Moto (CLAUDE.md:165), and it matters for the Jelly Star (stock GMS). The `adb dpm` path works.
9. **Packages of interest to the dialer work are hidden.** `controllablePackages()` includes any system app with a
   launcher icon, so the **system Phone/Dialer app gets suspended *and hidden*** whenever it is not on the
   allowlist (:104-130). Today that is how calls get blocked. Once we own the dialer role, hiding the system
   dialer becomes dangerous (see §4).

## 3. Where each feature plugs in

### Common: extending the policy DTO
- Add **nested, nullable** objects to `PolicyResponse` (`server/dto/PolicyResponse.kt:34`). `null` should mean
  "server doesn't know this feature, don't touch device state", which keeps an un-upgraded upstream server working.
  Use `@Serializable` nested classes; snake_case naming applies automatically. Do not use non-null fields
  without defaults: `ServerJson` has no `coerceInputValues`, so a server `null` sent for a non-null field fails the whole decode
  and the phone falls back to cache.
  ```kotlin
  val calls: CallPolicy? = null          // enabled, incoming_mode, outgoing_mode, contacts[{name, number, home, in, out}]
  val smsEnabled: Boolean? = null        // JSON: sms_enabled
  val camera: CameraPolicy? = null       // disabled, per_app_denied[]
  val permissionGrants: List<PermissionGrant>? = null   // {package, permission, state: granted|denied|default}
  val autoDenyNewPermissions: Boolean? = null
  val managedConfigs: Map<String, JsonObject>? = null   // feature 6
  ```
- The offline cache works for free, because the whole response is stored in `kid_mode_policy`. **But** add a
  decode test with an old blob, and consider failing *closed* (keep the last-applied state, don't call `apply(null)`)
  when decoding fails (`MdmSyncWorker.kt:102`).
- Server side, for each feature: a migration on `device_policy` (or new tables for contacts), the `models.rs:244`
  struct, `device_api.rs:182-196`, and the PWA device page. Land the server and phone halves as paired PRs with the same branch name (per PLAN).
- Extend `StatusReportRequest` (`server/dto/StatusReportRequest.kt`) with the applied state (dialer role held, screening
  active, restrictions actually set) so the PWA can show it.

### (1) Default dialer + allowlisted outbound + home contact buttons
- New package `calls/`:
  - `KidInCallService : InCallService` tracks `Call` objects and shows the call UI or a notification.
  - `InCallActivity` handles incoming and ongoing calls (`showWhenLocked`, `turnScreenOn`, proximity wake lock, mute, speaker, DTMF, hang up).
  - `DialerActivity` holds the `DIAL` intent filters and offers **contacts only**, no free keypad except for emergency numbers.
  - `CallPolicyStore` is an in-memory parsed allowlist from the cached policy with number normalization
    (`PhoneNumberUtils`, e.g. `areSamePhoneNumber(a, b, iso)`, API 31; **[belief]** on exact semantics, so write unit tests).
- Manifest, matching the role's required components from AOSP `roles.xml` **[verified]**:
  an activity with `<action DIAL/>` and one with `<action DIAL/><data scheme="tel"/>` (add `category DEFAULT`), plus
  `<service ... permission="android.permission.BIND_INCALL_SERVICE">` with an `android.telecom.InCallService` filter and meta-data
  `android.telecom.IN_CALL_SERVICE_UI=true` (also `IN_CALL_SERVICE_RINGING=true` so we own the ringing UI **[belief]**).
  Add uses-permissions `CALL_PHONE`, `READ_PHONE_STATE`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`.
- Taking the role: in `AppEnforcer.apply` (new `applyDialerRole`), call `dpm.setDefaultDialerApplication(context.packageName)` (§4).
- Outbound enforcement: the dialer UI only offers contacts and calls `TelecomManager.placeCall` (`CALL_PHONE` is granted to the role holder).
  **Backstop:** in `KidInCallService.onCallAdded`, disconnect any outgoing call whose number is neither allowlisted nor an
  emergency number. This catches calls placed by *other* apps that hold `CALL_PHONE`, which bypass our dialer UI. Also deny
  `CALL_PHONE` to every other package (feature 4).
- Home buttons: add a contacts section to `ui/minimalist/MinimalistHomeAdapter.kt` (or a `ConcatAdapter` in `HomeActivity.kt:81-83`)
  fed from `cachedPolicy()?.calls?.contacts?.filter { it.home }`, refreshed on the existing pref listener (`HomeActivity.kt:46-60`).
- `controllablePackages()` / `apply()` (`AppEnforcer.kt:39-138`): **never hide** `TelecomManager.getSystemDialerPackage()`.
  Suspending it is acceptable. Hiding removes the emergency fallback.
- Override and pause: per the standing rule, the PIN override should lift the call allowlist too. Decide this explicitly with upstream.

### (2) CallScreeningService
- `calls/KidCallScreeningService : CallScreeningService` with `<service permission="android.permission.BIND_SCREENING_SERVICE">`
  and an `android.telecom.CallScreeningService` filter **[verified, role component]**. As default dialer it is consulted for all
  incoming calls **[belief; PLAN states it, consistent with docs]**.
- `onScreenCall`: let the call through if the number is on the incoming allowlist. Otherwise
  `respondToCall(setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(true))`.
  Withheld or unknown caller IDs are rejected. Respond fast: Telecom times out and **allows** the call **[belief: ~5 s]**.
  Read from the in-memory store, not by decoding JSON on each call.
- Allow calls for some minutes after an outgoing emergency call (PSAP callback). Record the time in the InCallService.
- **Direct boot:** the app is not `directBootAware` (CLAUDE.md:37), so before the first unlock after a reboot neither
  our InCallService nor screening can run, and calls ring through the system dialer unscreened **[belief, high confidence; test]**.
  Fixing this needs `directBootAware` on the call components and the allowlist mirrored into device-protected storage. Do this as a separate follow-up.

### (3) SMS on/off
- `AppEnforcer`: new `applySmsRestriction` -> `setRestriction(DISALLOW_SMS, !smsEnabled && !overrideActive)`, skipped when the field is null.
  An SMS allowlist would require the default SMS role (`dpm.setDefaultSmsApplication(admin, pkg)` exists for DO, API 29 **[belief]**)
  plus a full SMS app. It is out of scope; PLAN uses Element X instead.

### (4) Camera and per-app runtime permissions
- `applyCameraPolicy`: `dpm.setCameraDisabled(admin, disabled)`. This also kills the launcher's own "Scan setup QR" (`SettingsFragmentLauncher`),
  which is acceptable but should be documented.
- `applyPermissionGrants`: for each entry, `dpm.setPermissionGrantState(admin, pkg, perm, state)`, and **only when
  `getPermissionGrantState` differs** (CLAUDE.md:63: re-issuing a grant re-fires the "your organization allowed..." notification).
  Extend or reuse `QuickControls.selfGrantPermission` (`server/QuickControls.kt:121-143`).
- `setPermissionPolicy(admin, PERMISSION_POLICY_AUTO_DENY)` for "new requests auto-denied". Under an override, revert to `PROMPT` and `DEFAULT` states.
- Granting *sensor* permissions (camera, mic, location) as DO depends on `canAdminGrantSensorsPermissions()` (API 31) **[belief]**. Denying is always allowed.
  The launcher already self-grants location and camera successfully on the test phones, so this probably holds there.
- Collect applied state for the status report. `InstalledApp` (`server/dto/InstalledApp.kt`) could carry requested dangerous permissions so the PWA can offer toggles.

### (5) Hardening
Grep results for the fork:

| Restriction | Already set? |
|---|---|
| `DISALLOW_INSTALL_UNKNOWN_SOURCES` (+`_GLOBALLY`) | **Yes**, `AppEnforcer.kt:364-367`, lifted under override or pause |
| `DISALLOW_SAFE_BOOT`, `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_FACTORY_RESET`, `DISALLOW_ADD_USER`, `DISALLOW_MODIFY_ACCOUNTS`, `DISALLOW_CONFIG_DEFAULT_APPS` | **No** (no hits anywhere) |
| Also absent: `DISALLOW_SMS`, `DISALLOW_OUTGOING_CALLS`, `DISALLOW_CONFIG_DATE_TIME`, `setCameraDisabled`, `setPermissionPolicy`, `setScreenCaptureDisabled`, `DISALLOW_ADJUST_VOLUME`, `DISALLOW_USB_FILE_TRANSFER` | No |

- Add `applyHardening(dpm, admin, policy.hardening, overrideActive)` beside `applySideloadRestriction` (`AppEnforcer.kt:162`), reusing `setRestriction`.
  Make it **server-controllable per device, not hardcoded "always on"**. `DISALLOW_DEBUGGING_FEATURES` blocks adb, which is the
  developers' only recovery and debug path (CLAUDE.md:33-38 documents two boot lockouts recovered via adb and recovery mode).
  `DISALLOW_SAFE_BOOT` plus a crashing launcher with no fallback Home is a brick risk. Lift all of it under the PIN override (standing rule).
- Add `DISALLOW_CONFIG_DATE_TIME` to the list; it closes the clock bypass in §2.2.
- `DISALLOW_CONFIG_DEFAULT_APPS` is API 34 **[belief]**. Apply it only after the dialer role is confirmed held. Whether it also blocks
  the DO's own `setDefaultDialerApplication` is **[test]**; to be safe, set the role, then add the restriction.

### (6) Managed config for the music app
- Generalize `applyBrowserPolicy` (`AppEnforcer.kt:392-420`) into `applyManagedConfigs(policy.managedConfigs)`: convert JSON to a
  `Bundle` (handle nested bundles and arrays), then `setApplicationRestrictions(admin, pkg, bundle)`. Under an override, push an
  empty Bundle (current pattern). Skip packages that aren't installed, and re-push on `onPackageAdded` (`Application.kt:47`).
  The restrictions bundle has size limits for large libraries **[belief]**, so send the library by reference (URL) rather than inline.

## 4. Android gotchas: dialer and screening on a Device Owner

- **Taking ROLE_DIALER without UI:** `DevicePolicyManager.setDefaultDialerApplication(String packageName)` was **added in API 34**,
  callable by a device owner (or the PO of an org-owned profile), and does nothing on devices without `FEATURE_TELEPHONY`
  **[verified, API docs]**. Note there is no admin `ComponentName` parameter. minSdk is 34, so this covers Android 14-16 and
  removes the need for PLAN's "parent taps accept". Confidence that it works silently on stock 14-16 is high; whether it
  succeeds on the Jelly Star's OEM build is **[test]**. Fallback: `RoleManager.createRequestRoleIntent(ROLE_DIALER)` (needs a tap;
  the pattern already exists for ROLE_HOME in `Functions.kt:59-62`). `RoleManager.addRoleHolderAsUser` is `@SystemApi` and needs
  `MANAGE_ROLE_HOLDERS`, so a DO cannot use it **[belief]**. Call it idempotently: check `RoleManager.isRoleHeld(ROLE_DIALER)` first.
- **Role qualification** needs the DIAL and DIAL-tel activities plus an InCallService with `BIND_INCALL_SERVICE` and
  `IN_CALL_SERVICE_UI=true` **[verified, AOSP roles.xml]**. If any is missing, the DPM call throws `IllegalArgumentException` **[belief]**.
- **The InCallService is the whole phone UI.** Ringing, audio routing, hold, DTMF, proximity, the call log, missed-call
  notifications, and video calls (decline video or treat them as audio) are all ours. Incoming calls should use a
  high-priority notification with a full-screen intent.
- **Lock task interaction [test]:** our InCallActivity is in our own package, which is always lock-task-permitted (`AppEnforcer.kt:215`),
  which is good. But with `lockTaskFeatures = 0`, notifications and heads-up are suppressed, so a full-screen-intent or heads-up
  may never show while the kid is inside another pinned app. We need the InCallService to start `InCallActivity` directly. That
  depends on Telecom binding with a background-activity-start allowance **[belief]**. Test this first.
- **Emergency calls:**
  - Telecom handles emergency numbers specially. The default dialer can place them with `placeCall` **[belief: non-default
    apps' ACTION_CALL to emergency is downgraded to DIAL]**. Detect them with `TelephonyManager.isEmergencyNumber` (API 29),
    not a hardcoded 112/110/113 list (that list is fine as a UI shortcut only).
  - If our InCallService fails to bind during an emergency call, Telecom falls back to the **system dialer's** InCallService
    **[belief]**. That is the second reason never to *hide* or uninstall the system dialer (§3.1).
  - Lock task already allows the emergency dialer from the keyguard **[belief]**.
  - Test on the emulator with a test emergency number and never against the real 112.
- **Screening limits:** the CallScreeningService only sees calls Telecom routes to it. A call while our app is dead or updating
  still goes through screening because Telecom binds on demand. During a self-update install, though, there is a window where the
  package is being replaced **[belief]**. The direct-boot gap is covered in §3(2).
- **Other apps placing calls:** any app with `CALL_PHONE` can place a call without our dialer UI. Mitigate by denying `CALL_PHONE` via
  grant state (4) and with the InCallService disconnect backstop. `CallRedirectionService` (ROLE_CALL_REDIRECTION) is cleaner, but
  there is no DPM setter for that role **[belief]**.
- **SIM/carrier:** iceJunior restricts outbound at the carrier. Inbound screening is still required (PLAN).

## 5. Build

- **Toolchain:**
  - JDK 17 (CI uses Temurin 17, `android.yml:16-20`; `compileOptions` 17).
  - Gradle 8.13 wrapper, AGP 8.13.0, Kotlin 2.1.20 with kapt 2.1.21 (`gradle/libs.versions.toml`).
  - compileSdk/targetSdk 36, so the Android SDK needs platform 36 and recent build-tools.
  - No JDK or Android SDK is installed in this environment (`java: command not found`).
- **tsnet.aar:** not checked in. `app/build.gradle.kts:108` hard-depends on `files("libs/tsnet.aar")`. CI builds it with Go 1.23
  (setup-go) + NDK 28.1.13356709 + `gomobile bind -target=android/arm64 -androidapi 34 -ldflags=-checklinkname=0 ./tsembed`, using
  `go get tailscale.com/tsnet@latest` and `wlynxg/anet@latest` (unpinned) (`android.yml:28-61`). There are three ways to get it:
  1. Download the CI artifact `tsnet-aar-<sha>` (`android.yml:79-86`) with `gh run download` from upstream's or our fork's
     Actions. Artifacts expire (default 90 days).
  2. Build it locally with the same commands (Go toolchain + NDK 28.1). `go 1.23` in `mobile/go.mod` vs. the `go get -tool`
     step (Go 1.24+ syntax) relies on GOTOOLCHAIN auto-upgrade **[belief]**.
  3. Make it optional (below).
- **Debug build without tsnet is feasible and small.** Only `server/TsnetClient.kt` imports `tsembed.*` (:12-13). Options:
  - A product flavor `notailnet` with a stub `TsnetClient` (`proxy()` returns null, `connectFromPreferences` returns null, `connected` is false).
  - Or `if (file("libs/tsnet.aar").exists())` plus a source-set switch.

  Everything else falls back to direct networking (`MdmApi.kt:167`, `CommandListenerService.kt:90`), so the server must be reachable
  by plain URL (e.g. `http://10.0.2.2:PORT` from the emulator; cleartext is already allowed). This is a reasonable upstream PR too (contributor builds).
- **Emulator:** the aar is **arm64-only**. This host is aarch64, so an arm64 system image is right. Linux/aarch64 emulator support
  and KVM availability are **[test]**. On x86_64 you would need the stub flavor or an `android/amd64` target.
- **Signing and IDs:** upstream's discipline (CLAUDE.md:73) is to install only CI-signed builds, signed with *their* `ANDROID_DEBUG_KEYSTORE`.
  Our fork needs its own keystore secret, and we keep `com.kidslauncher.mdm.debug`. Self-update only works across builds signed with the
  same key, so never mix upstream and fork builds on one device. CI runs `./gradlew build` (debug + release + lint + unit tests).

## 6. Top risks and recommended PR order

**Risks (highest first)**
1. **Bricking or lockout.** A launcher crash (the uncaught handler calls `exitProcess`, `Application.kt:110`), plus no fallback Home, plus
   `DISALLOW_SAFE_BOOT`/`DEBUGGING_FEATURES`/`FACTORY_RESET`, leaves the device unrecoverable. This has already happened twice without
   the hardening. New InCallService code runs in the same process as the launcher and the tsnet native code.
2. **Fail-open policy paths** (§2.2), especially the server returning a default policy on DB errors, and `apply(null)` on cache decode failure.
3. **Emergency call regressions** from hiding the system dialer or from bugs in our own InCallService.
4. **Lock-task vs. incoming-call UI** visibility (§4) and the **pre-first-unlock** unscreened window.
5. **Concurrency:** unserialized `AppEnforcer.apply` callers, main-thread `apply` in LockActivity, the TsnetClient race. New enforcers add more Binder work to `apply()`.
6. **Upstream fit:** flat DTO, four-place server edits, the comment-heavy house style, and the CLAUDE.md "liftable by override" rule.
   Agree the design in an issue first (PLAN already says so).

**Recommended order**
1. **PR 0 (build):** an optional tsnet / stub flavor, a pinned tsnet version, a decode-compat unit test for `PolicyResponse`. Gets the emulator running.
2. **PR 1 (robustness, small, upstream-friendly):** server: don't fall back to a default policy on DB errors (return 5xx).
   Phone: keep last-applied state when cache decoding fails. Move `OfflineOverride.activate` and `apply` off the main thread
   and through one serialized entry point. Synchronize `TsnetClient.connectFromPreferences`. Add the GET_PROVISIONING_MODE and
   ADMIN_POLICY_COMPLIANCE activities (separate PR if upstream prefers).
3. **PR 2 (hardening, server-toggleable, override-liftable):** `DISALLOW_ADD_USER`, `MODIFY_ACCOUNTS`, `CONFIG_DATE_TIME`, `FACTORY_RESET`,
   `SAFE_BOOT`, and `DEBUGGING_FEATURES` last and default off in dev. Report applied state.
4. **PR 3 (camera + per-app permissions + SMS switch):** low risk, reuses existing patterns, and needed by the dialer (deny `CALL_PHONE` to others).
5. **PR 4 (dialer):** role via `setDefaultDialerApplication`, InCallService/InCallActivity/DialerActivity, contacts DTO plus server tables and PWA,
   home buttons, never hide the system dialer, emergency handling. Test on the emulator, then on the Jelly Star, with lock task on.
6. **PR 5 (call screening):** incoming allowlist, PSAP-callback window, then `DISALLOW_CONFIG_DEFAULT_APPS` once the role is confirmed.
   Follow-up: direct-boot-aware call components.
7. **PR 6 (managed configs, handy-specific until Vibb exists):** generalize `applyBrowserPolicy`.

Not verified in this review: anything requiring a build or device; exact Telecom timeouts, fallback behaviour, BAL exemptions;
`DISALLOW_CONFIG_DEFAULT_APPS` interaction with the DPM dialer setter; the provisioning-activity theory for the GMS QR failure.
