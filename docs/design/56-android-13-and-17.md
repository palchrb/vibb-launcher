# 56 - Android 13 (API 33) and Android 17 (API 37) next to today's 14-16 (assessment)

Asked 2026-10-21 (the user: "hva skal til for å gjøre alt kompatibelt med android 13 og 17 i tillegg?"). An
assessment, not a plan of record: nothing here is built. Both Android apps: the launcher (Vibb One, `launcher/`,
`minSdk 34`, `compileSdk`/`targetSdk 36`, `launcher/app/build.gradle.kts:89-97`) and the music app (`music/`,
`minSdk 34`, `compileSdk`/`targetSdk 36`, `music/app/build.gradle.kts:30-35`). The server is unaffected, except for the
optional status field in §4.4.

How each claim was checked:
- **[lint]**: lint was run on main `397d0c5e` (2026-10-21) in a throwaway worktree with `minSdk = 33` and
  `checkOnly NewApi, InlinedApi, UnusedAttribute, WrongConstant` (`lintDebug`, both apps; the launcher's debug build
  uses the tsnet stub). Lint reported 22 errors and 14 warnings for the launcher and 1 error for the music app. No
  `UnusedAttribute` or XML findings: the manifest and resources use nothing newer than 33.
- **[code]**: read in the sources at the file:line given.
- **[docs]**: developer.android.com, read 2026-10-21: `about/versions/17/behavior-changes-17`,
  `.../behavior-changes-all`, `.../changes/bg-audio`, `work/versions/android-17`.
- **[3rd]**: a third-party source (Bayton's Android Enterprise tracker, Unihertz's blog, heise). Treat it as likely,
  not certain.
- **[unverified]**: from memory of AOSP or general knowledge, not re-read for this assessment. Each one is a check to
  do before relying on it.

## 0. Summary

| | Android 13 (`minSdk 33`) | Android 17, `targetSdk 36` (run on it) | Android 17, `targetSdk 37` (adopt it) |
|---|---|---|---|
| Code | Launcher: 22 lint errors in 12 files ([lint]), plus 3 Telecom callbacks lint can't see and the tsnet AAR's minSdk. Music: 1 error | None known to be needed | AGP 9.1+ migration first, then 3-4 behaviour items |
| Lost on that version | `DISALLOW_CONFIG_DEFAULT_APPS`; the silent dialer role; data-sensitive views for accessibility; the Clock "Stop" without background-start denial | Nothing known | Nothing, if the local-network grants land with it |
| Risk | Medium. The guards are easy. The kiosk and lock behaviour on AOSP 13 internals is the unknown | Low to medium: the kiosk's reliance on AOSP internals | Medium: build-chain churn, background audio |
| Effort | 1 implementation round + 1 API 33 emulator round (+ fix round). Then a lasting test cost | 1 emulator round on an API 37 AVD | 1 build-chain round + 1 behaviour round + emulator round |
| Recommendation | **Not by default.** Only for a concrete phone that can't leave 13. A Jelly Star should take its OTA first | **Yes, now**: cheap, and Pixels already run 17 | **Not early.** No Play deadline binds us. Do the forward-compatible bits now (§3.4) |

## 1. Context: who runs what

- **Android 13 is out of security support.** Google's March 2026 bulletin no longer lists 13. The last patch was
  December 2025 or March 2026; the sources disagree [3rd: heise, teltarif]. A kid phone on 13 runs an unpatched OS
  under our kiosk.
- **The Jelly Star** launched in 2023 on Android 13. Unihertz announced an Android 16 OTA for it and the Titan 2
  [3rd: unihertz.com blog, "planned Android 16 upgrades"; the rollout date isn't certain]. The repo already treats the
  test unit as 16: "Jelly Star (Android 16, GMS)" (`docs/design/02-calls.md:9`). Check with
  `adb shell getprop ro.build.version.sdk` on each unit before provisioning.
  - A Jelly Star still on 13 can take the OTA in Android's own setup and then be factory-reset for QR/adb provisioning.
    That costs one OTA per phone, against permanent 13 support in the code.
- **Cheap new phones.** New GMS devices in 2025-26 generally ship 14-16 [unverified: GMS launch-version rules]. 13 is
  mostly old stock, second-hand phones, and Go-edition handsets.
- **Android 17** is API 37. It went stable on Pixels in June 2026. Other OEMs follow from late Q3 2026. QPR1 (37.1) is
  in beta and QPR2 (37.2) is expected in December 2026 [3rd: Bayton; docs: the versions page lists 37.1/37.2 betas].
  Minor SDKs don't change `SDK_INT`: `Build.VERSION.SDK_INT_FULL` (API 36) tells 37.1 from 37.0.
- **Google Play's target-37 requirement** is expected mid-2027 [3rd]. It doesn't bind us: both APKs come from GitHub
  Releases through our own catalog (launcher `CLAUDE.md`, "Established discipline").

## 2. Android 13 (`minSdk 33`)

### 2.1 Launcher: every API newer than 33 in `src/main`

Lint at `minSdk 33` [lint], plus what lint can't see (callbacks, the native library). The "On 13" column is the guard
and fallback each needs.

| # | Where | API (level) | What it does today | On 13 |
|---|---|---|---|---|
| 1 | `server/AppEnforcer.kt:698`, `:708` | `DevicePolicyManager.setDefaultDialerApplication` (34) | Takes the dialer role silently, and hands it back on unmanage | **No device-owner API.** `RoleManager.addRoleHolderAsUser` is a system API. Take the role with adb at provisioning (`cmd role add-role-holder android.app.role.DIALER me.vibb.launcher`, like REDIRECTION and SCREENING today), or Home's existing once-a-day role prompt (`rolePromptFor`). The hand-back on unmanage becomes "the parent picks the default in Settings". See §2.3 |
| 2 | `server/AppEnforcer.kt:695`, `:721` | `UserManager.DISALLOW_CONFIG_DEFAULT_APPS` (34, a constant) | Locks Settings' default apps while we hold the dialer role | **No such restriction.** AOSP 13's DPMS ignores an unknown key [unverified: `UserRestrictionsUtils.isValidRestriction`]. Guard it and report it. See §2.3 |
| 3 | `server/LocateCommands.kt:413` | `wipeDevice` (34) | Remote wipe | **Already handled**: `wipeCall(sdkInt)` returns `WIPE_DATA` below 34 (`server/WipePlan.kt:26-28`). AOSP 13's `wipeData` from a device owner is a factory reset [unverified], and needs `<wipe-data />`, which is declared. `remoteWipeReady(33, granted)` returns the granted policy, as on 34. Only lint's suppression is missing. Never test a wipe casually |
| 4 | `lock/VisibleSender.kt:29` (+ the `ALLOWED` constant) | `ActivityOptions.setPendingIntentBackgroundActivityStartMode` (34) | The sender's opt-in for the VoIP, music-session and glance sends | **Not needed**: before 14 a visible sender's background-start privilege passed to the PendingIntent by default [unverified on 13, emulator check]. Return `makeBasic()` without a mode below 34 |
| 5 | `lock/ClockActions.kt:24` | same, with `MODE_BACKGROUND_ACTIVITY_START_DENIED` (34) | The ended timer's "Stop" broadcast, **with background starts denied**, so the clock's receiver can't open a screen over the lock (design 42) | **No way to deny on 13.** A visible sender implicitly lends its privilege to the broadcast's receiver [unverified], and with the kiosk off the clock app is a pinned PIN-lock helper (`pinLockHelpers(clockApp)`). Hide the Stop button below 34 (the tile still shows "Tiden er ute") rather than send without the denial |
| 6 | `lock/GlancePanel.kt:480`, `ui/ParentPinKeypad.kt:50`, `:118` | `View.setAccessibilityDataSensitive` (34) | The glance rows and the parent-PIN keypad hidden from non-tool accessibility services | **No equivalent.** Skip it below 34. Residual: an enabled accessibility service could read glance rows and the keypad's key presses. Mitigated by what already exists: `setPermittedAccessibilityServices` = the allowlist (also inside windows), and foreign access (a non-system service outside the allowlist) refuses the parent PIN (`server/ForeignAccess.kt`). `FLAG_SECURE` stays |
| 7 | `lock/PinLockActivity.kt:277` | `Activity.overrideActivityTransition` (34) | No open transition for the lock (bug 25b-B) | `overridePendingTransition(0, 0)` below 34 (deprecated in 34: `@Suppress`) |
| 8 | `lock/FontScaleRefresh.kt:34` | `TypedValue.deriveDimension` (34) | Re-sizes the lock's texts after a font-scale change | Below 34 font scaling is linear (non-linear scaling is 14+), so sp = px / (`density` × `fontScale`) of the old metrics. A one-line fallback in the pure `rescale` |
| 9 | `timerules/ScreenTime.kt:64` | `java.time.LocalDate.ofInstant` (34 without desugaring) | The screen-time ledger's date | `Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()` works on every level. Pure and JVM-tested. Change it unconditionally |
| 10 | `music/MusicSync.kt:164` | `BroadcastOptions.makeBasic().setShareIdentityEnabled(true)` + `Context.sendBroadcast(Intent, String, Bundle)` (34) | The `BRIDGE_CHANGED` nudge carries the launcher's identity, which the music app checks | Plain `sendBroadcast(intent)` below 34. The music side's check needs the matching fallback (§2.5) |
| 11 | `calls/OngoingCalls.kt:137-156` | `CallEndpoint`, `InCallService.requestCallEndpointChange`, `CallEndpointException` (34) | The call screen's Speaker toggle and its state | Below 34: `InCallService.setAudioRoute(CallAudioState.ROUTE_SPEAKER / ROUTE_WIRED_OR_EARPIECE / ROUTE_BLUETOOTH)` and the route from `CallAudioState` (both deprecated in 34, `@Suppress`). **The largest item**: two code paths in the in-call UI, which needs a real call to test (§2.6) |
| 12 | `calls/KidInCallService.kt:222-227` (not seen by lint: overrides) | `onMuteStateChanged`, `onCallEndpointChanged`, `onAvailableCallEndpointsChanged` (34 callbacks) | Feed `OngoingCalls.audioChanged` (mute, route, available routes) | **Never called on 13**: mute and speaker would show stale state. Override `onCallAudioStateChanged(CallAudioState)` (deprecated in 34) and feed the same `audioChanged` from it below 34. ART on 13 loads the class: methods whose signature names the missing `CallEndpoint` fail verification softly and are never called [unverified: emulator check] |
| 13 | `badges/VoipCallReader.kt:38` | `Notification.EXTRA_CALL_TYPE` (34, a constant) | Tells an incoming CallStyle apart | **Fine as is.** The constant is inlined (`"android.callType"`) and CallStyle writes that extra since 31 [unverified]. Suppress `InlinedApi` with a comment |
| 14 | `.github/actions/build-tsnet/action.yml:54` | `gomobile bind ... -androidapi 34` | Builds `tsnet.aar` | gomobile writes `minSdkVersion` = `-androidapi` into the AAR's manifest, so a `minSdk 33` release build would fail at manifest merge [unverified: the generated manifest; lint used the stub, so it didn't see this]. Set `-androidapi 33`. Go supports far older levels |

Notes on the rest:
- Every other `SDK_INT` check in `src/main` is already below 34 or above it, for example `Notifications.kt:206`
  (`< TIRAMISU`) and `VoipRinger.kt:42` (`>= BAKLAVA`). `pendingAdminPolicies(sdkInt)` and `remoteWipeReady(sdkInt)`
  treat 33 like 34, which is right for the pre-policy-engine DPMS [unverified].
- **Already fine on 13** [code + API levels]: `registerReceiver` with `RECEIVER_EXPORTED`/`NOT_EXPORTED` (33),
  `POST_NOTIFICATIONS` (33), `USE_EXACT_ALARM` (33), `LocaleManager` (33), `enableOnBackInvokedCallback` (33),
  `TaskDescription.Builder` (33), `setRecentsScreenshotEnabled` (33), `getPackageArchiveInfo` flags,
  `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (30), `setKeyguardDisabled`, `setStatusBarDisabled`,
  `setPermissionGrantState`, `setPermittedAccessibilityServices`/`InputMethods`, `resetPasswordWithToken`,
  `setLocationEnabled` (30), `setSystemSetting` (28), `registerBestMatchingNetworkCallback` (31), the photo picker
  (`ACTION_PICK_IMAGES`, a kiosk helper).
- **Foreground services**: 13 has no mandatory FGS types. `foregroundServiceType="specialUse"` and
  `FOREGROUND_SERVICE_SPECIAL_USE` on the anchor and the VPN (`AndroidManifest.xml:60-68`, `:417-420`, `:503`) are
  ignored or harmless there: the 2-argument `startForeground` takes the manifest's bits, and 13 doesn't validate
  unknown ones [unverified]. 15's 6-hour `dataSync` cap doesn't exist on 13. A device owner may start an FGS from the
  background on 13 too.
- **Window and back behaviour** is the same as on our 14 floor. Edge-to-edge is enforced only on 15+ for targets 35+,
  so `ui/KidInsets` already covers a window that isn't edge-to-edge. 13 runs predictive back's callback without the
  animation.
- **Look**: 13 caps the font scale at 1.3 (2.0 is 14+), so the 2.0 layout models are an upper bound there too.

### 2.2 Music app

- `MusicApp.kt:63`: `BroadcastReceiver.getSentFromPackage` (34) [lint] is the music half of item 10. Below 34 there
  is no sender identity. Options:
  - (a) accept the nudge unauthenticated below 34. It carries nothing and only starts a sync, which talks to the
    launcher over the signature-protected bridge. Rate-limit it.
  - (b) put a sender permission on the receiver. Awkward: two launcher build names, and a defining app must also
    `uses-permission` its own permission.
  Recommendation: (a), with a comment that qa-21-step3-code L7's identity check is 34+.
- Nothing else. Media3 1.7.1, the Compose BOM 2025.06.01, Room, WorkManager and core-ktx 1.17 all support far below
  33. `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is ignored on 13 (`mediaPlayback` itself is API 29).
- The Bluetooth reflection in `bluetooth/BluetoothLink.kt:167` (`BluetoothA2dp.connect`/`setActiveDevice`) has the
  same open question as the launcher's (§2.3).

### 2.3 Device-owner specifics on 13

- **Default apps (items 1-2) - the real loss.** On 14+ `DISALLOW_CONFIG_DEFAULT_APPS` makes PermissionController
  refuse every exclusive role request (launcher `CLAUDE.md`, "Dialer role"). On 13 there is no such lock:
  - Settings › Default apps, wherever Settings is reachable, can change the phone, SMS and browser defaults.
  - Any allowed app can show the role request dialog. PermissionController is a pinned kiosk helper for permission
    dialogs, so the dialog shows. A kid tapping "Set as default" in an allowed third-party dialer takes our dialer role.
  - We can't take the role back silently (item 1).
  - What still holds:
    - HOME stays ours: a persistent preferred activity outranks the default-home choice.
    - REDIRECTION and SCREENING are separate roles, granted with adb at provisioning. Unless the kid also moves those
      through prompts, screening still rejects disallowed incoming calls and redirection still cancels disallowed
      outgoing ones.
    - The existing rule "`DISALLOW_OUTGOING_CALLS` (emergency exempt) when our dialer isn't in place" fails closed:
      outgoing calls stop until a prompt or adb gives the role back.
  - Net: on 13 a lost role is a usability failure (no calls out), not a hole. The server should say why (§4.4).
  - Side effect: with no restriction, Home's role prompts for REDIRECTION and SCREENING work on 13. On 14+ they never
    show while we hold the dialer role.
- **Lock task** works the same on 13 at the API level. The kiosk also relies on AOSP internals that were read for
  14/15/16 only:
  - `LockTaskController`'s emergency exemption for the system dialer under `LOCK_TASK_FEATURE_KEYGUARD`.
  - The LOCKED status-bar flag map that `StatusBarHealTest` mirrors (113).
  - The recents provider (`config_recentsComponentName`) and quickstep starting inside lock task (16d).
  - FallbackHome at boot.
  - `BroadcastSkipPolicy`'s NOT_EXPORTED denial (the "14 :567" line refs).
  - `SuspendPackageHelper`'s protected packages.
  Each needs a re-read of AOSP `android13-release`, or the API 33 emulator round (§2.6) [unverified].
- **Lock flash (design 32).** `docs/testing/emulator.md:1103` already warns: "On Android 13 (legacy transitions),
  repeat (b) and (d) carefully". 13 doesn't have the shell transitions the lock-up order was tuned on. Expect
  differences in what flashes at screen-on.
- **Keyguard and the PIN lock**: `setKeyguardDisabled(true)` with no credential, `onPasswordChanged` with
  `<limit-password />` and `KEYGUARD_DISABLE_SECURE_CAMERA` exist on 13. 13 has no policy engine, so DPMS checks the
  admin's declared policies, and `device_admin.xml` declares all five. The power-button camera gesture has only
  `camera_double_tap_power_gesture_disabled` there (emulator.md:116). `CameraLock`'s suspension and
  `setCameraDisabled` don't depend on it.
- **Notification listener**: 13 introduced "restricted settings" for sideloaded apps, so the adb grant at provisioning
  (`cmd notification allow_listener`) is the same runbook as today.
- **Wipe**: item 3. **Provisioning**: QR/NFC needs `GET_PROVISIONING_MODE`/`ADMIN_POLICY_COMPLIANCE` since 12, and we
  have both. Sensor-permission self-grants for a fully managed device work as on 14 [unverified].
- **Full-screen intents**: 13 grants `USE_FULL_SCREEN_INTENT` at install. The 14+ limits don't apply, so calls and
  VoIP rings are no worse.
- **Bluetooth (design 52)**: `BluetoothA2dp.connect`/`setActiveDevice` were checked as `@UnsupportedAppUsage`
  without `maxTargetSdk` on AOSP 14/15/16 only (`docs/design/52-bluetooth-headphones.md` §0). On 13 Bluetooth is
  already the `com.android.btservices` mainline module [unverified]. Re-check 13's annotations and service checks. The
  code fails soft (`BluetoothAudio.call`), so the worst case is "Fikk ikke kontakt".
- **What 13 does not have and we don't use**: `setUserControlDisabledPackages` (banned anyway), update ownership
  (`setRequestUpdateOwnership`), and the 14 policy engine's per-permission checks (`MANAGE_DEVICE_POLICY_*`).

### 2.4 Build, CI and guards

- `minSdk = 33` in both `build.gradle.kts` files, and `-androidapi 33` in the tsnet action (item 14).
- **Make `NewApi` fail the build.** CI runs lint today, but `lint { abortOnError = false }` (launcher
  `build.gradle.kts:184-186`, music `:95`), so a new 34+ call would pass silently. Proposal: `fatal += "NewApi"`, and
  either `abortOnError = true` with a lint baseline for today's other findings, or a separate lint task with only
  `NewApi`. This is the guard that keeps 13 honest after the one-off fix.
- Pure tests: `wipeCall`/`remoteWipeReady`/`pendingAdminPolicies` already take `sdkInt`. New pure deciders get the
  same `sdkInt` parameter and a row for 33:
  - `dialerRoleAction(..., sdk)` returning "prompt only".
  - `clockStopShown(sdk)`.
  - `visibleSendMode(sdk)`.
  The source-scan tests that pin `VisibleSender`, `ClockActions`, `KidInCallService` and `AdminPolicies` need their
  patterns updated.
- CI has no emulator today, and this doesn't change that. An API 33 instrumented job
  (`reactivecircus/android-emulator-runner`, pinned by SHA) would be new infrastructure for little gain over the local
  loop.

### 2.5 Server

- Nothing is required. Recommended: send the phone's Android version in the status report (§4.4), so the PWA can say
  "Android 13: default apps can't be locked; the phone app role is granted at setup" next to `call_state`'s role
  warnings, and so the parent sees which phones are on an unpatched OS.

### 2.6 Testing cost

- **AVD**: `system-images;android-33;google_apis_playstore;x86_64` (a ~1.5-2 GB download; the disk has room), resized
  like `handy36` (480x854 @320 dpi, `wm density 240`).
- **The VM runs one emulator at a time** (`local-build-env` memory: OOM kills with parallel builds). Every emulator
  round touching kiosk, lock, calls or roles then runs twice, once on 36 and once on 33. That is roughly +50-80 % of
  emulator time for those rounds, from now on.
- **API 33 round**:
  - `scripts/smoke-test.sh` (lock, calls, `REBOOT=1`).
  - `emulator.md` §5b (SSE), §6c (PIN lock), §6d (update fence, notification rule), §6e (recents/lock task), §6g
    (`remote_wipe_ready` only, never a real wipe), §6j/§6k (debug hooks).
  - The role flows: adb grant of DIALER, the prompt path, stealing the role through another dialer's request.
  - The call screen's speaker and mute (item 11), if the 33 image's modem answers `gsm call`. `emulator.md:255` says
    the 16 image's modem simulator doesn't [unverified for 33].
  - Design 32's flash checks (b) and (d).
- **Device**: one real 13 phone, if a concrete one is the reason for the work.

## 3. Android 17 (API 37)

### 3.1 What hits every app on Android 17, whatever its target [docs]

| Change | Us | Action |
|---|---|---|
| **Background audio hardening** (all apps). Playback (`AudioTrack.write`, AAudio, OpenSL; Media3/ExoPlayer "could be impacted"), focus requests and volume/ringer calls (`setStreamVolume`, `adjustStreamVolume`, `setRingerMode`, ...) fail silently from the background unless the app has a visible activity or a running FGS that isn't `SHORT_SERVICE` | **Launcher:**<br>- The anchor `CommandListenerService` (specialUse) runs whenever the process does, so these keep working at target 36: Find My Device's ring (`FindMyDeviceRing`, alarm stream + `setStreamVolume(STREAM_ALARM)`) and `VolumeCap`'s `setStreamVolume(STREAM_MUSIC)` from `VOLUME_CHANGED`.<br>- `VoipRinger` and `QuickControls.setSound` run with our screen visible.<br>**Music:**<br>- Plays inside Media3's `mediaPlayback` FGS.<br>- Exposed: a play from the background while the FGS is down, e.g. `CALL_RELEASE` resuming after a long call, or the Spotify WebView's audio if `PlaybackService` has left the foreground | Emulator check on 37: `adb shell dumpsys audio`, and `logcat` lines `AudioHardening` with our package (`level: partial` = no FGS, `level: full` = FGS without WIU). Run `cmd audio set-enable-hardening throw` to make failures loud |
| **App memory limits** (RAM-based, for leaks and outliers). Exit reason `REASON_OTHER`, description `MemoryLimiter:AnonSwap` | The launcher is a long-lived home process with icon and wallpaper caches and tsnet's Go heap. If it is killed, Home restarts, but for a moment nothing holds the kiosk | Cheap: report `ApplicationExitInfo` exits with `MemoryLimiter` in the crash upload (`crash/CrashReports`), so a kill reaches the server. `am memory-limiter status` on the emulator |
| **Bluetooth autonomous re-pairing**, "users are prompted to confirm" | Is the confirm dialog reachable under the kiosk's app block or over the PIN lock? It is a system activity (Settings or Bluetooth) | Device check [unverified] |
| **Lock task**: an external display can only mirror | None | - |
| **Theft Detection Lock / Remote Lock on by default** (GMS features) [3rd] | Both need an Android screen lock. Our phones run keyguard "None" under handy's own lock, so they can't engage [unverified]. A phone with an Android PIN is unaffected by us | Note only |
| Cross-profile loopback blocked; SMS OTP delayed 3 h; IME visibility; touchpad pointer capture | None. No work profile; tsnet's SOCKS proxy is same-profile loopback; we read no SMS | - |
| `usesCleartextTraffic` deprecated "in a future release" (not yet enforced) | Launcher manifest `android:usesCleartextTraffic="true"` (the server over the tailnet is http). The music app already has `networkSecurityConfig` | Forward-compatible move (§3.4) |

Beyond the documented changes, the kiosk depends on platform internals that Google doesn't document as behaviour
changes. Each needs re-reading on AOSP 17 source, or the API 37 emulator round:
- The lock-task flag map behind the status-bar heal (`StatusBarHealTest`).
- `LockTaskController`'s emergency exemption.
- The recents provider and quickstep starting inside lock task.
- PermissionController's "blocked by your admin" behaviour for role requests.
- `BroadcastSkipPolicy`'s line refs.
- Notification autogrouping (`config_autoGroupAtCount`, design 42's fix).
- Safety Center's channel ids (`PLATFORM_NAG_CHANNELS`).
- `GestureLauncherService`'s settings.
- `ACTION_SHUTDOWN` delivery (design 38).
- `setSystemSetting`'s allowlist.

The design 52 note also applies: "re-check the annotations on an SDK bump". Whether AOSP 17's source is published
yet is itself to check [unverified: Google changed its AOSP publishing cadence in 2025-26].

### 3.2 What `targetSdk 37` would change for us [docs]

| Change (target 37) | Us | Needed |
|---|---|---|
| **`ACCESS_LOCAL_NETWORK`** (new runtime permission, `NEARBY_DEVICES` group, mandatory at 37; a device owner may pre-grant it with `setPermissionGrantState` [docs: work/versions/android-17]) | **Music:**<br>- Sonos is all LAN: SSDP multicast (`sonos/Ssdp.kt`), UPnP HTTP and the WebSocket (`SonosSocket`).<br>- The emulator loop's `fake-sonos.py` on 10.0.2.2 counts too [unverified].<br>**Launcher:**<br>- Design 36's custom DNS server on the home LAN (protected sockets; whether a VPN app's protected sockets are exempt is [unverified]).<br>- tsnet's direct LAN paths to the Pi. Without them, magicsock should fall back to DERP: it works, but adds latency and battery [unverified] | Declare it in both manifests. The launcher self-grants it (as with `NEARBY_WIFI_DEVICES`) and adds it to `MUSIC_RUNTIME_GRANTS` for the music app, both only on 37+ (an unknown permission can't be granted below). Without these, Sonos silently finds nothing |
| **Background audio**: a background app must hold an FGS **with while-in-use (WIU)** capability. An FGS started from the background normally has none. Waiver: the exact-alarm permission + `USAGE_ALARM` streams | **Launcher:**<br>- The anchor is started at boot and from the background (`SyncRunner.request`). Whether a device owner's background-started FGS gets WIU is the open question. Device owners are background-activity-start exempt, and the docs grant WIU to an FGS started "with background activity launch capability", so likely yes [unverified: AOSP `ActiveServices`].<br>- If it doesn't: `VolumeCap`'s background `setStreamVolume(STREAM_MUSIC)` silently stops working (the volume cap fails open). Find My Device's ring is on the alarm stream with `USE_EXACT_ALARM` held, so probably waived, but whether `USE_EXACT_ALARM` counts as "the exact alarm permission" is [unverified].<br>**Music:**<br>- Media3's FGS started from a tap or media key has WIU. A resume from the launcher's `CALL_RELEASE` while the FGS is down would not | Check the anchor's `allowWhileInUsePermissionInFgs` in `dumpsys activity services` and `AudioHardening` in `dumpsys audio` on 37. If it lacks WIU, `VolumeCap` needs another path. Music: keep the FGS through a call (bg-audio §3: through "transient" losses under 10 min) or start nothing on `CALL_RELEASE` after a long call |
| **Background activity launch hardening** extends to `IntentSender`. Replace `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` with `..._ALLOW_IF_VISIBLE` | **Already done**: `VisibleSender` uses `ALLOW_IF_VISIBLE` on 36+ (`lock/VisibleSender.kt:18-19`), `ClockActions` uses `DENIED`. We use no `startIntentSender*` | Nothing. Re-run the VoIP, glance and music sends on 37 |
| **Certificate Transparency on by default** | TLS clients:<br>- The parent's DoH/DoT server (OkHttp/SSLSocket on the platform trust manager).<br>- Cloudflare/Quad9 by IP.<br>- The music app's hosts (Storytel, NRK, RSS, Spotify), all public CAs that log certificates.<br>- The server itself is http over the tailnet.<br>Risk: a parent's DoH server with an unlogged certificate | Low. Test design 36's §6m on 37 |
| **`static final` fields unmodifiable** (reflection: `IllegalAccessException`; JNI `SetStatic*Field`: a crash) | We reflect only on methods (`QuickControls.kt:516`, `:691`, `BluetoothAudio.kt:340`, `PowerEvents.kt:369`, music `BluetoothLink.kt:167`). Third-party code: gomobile's JNI glue in tsnet.aar, dnsjava, pcap4j [unverified] | Smoke-test the release build at target 37. A JNI write in tsnet would crash at connect, which is the boot-loop class of incident in launcher `CLAUDE.md`. Test with the tsnet crash-loop guard in place |
| **Lock-free `MessageQueue`** | We don't reflect on it; there's no Robolectric | Nothing |
| **Large screens**: orientation and resizability restrictions ignored on sw ≥ 600 dp, no opt-out | Phones only. The music app's `screenOrientation="portrait"` would be ignored on a tablet | Nothing for our hardware |
| **Hidden APIs**: Android 17's lists may move `BluetoothA2dp.connect`/`disconnect`/`setActiveDevice` (today `@UnsupportedAppUsage` without `maxTargetSdk`) to `max-target-36` | Kid Settings' Connect and the music app's "Spill av på" | Check `hiddenapi-flags.csv` for 37 (or `veridex`). Both fail soft to `false` |
| `ContentCaptureManager.setContentCaptureEnabled(false)` no longer works; RemoteViews memory cap; CP2 columns; RFCOMM `-1` | Not used (no widgets, no RFCOMM, no `ContactsContract` query - contacts come from the server; the call path reads the call log only) | Nothing [code: grep] |

**The build chain is the larger cost.**
- `compileSdk 37` needs **AGP 9.1.1 or newer**, per one translated developer.android.com table [3rd/unverified;
  check the English "Android API level support" table]. AGP 9.2 needs Gradle 9.4.1.
- We are on AGP 8.13 / Kotlin 2.1.20 (`launcher/gradle/libs.versions.toml:4`, `:14`).
- AGP 9 brings built-in Kotlin. The launcher's `kapt` (the PreferenceProcessor) needs the legacy-kapt path, and the
  music app's KSP version must follow Kotlin [unverified details].
- Both `verification-metadata.xml` files need regenerating, with CI's x86_64 aapt2 entry added by hand (root
  `CLAUDE.md`, "Gradle build chain").
- `-PwarningsAsErrors=true` will surface the new deprecations.
- Running on Android 17 needs none of this. Only *targeting* 37 does.

### 3.3 Android 16 changes we already target (for completeness)

These are absorbed at target 36 and are simply less strict on 13 and 14:
- Edge-to-edge enforcement (`KidInsets`; no `windowOptOutEdgeToEdgeEnforcement`).
- Predictive back: `onBackPressed` isn't called; we use `OnBackInvokedCallback`.
- The `ALLOW_IF_VISIBLE` mode (`VisibleSender`).
- `JobScheduler` runtime quotas, which only touch the music app's WorkManager jobs. The launcher has no jobs: the
  anchor and alarms (design 19, `v0.8.0`).
- Orientation and resizability ignored on large screens, with the opt-out that 37 removes.
- 15's `dataSync` cap, avoided with `specialUse`.
- 15's `BOOT_COMPLETED` FGS rules.
- 16's notification autogrouping at 2 (design 42).

### 3.4 Forward-compatible now (cheap, no target change)

1. Declare `android.permission.ACCESS_LOCAL_NETWORK` in both manifests. The launcher self-grants it and grants it to
   the music app on `SDK_INT >= 37` only. Harmless at target 36 (not enforced), ready for 37, and it closes the
   emulator-loop surprise for Sonos.
2. Move the launcher from `usesCleartextTraffic="true"` to a `networkSecurityConfig` that permits cleartext (the music
   app's pattern). The deprecation is announced, not dated.
3. Report `MemoryLimiter` exits (§3.1).
4. Add the Android version to the status report (§4.4).

## 4. Effort, risk, recommendation

### 4.1 Android 13

- **Rounds** (the usual architect -> design QA -> implementation -> code QA -> fix chain):
  - One implementation round across both apps (about 15 call sites in 13 files, the tsnet flag and the lint guard).
  - One fix round.
  - One API 33 emulator round (Sonnet) with its fix round.
- **Calendar**: two to three working days of agent time, gated on the emulator's RAM.
- **Risk**: the guards are mechanical. The unknowns are AOSP 13's kiosk internals (§2.3), design 32's legacy
  transitions, and the call screen's two audio-route paths. A mistake there shows on the kid's lock or call screen,
  not in tests.
- **Lasting cost**: every later kiosk, lock or call change needs an API 33 pass. With one emulator at a time, those
  rounds take noticeably longer.
- **What 13 loses for good**: the default-apps lock, the silent dialer role, data-sensitive views, and Stop on the
  clock tile. Plus an OS without security patches.

### 4.2 Android 17 at `targetSdk 36`

- **Rounds**: one emulator round on an API 37 AVD (`system-images;android-37;google_apis_playstore;x86_64`): the smoke
  test, the §3.1 checks (audio hardening, memory limiter, internals), design 36 §6m, design 52's Bluetooth path.
- **Code**: none expected. Fixes only if a check fails.
- **Risk**: low to medium. Only the internals list in §3.1 is unknown, and Pixels already run 17.

### 4.3 `targetSdk 37`

- **Rounds**:
  - A build-chain round (AGP 9.1+, Gradle 9, kapt/KSP, verification metadata, aapt2 by hand).
  - A behaviour round (local network if §3.4 isn't done, WIU for `VolumeCap`, the hidden-API check, a JNI smoke test
    of tsnet).
  - An emulator round.
- **Risk**: medium. The build chain is the churn. A silent `VolumeCap` fail-open is the one behaviour risk with a
  safety angle.

### 4.4 Status field (optional, both 13 and 17)

`StatusReportRequest` has no Android version today (`server/dto/StatusReportRequest.kt`). Add `android_sdk`
(`SDK_INT`) and `android_sdk_full` (`SDK_INT_FULL`, 36+) as nullable fields. This is a contract change in both
directories in one commit (`api-both-dirs`), with the PWA showing "Android 13 (no security updates)" and the
13-specific role text.

### 4.5 Recommendation

- **Android 13: don't lower `minSdk` by default.**
  - The code is small (one round), but it buys a permanent extra test pass, two weaker device-owner guarantees and an
    unpatched OS.
  - For the Jelly Star line, take Unihertz's Android 16 OTA before provisioning: one OTA and a reset per phone. The
    test unit is already on 16 per `02-calls.md`.
  - Do the work only for a concrete phone that can't leave 13, and then as written in §2, with the `NewApi` lint guard
    first.
- **Android 17: yes, run the API 37 emulator round now**, plus the four forward-compatible items in §3.4. Phones on 17
  exist today, and the kiosk's reliance on internals is the only real exposure.
- **`targetSdk 37`: not early.** Nothing forces it: we don't ship through Play, and Play's own deadline is mid-2027.
  Schedule it together with the AGP 9 migration in H1 2027, after 37.1/37.2 settle. By then the local-network grant
  and cleartext config from §3.4 are already in place.

### 4.6 What to watch

- **Android 17 QPR1/QPR2 betas** (Bayton's tracker for the enterprise surface):
  - the typed policy engine (`setPolicy`/`getResolvedDeviceWidePolicy`, provisional, QPR2), in case it changes how a
    device owner's user restrictions or lock-task calls resolve;
  - any lock-task or `setKeyguardDisabled` change;
  - the `usesCleartextTraffic` removal date;
  - the hidden-API lists for 37.x;
  - background audio moving from "target 37" to "all apps" for the WIU rule (the `set-enable-hardening enable` switch
    already simulates that).
- **Android 18**: implicit URI grants for `ACTION_SEND`/`IMAGE_CAPTURE` end there [docs]. We don't send those today.

Sources (read 2026-10-21): developer.android.com `about/versions/17/behavior-changes-17`, `behavior-changes-all`,
`changes/bg-audio`, `work/versions/android-17`, `about/versions/17`; bayton.org `android-17-enterprise-features` and its
beta tracker; unihertz.com "Unihertz Announces Planned Android 16 Upgrades for Titan 2 and Jelly Star"; heise.de
11262704 (Android 13 end of support); the translated "About AGP" table for the AGP minimum for API 37.
